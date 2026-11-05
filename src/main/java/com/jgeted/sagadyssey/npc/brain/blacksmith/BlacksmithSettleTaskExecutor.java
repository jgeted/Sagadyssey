package com.jgeted.sagadyssey.npc.brain.blacksmith;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.execution.NpcEntityExecutionContext;
import com.jgeted.sagadyssey.npc.brain.execution.NpcExecutionContext;
import com.jgeted.sagadyssey.npc.brain.execution.NpcTaskExecutor;
import com.jgeted.sagadyssey.npc.brain.execution.PlanCancellationReason;
import com.jgeted.sagadyssey.npc.brain.execution.TaskTickResult;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.social.production.ProductionBlockedException;
import com.jgeted.sagadyssey.social.production.ProductionOrder;
import com.jgeted.sagadyssey.social.production.ProductionOrderData;
import com.jgeted.sagadyssey.social.production.ProductionOrderStatus;
import com.jgeted.sagadyssey.social.production.ProductionService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;

/** 以固定 batchId 幂等结算；输出满时保留生产批次并等待重试。 */
public final class BlacksmithSettleTaskExecutor implements NpcTaskExecutor {
    @Override
    public ResourceLocation id() {
        return BlacksmithTaskIds.SETTLE;
    }

    @Override
    public TaskTickResult start(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) {
        return settle(context, plan, step);
    }

    @Override
    public TaskTickResult tick(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) {
        return settle(context, plan, step);
    }

    @Override
    public TaskTickResult recover(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) {
        return settle(context, plan, step);
    }

    private TaskTickResult settle(NpcExecutionContext rawContext, NpcPlan plan,
                                  NpcPlanStep step) {
        NpcEntityExecutionContext context = BlacksmithExecutionSupport.entityContext(rawContext);
        long now = context.gameTime();
        ProductionOrder order = BlacksmithExecutionSupport.requireOrder(context, step);
        if (order.status() == ProductionOrderStatus.COMPLETED) {
            return TaskTickResult.stepSucceeded("订单已经幂等结算");
        }
        if (order.terminal()) return TaskTickResult.failed("生产订单已经终止: " + order.status());
        order = BlacksmithExecutionSupport.resumeIfBlocked(context, order, now);
        if (order.status() != ProductionOrderStatus.SETTLING) {
            return TaskTickResult.failed("订单尚未进入结算阶段: " + order.status());
        }
        BlacksmithExecutionSupport.ReservationCheck check =
                BlacksmithExecutionSupport.reserveEphemeral(context, plan, order,
                        NpcBrainRuntime.reservations(context.level().getServer()));
        if (!check.successful()) {
            return BlacksmithExecutionSupport.block(context, order, check.code(), check.detail(),
                    now + 40L);
        }
        Container container;
        try {
            container = BlacksmithExecutionSupport.targetContainer(context, order);
        } catch (RuntimeException exception) {
            NpcBrainRuntime.reservations(context.level().getServer()).releasePlan(plan.id());
            ProductionOrder failed = order.transition(ProductionOrderStatus.FAILED, now,
                    "成品容器身份无效: " + safeMessage(exception));
            ProductionOrderData.get(context.level().getServer()).put(failed);
            return TaskTickResult.failed(failed.detail());
        }
        if (container == null) {
            NpcBrainRuntime.reservations(context.level().getServer()).releasePlan(plan.id());
            return BlacksmithExecutionSupport.block(context, order, "CONTAINER_UNAVAILABLE",
                    "成品容器未加载或不是仓储容器", now + 100L);
        }
        try {
            ProductionService.complete(context.level().getServer(), order.batchId(), container,
                    BlacksmithExecutionSupport.requiredWorkTicks(step), now);
            ProductionOrder completed = order.transition(ProductionOrderStatus.COMPLETED, now,
                    "原料已消费，成品已登记并存入目标容器");
            ProductionOrderData.get(context.level().getServer()).put(completed);
            context.npc().reportWorkActivity("订单完成", order.targetContainerPosition(),
                    "订单=" + order.id());
            return TaskTickResult.stepSucceeded("生产订单结算完成");
        } catch (ProductionBlockedException blocked) {
            NpcBrainRuntime.reservations(context.level().getServer()).releasePlan(plan.id());
            return BlacksmithExecutionSupport.block(context, order, blocked.code(),
                    blocked.getMessage(), now + 100L);
        } catch (RuntimeException exception) {
            NpcBrainRuntime.reservations(context.level().getServer()).releasePlan(plan.id());
            ProductionOrder failed = order.transition(ProductionOrderStatus.FAILED, now,
                    "生产结算失败: " + safeMessage(exception));
            ProductionOrderData.get(context.level().getServer()).put(failed);
            return TaskTickResult.failed(failed.detail());
        }
    }

    @Override
    public void cancel(NpcExecutionContext rawContext, NpcPlan plan, NpcPlanStep step,
                       PlanCancellationReason reason) {
        NpcEntityExecutionContext context = BlacksmithExecutionSupport.entityContext(rawContext);
        NpcBrainRuntime.reservations(context.level().getServer()).releasePlan(plan.id());
    }

    private static String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
