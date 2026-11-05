package com.jgeted.sagadyssey.npc.brain.blacksmith;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.execution.NpcEntityExecutionContext;
import com.jgeted.sagadyssey.npc.brain.execution.NpcExecutionContext;
import com.jgeted.sagadyssey.npc.brain.execution.NpcTaskExecutor;
import com.jgeted.sagadyssey.npc.brain.execution.PlanCancellationReason;
import com.jgeted.sagadyssey.npc.brain.execution.TaskTickResult;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.social.production.ProductionBatchStatus;
import com.jgeted.sagadyssey.social.production.ProductionBlockedException;
import com.jgeted.sagadyssey.social.production.ProductionOrder;
import com.jgeted.sagadyssey.social.production.ProductionOrderData;
import com.jgeted.sagadyssey.social.production.ProductionOrderStatus;
import com.jgeted.sagadyssey.social.production.ProductionMaterialRequest;
import com.jgeted.sagadyssey.social.production.ProductionMaterialRequestData;
import com.jgeted.sagadyssey.social.production.ProductionService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;

import java.util.Set;

/** 原子地认领订单，并在任何动作开始前锁定原料、工作站与输出目的地。 */
public final class BlacksmithReserveOrderTaskExecutor implements NpcTaskExecutor {
    @Override
    public ResourceLocation id() {
        return BlacksmithTaskIds.RESERVE_ORDER;
    }

    @Override
    public TaskTickResult start(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) {
        return reserve(context, plan, step);
    }

    @Override
    public TaskTickResult tick(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) {
        return reserve(context, plan, step);
    }

    @Override
    public TaskTickResult recover(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) {
        return reserve(context, plan, step);
    }

