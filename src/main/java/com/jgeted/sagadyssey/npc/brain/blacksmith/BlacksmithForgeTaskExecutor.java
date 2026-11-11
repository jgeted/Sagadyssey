package com.jgeted.sagadyssey.npc.brain.blacksmith;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.execution.NpcEntityExecutionContext;
import com.jgeted.sagadyssey.npc.brain.execution.NpcExecutionContext;
import com.jgeted.sagadyssey.npc.brain.execution.NpcTaskExecutor;
import com.jgeted.sagadyssey.npc.brain.execution.PlanCancellationReason;
import com.jgeted.sagadyssey.npc.brain.execution.TaskTickResult;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.social.production.ProductionOrder;
import com.jgeted.sagadyssey.social.production.ProductionOrderData;
import com.jgeted.sagadyssey.social.production.ProductionOrderStatus;
import net.minecraft.resources.Identifier;

/** 持久化劳动起点后只启动一次锻造表现；重载恢复绝不重播动作。 */
public final class BlacksmithForgeTaskExecutor implements NpcTaskExecutor {
    private final java.util.Map<java.util.UUID,com.jgeted.sagadyssey.npc.ai.MovementProgress> movement=new java.util.HashMap<>();
    @Override public void forget(java.util.UUID body,java.util.UUID plan){movement.remove(plan);}
    @Override
    public Identifier id() {
        return BlacksmithTaskIds.FORGE;
    }

    @Override
    public TaskTickResult start(NpcExecutionContext rawContext, NpcPlan plan, NpcPlanStep step) {
        NpcEntityExecutionContext context = BlacksmithExecutionSupport.entityContext(rawContext);
        ProductionOrder order = prepare(context, plan, step);
        TaskTickResult reservationBlock = reservationBlock(context, plan, order);
        if (reservationBlock != null) return reservationBlock;
        if (order.status() == ProductionOrderStatus.SETTLING
                || order.status() == ProductionOrderStatus.COMPLETED) {
            return TaskTickResult.stepSucceeded("劳动已经完成");
        }
        if (order.status() == ProductionOrderStatus.IN_PROGRESS) {
            // 新计划接管被战斗或重载中断的订单时，持久状态就是动作回执。
            return progress(context, order, step);
        }
        if (order.status() != ProductionOrderStatus.RESERVED) {
            return TaskTickResult.failed("订单尚未完成资源预留: " + order.status());
        }
        if (!BlacksmithExecutionSupport.atWorkstation(context, order)) {
            return TaskTickResult.failed("铁匠不在订单工作站的交互范围内");
        }
        ProductionOrder working = order.transition(ProductionOrderStatus.IN_PROGRESS,
                context.gameTime(), "锻造劳动开始");
        ProductionOrderData.get(context.level().getServer()).put(working);
        context.npc().playAction(NpcAction.SMITH,
                BlacksmithExecutionSupport.requiredWorkTicks(step));
        context.npc().reportWorkActivity("按订单锻造", order.workstationPosition(),
                "订单=" + order.id());
        return progress(context, working, step);
    }

    @Override
    public TaskTickResult tick(NpcExecutionContext rawContext, NpcPlan plan, NpcPlanStep step) {
        NpcEntityExecutionContext context = BlacksmithExecutionSupport.entityContext(rawContext);
        ProductionOrder order = prepare(context, plan, step);
        TaskTickResult reservationBlock = reservationBlock(context, plan, order);
        return reservationBlock != null ? reservationBlock : progress(context, order, step);
    }

    @Override
    public TaskTickResult recover(NpcExecutionContext rawContext, NpcPlan plan, NpcPlanStep step) {
        NpcEntityExecutionContext context = BlacksmithExecutionSupport.entityContext(rawContext);
        ProductionOrder order = prepare(context, plan, step);
        TaskTickResult reservationBlock = reservationBlock(context, plan, order);
        if (reservationBlock != null) return reservationBlock;
        // IN_PROGRESS 已经是动作启动的持久回执；恢复时只继续计时。
        return progress(context, order, step);
    }

    private ProductionOrder prepare(NpcEntityExecutionContext context, NpcPlan plan,
                                    NpcPlanStep step) {
        ProductionOrder order = BlacksmithExecutionSupport.requireOrder(context, step);
        if (order.terminal()) return order;
        order = BlacksmithExecutionSupport.resumeIfBlocked(context, order, context.gameTime());
        return order;
    }

    private TaskTickResult reservationBlock(NpcEntityExecutionContext context, NpcPlan plan,
                                            ProductionOrder order) {
        if (order.terminal() || order.status() == ProductionOrderStatus.SETTLING) return null;
        BlacksmithExecutionSupport.ReservationCheck check =
                BlacksmithExecutionSupport.reserveEphemeral(context, plan, order,
                        NpcBrainRuntime.reservations(context.level().getServer()));
        return check.successful() ? null : BlacksmithExecutionSupport.block(context, order,
                check.code(), check.detail(), context.gameTime() + 40L);
    }

    private TaskTickResult progress(NpcEntityExecutionContext context, ProductionOrder order,
                                    NpcPlanStep step) {
        if (order.status() == ProductionOrderStatus.COMPLETED
                || order.status() == ProductionOrderStatus.SETTLING) {
            return TaskTickResult.stepSucceeded("劳动已经完成");
        }
        if (order.status() != ProductionOrderStatus.IN_PROGRESS) {
            return TaskTickResult.failed("订单不在劳动阶段: " + order.status());
        }
        if (!context.npc().isAlive()) return TaskTickResult.failed("原铁匠死亡，停止生产");
        if (context.npc().getTarget()!=null) return TaskTickResult.running("实际威胁，等待执行桥释放预留并中断；不计算劳动");
        if (!BlacksmithExecutionSupport.atWorkstation(context, order)) {
            var route=movement.computeIfAbsent(order.id(),ignored->{var v=new com.jgeted.sagadyssey.npc.ai.MovementProgress();v.reset(context.npc(),order.workstationPosition());return v;});
            if(route.tick(context.npc(),order.workstationPosition())) {movement.remove(order.id());return TaskTickResult.failed("回到原工作站的有限路径失败，取消原生产承诺");}
            if(context.npc().getNavigation().isDone()||context.gameTime()%20==0)
                com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes.move(context.npc(),order.workstationPosition());
            return TaskTickResult.running("离开原工作站；真实返回途中不计算劳动");
        }
        movement.remove(order.id());
        int required=BlacksmithExecutionSupport.requiredWorkTicks(step);
        int worked=com.jgeted.sagadyssey.social.production.ProductionLaborData.get(context.level().getServer())
                .tick(order.id(),required,context.gameTime());
        if (worked < required) {
            return TaskTickResult.running("锻造中，剩余 " + (required-worked) + " tick 实际劳动");
        }
        ProductionOrder settling = order.transition(ProductionOrderStatus.SETTLING,
                context.gameTime(), "劳动完成，等待原子结算");
        ProductionOrderData.get(context.level().getServer()).put(settling);
        return TaskTickResult.stepSucceeded("锻造劳动完成");
    }

    @Override
    public void cancel(NpcExecutionContext rawContext, NpcPlan plan, NpcPlanStep step,
                       PlanCancellationReason reason) {
        NpcEntityExecutionContext context = BlacksmithExecutionSupport.entityContext(rawContext);
        NpcBrainRuntime.reservations(context.level().getServer()).releasePlan(plan.id());
        context.npc().getNavigation().stop();
        movement.remove(BlacksmithExecutionSupport.requireOrder(context,step).id());
    }
}
