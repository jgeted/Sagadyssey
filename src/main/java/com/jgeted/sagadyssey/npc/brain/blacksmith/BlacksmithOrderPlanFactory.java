package com.jgeted.sagadyssey.npc.brain.blacksmith;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPriority;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStepStatus;
import com.jgeted.sagadyssey.social.production.ProductionOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 将已认领的铁匠生产订单转换为固定、可持久化的四阶段计划。 */
public final class BlacksmithOrderPlanFactory {
    public static final int MIN_REQUIRED_WORK_TICKS = 1;
    public static final int MAX_REQUIRED_WORK_TICKS = 12_000;
    public static final long MAX_PLAN_LIFETIME_TICKS = 72_000L;
    private static final long COMMITMENT_TICKS = 200L;

    private BlacksmithOrderPlanFactory() {
    }

    public static NpcPlan create(UUID planId, UUID npcEntityId, UUID workerId,
                                 ProductionOrder order,
                                 long now, long expiresAt) {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(npcEntityId, "npcEntityId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(order, "order");
        validateOrder(workerId, order, now);
        validateTiming(now, expiresAt, order.requiredWorkTicks());

        Map<String, String> identity = identityParams(order);
        Map<String, String> workstation = locatedParams(identity,
                order.workstationDimension(), order.workstationPosition());
        Map<String, String> target = locatedParams(identity,
                order.targetContainerDimension(), order.targetContainerPosition());

        List<NpcPlanStep> steps = List.of(
                step(0, NpcPlanPhase.RESERVING, BlacksmithTaskIds.RESERVE_ORDER, identity, now),
                step(1, NpcPlanPhase.TRAVELLING, BlacksmithTaskIds.MOVE_TO_BLOCK, workstation, now),
                step(2, NpcPlanPhase.ACTING, BlacksmithTaskIds.FORGE, workstation, now),
                step(3, NpcPlanPhase.SETTLING, BlacksmithTaskIds.SETTLE, target, now));
        long commitmentUntil = now + Math.min(COMMITMENT_TICKS, expiresAt - now);
        return new NpcPlan(planId, npcEntityId, "production-order:" + order.id(), BlacksmithTaskIds.PROVIDER,
                NpcPlanKind.WORK, NpcPlanPriority.NORMAL, NpcPlanStatus.SELECTED,
                steps, 0, now, now, commitmentUntil, expiresAt, null,
                "执行生产订单 " + order.id());
    }

    private static void validateOrder(UUID workerId, ProductionOrder order, long now) {
        if (order.terminal()) {
            throw new IllegalArgumentException("terminal production order cannot become a plan");
        }
        if (!workerId.equals(order.assignedWorkerId())) {
            throw new IllegalArgumentException("production order is not assigned to this NPC");
        }
        if (order.workstationDimension() == null || order.workstationPosition() == null) {
            throw new IllegalArgumentException("production order requires a workstation");
        }
        if (now < 0L || now < order.updatedAt()) {
            throw new IllegalArgumentException("plan time cannot precede the production order");
        }
    }

    private static void validateTiming(long now, long expiresAt, int requiredWorkTicks) {
        if (requiredWorkTicks < MIN_REQUIRED_WORK_TICKS
                || requiredWorkTicks > MAX_REQUIRED_WORK_TICKS) {
            throw new IllegalArgumentException("requiredWorkTicks must be between "
                    + MIN_REQUIRED_WORK_TICKS + " and " + MAX_REQUIRED_WORK_TICKS);
        }
        if (expiresAt <= now) {
            throw new IllegalArgumentException("plan expiry must follow creation time");
        }
        long lifetime = expiresAt - now;
        if (lifetime > MAX_PLAN_LIFETIME_TICKS) {
            throw new IllegalArgumentException("plan lifetime exceeds "
                    + MAX_PLAN_LIFETIME_TICKS + " ticks");
        }
        if (lifetime < requiredWorkTicks) {
            throw new IllegalArgumentException("plan lifetime cannot fit required work ticks");
        }
    }

    private static Map<String, String> identityParams(ProductionOrder order) {
        return Map.of(BlacksmithTaskIds.ORDER_ID, order.id().toString(),
                BlacksmithTaskIds.BATCH_ID, order.batchId().toString(),
                BlacksmithTaskIds.REQUIRED_WORK_TICKS,
                Integer.toString(order.requiredWorkTicks()));
    }

    private static Map<String, String> locatedParams(Map<String, String> identity,
                                                     String dimension, BlockPos position) {
        Map<String, String> params = new LinkedHashMap<>(identity);
        params.put(BlacksmithTaskIds.DIMENSION, dimension);
        params.put(BlacksmithTaskIds.X, Integer.toString(position.getX()));
        params.put(BlacksmithTaskIds.Y, Integer.toString(position.getY()));
        params.put(BlacksmithTaskIds.Z, Integer.toString(position.getZ()));
        return Map.copyOf(params);
    }

    private static NpcPlanStep step(int index, NpcPlanPhase phase,
                                    ResourceLocation executorId,
                                    Map<String, String> params, long now) {
        return new NpcPlanStep(index, phase, executorId, params,
                NpcPlanStepStatus.PENDING, now, "等待执行");
    }

}