    private TaskTickResult reserve(NpcExecutionContext rawContext, NpcPlan plan,
                                   NpcPlanStep step) {
        NpcEntityExecutionContext context = BlacksmithExecutionSupport.entityContext(rawContext);
        long now = context.gameTime();
        ProductionOrder order = BlacksmithExecutionSupport.requireOrder(context, step);
        if (order.status() == ProductionOrderStatus.COMPLETED) {
            return TaskTickResult.stepSucceeded("订单已经完成");
        }
        if (order.terminal()) return TaskTickResult.failed("生产订单已经终止: " + order.status());
        order = BlacksmithExecutionSupport.resumeIfBlocked(context, order, now);
        ProductionOrderData orders = ProductionOrderData.get(context.level().getServer());
        if (order.status() == ProductionOrderStatus.OPEN) {
            order = order.claim(context.npc().getCharacterId(), now, "铁匠领取订单");
            orders.put(order);
        }
        boolean alreadyReserved = order.status() == ProductionOrderStatus.RESERVED
                || order.status() == ProductionOrderStatus.IN_PROGRESS
                || order.status() == ProductionOrderStatus.SETTLING;
        if (order.status() != ProductionOrderStatus.CLAIMED && !alreadyReserved) {
            return TaskTickResult.failed("订单状态不能预留: " + order.status());
        }
        if (!BlacksmithExecutionSupport.workstationAvailable(context, order)) {
            return BlacksmithExecutionSupport.block(context, order, "WORKSTATION_UNAVAILABLE",
                    "订单指定的铁砧或锻造台未加载或已被移除", now + 100L);
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
                    "生产容器身份无效: " + safeMessage(exception));
            orders.put(failed);
            return TaskTickResult.failed(failed.detail());
        }
        if (container == null) {
            NpcBrainRuntime.reservations(context.level().getServer()).releasePlan(plan.id());
            return BlacksmithExecutionSupport.block(context, order, "CONTAINER_UNAVAILABLE",
                    "原料与成品容器未加载或不是仓储容器", now + 100L);
        }
        reconcileMaterialRequests(context, order, container, now);
        if (!ProductionService.canAcceptOutput(context.level().getServer(),
                order.targetContainerId(), container, order.outputOwner(), order.output(), now)) {
            NpcBrainRuntime.reservations(context.level().getServer()).releasePlan(plan.id());
            return BlacksmithExecutionSupport.block(context, order, "OUTPUT_FULL",
                    "成品容器没有空槽", now + 100L);
        }
        try {
            var batch = ProductionService.begin(context.level().getServer(), order.batchId(),
                    context.npc().getCharacterId(), order.outputOwner(), order.targetContainerId(),
                    container, order.recipeId(), order.inputs(), order.output(),
                    BlacksmithExecutionSupport.requiredWorkTicks(step), now);
            if (batch.status() == ProductionBatchStatus.COMPLETED) {
                ProductionOrder completed = completeFromCurrent(order, now);
                orders.put(completed);
                return TaskTickResult.stepSucceeded("生产批次已经完成");
            }
            if (!alreadyReserved) {
                ProductionOrder reserved = order.transition(ProductionOrderStatus.RESERVED, now,
                        "原料、工作站与输出位置已预留");
                orders.put(reserved);
            }
            fulfillMaterialRequests(context, order, now);
            return TaskTickResult.stepSucceeded(alreadyReserved
                    ? "既有生产资源已经重新核验并续租" : "生产资源预留完成");
        } catch (ProductionBlockedException blocked) {
            NpcBrainRuntime.reservations(context.level().getServer()).releasePlan(plan.id());
            if ("MISSING_INPUT".equals(blocked.code())) {
                openMaterialRequests(context, order, container, now);
            }
            return BlacksmithExecutionSupport.block(context, order, blocked.code(),
                    blocked.getMessage(), now + 100L);
        } catch (RuntimeException exception) {
            NpcBrainRuntime.reservations(context.level().getServer()).releasePlan(plan.id());
            ProductionOrder failed = order.transition(ProductionOrderStatus.FAILED, now,
                    "生产预留失败: " + safeMessage(exception));
            orders.put(failed);
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

    private static ProductionOrder completeFromCurrent(ProductionOrder order, long now) {
        ProductionOrder current = order;
        if (current.status() == ProductionOrderStatus.CLAIMED) {
            current = current.transition(ProductionOrderStatus.RESERVED, now, "批次已存在");
        }
        if (current.status() == ProductionOrderStatus.RESERVED) {
            current = current.transition(ProductionOrderStatus.IN_PROGRESS, now, "批次已存在");
        }
        if (current.status() == ProductionOrderStatus.IN_PROGRESS) {
            current = current.transition(ProductionOrderStatus.SETTLING, now, "批次已存在");
        }
        return current.status() == ProductionOrderStatus.SETTLING
                ? current.transition(ProductionOrderStatus.COMPLETED, now, "批次已经完成")
                : current;
    }

    private static void openMaterialRequests(NpcEntityExecutionContext context,
                                             ProductionOrder order, Container container,
                                             long now) {
        ProductionMaterialRequestData requests = ProductionMaterialRequestData.get(
                context.level().getServer());
        for (var material : ProductionService.missingInputs(context.level().getServer(),
                order.targetContainerId(), container, order.outputOwner(), order.inputs(), now)) {
            requests.openOrGet(order.id(), order.targetContainerId(), material, now,
                    "生产订单缺少输入物资");
        }
    }

    private static void fulfillMaterialRequests(NpcEntityExecutionContext context,
                                                ProductionOrder order, long now) {
        ProductionMaterialRequestData requests = ProductionMaterialRequestData.get(
                context.level().getServer());
        for (ProductionMaterialRequest request : requests.forOrder(order.id())) {
            if (request.status() == ProductionMaterialRequest.Status.OPEN) {
                requests.fulfill(request.idempotencyKey(), now, "订单输入已经足额预留");
            }
        }
    }

    private static void reconcileMaterialRequests(NpcEntityExecutionContext context,
                                                  ProductionOrder order, Container container,
                                                  long now) {
        ProductionMaterialRequestData requests = ProductionMaterialRequestData.get(
                context.level().getServer());
        Set<String> missingKeys = ProductionService.missingInputs(context.level().getServer(),
                        order.targetContainerId(), container, order.outputOwner(),
                        order.inputs(), now).stream()
                .map(material -> ProductionMaterialRequest.keyFor(order.id(), material))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (ProductionMaterialRequest request : requests.forOrder(order.id())) {
            if (request.status() == ProductionMaterialRequest.Status.OPEN
                    && !missingKeys.contains(request.idempotencyKey())) {
                requests.fulfill(request.idempotencyKey(), now, "缺少的输入物资已经到达");
            }
        }
    }
}
