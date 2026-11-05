package com.jgeted.sagadyssey.npc.brain.blacksmith;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPriority;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStepStatus;
import com.jgeted.sagadyssey.social.production.ProductionMaterial;
import com.jgeted.sagadyssey.social.production.ProductionOrder;
import com.jgeted.sagadyssey.social.production.ProductionOrderStatus;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlacksmithOrderPlanFactoryTest {
    @Test
    void createsStableFourStepPlanWithOrderAndBatchIdentityEverywhere() {
        UUID npcId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        ProductionOrder order = claimedOrder(workerId, 10L);

        NpcPlan plan = BlacksmithOrderPlanFactory.create(
                planId, npcId, workerId, order, 12L, 1_012L);

        assertEquals(planId, plan.id());
        assertEquals("production-order:" + order.id(), plan.candidateKey());
        assertEquals(BlacksmithTaskIds.PROVIDER, plan.providerId());
        assertEquals(NpcPlanKind.WORK, plan.kind());
        assertEquals(NpcPlanPriority.NORMAL, plan.priority());
        assertEquals(NpcPlanStatus.SELECTED, plan.status());
        assertEquals(12L, plan.createdAt());
        assertEquals(212L, plan.commitmentUntil());
        assertEquals(1_012L, plan.expiresAt());

        assertEquals(List.of(NpcPlanPhase.RESERVING, NpcPlanPhase.TRAVELLING,
                        NpcPlanPhase.ACTING, NpcPlanPhase.SETTLING),
                plan.steps().stream().map(NpcPlanStep::phase).toList());
        assertEquals(List.of(BlacksmithTaskIds.RESERVE_ORDER,
                        BlacksmithTaskIds.MOVE_TO_BLOCK,
                        BlacksmithTaskIds.FORGE,
                        BlacksmithTaskIds.SETTLE),
                plan.steps().stream().map(NpcPlanStep::executorId).toList());
        assertTrue(plan.steps().stream().allMatch(
                step -> step.status() == NpcPlanStepStatus.PENDING));
        for (NpcPlanStep step : plan.steps()) {
            assertEquals(order.id().toString(),
                    step.params().get(BlacksmithTaskIds.ORDER_ID));
            assertEquals(order.batchId().toString(),
                    step.params().get(BlacksmithTaskIds.BATCH_ID));
            assertEquals(Integer.toString(order.requiredWorkTicks()),
                    step.params().get(BlacksmithTaskIds.REQUIRED_WORK_TICKS));
        }
    }

    @Test
    void locationParametersPointToWorkstationThenTargetContainer() {
        UUID npcId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        ProductionOrder order = claimedOrder(workerId, 10L);
        NpcPlan plan = BlacksmithOrderPlanFactory.create(
                UUID.randomUUID(), npcId, workerId, order, 11L, 1_011L);

        assertFalse(plan.steps().get(0).params().containsKey(
                BlacksmithTaskIds.DIMENSION));
        assertLocation(plan.steps().get(1).params(), "minecraft:the_nether",
                new BlockPos(-8, 72, 15));
        assertLocation(plan.steps().get(2).params(), "minecraft:the_nether",
                new BlockPos(-8, 72, 15));
        assertEquals(Integer.toString(order.requiredWorkTicks()), plan.steps().get(2).params().get(
                BlacksmithTaskIds.REQUIRED_WORK_TICKS));
        assertLocation(plan.steps().get(3).params(), "minecraft:overworld",
                new BlockPos(20, 65, 8));
    }

    @Test
    void rejectsUnassignedWrongWorkerMissingWorkstationAndTerminalOrders() {
        UUID npcId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        ProductionOrder unassigned = openOrder(10L, null, null);
        assertThrows(IllegalArgumentException.class, () -> BlacksmithOrderPlanFactory.create(
                UUID.randomUUID(), npcId, workerId, unassigned, 11L, 100L));

        ProductionOrder assigned = claimedOrder(workerId, 10L);
        assertThrows(IllegalArgumentException.class, () -> BlacksmithOrderPlanFactory.create(
                UUID.randomUUID(), npcId, UUID.randomUUID(), assigned, 11L, 100L));

        ProductionOrder noWorkstation = openOrder(10L, null, null)
                .claim(workerId, 11L, "认领但未指定铁砧");
        assertThrows(IllegalArgumentException.class, () -> BlacksmithOrderPlanFactory.create(
                UUID.randomUUID(), npcId, workerId, noWorkstation, 12L, 100L));

        ProductionOrder cancelled = assigned.transition(
                ProductionOrderStatus.CANCELLED, 12L, "取消");
        assertThrows(IllegalArgumentException.class, () -> BlacksmithOrderPlanFactory.create(
                UUID.randomUUID(), npcId, workerId, cancelled, 13L, 100L));
    }

    @Test
    void validatesWorkAndExpiryBoundsWithoutOverflow() {
        UUID npcId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        ProductionOrder order = claimedOrder(workerId, 10L);

        assertThrows(IllegalArgumentException.class, () -> BlacksmithOrderPlanFactory.create(
                UUID.randomUUID(), npcId, workerId, order, 12L, 12L));
        assertThrows(IllegalArgumentException.class, () -> BlacksmithOrderPlanFactory.create(
                UUID.randomUUID(), npcId, workerId, order, 12L, 111L));
        assertThrows(IllegalArgumentException.class, () -> BlacksmithOrderPlanFactory.create(
                UUID.randomUUID(), npcId, workerId, order, 12L,
                12L + BlacksmithOrderPlanFactory.MAX_PLAN_LIFETIME_TICKS + 1L));
        assertThrows(IllegalArgumentException.class, () -> BlacksmithOrderPlanFactory.create(
                UUID.randomUUID(), npcId, workerId, order, 9L, 100L));

        NpcPlan upper = BlacksmithOrderPlanFactory.create(UUID.randomUUID(), npcId, workerId,
                order, 12L, 12L + BlacksmithOrderPlanFactory.MAX_PLAN_LIFETIME_TICKS);
        assertEquals(12L + BlacksmithOrderPlanFactory.MAX_PLAN_LIFETIME_TICKS,
                upper.expiresAt());
    }

    private static ProductionOrder claimedOrder(UUID npcId, long now) {
        return openOrder(now, null, null).claim(npcId, "minecraft:the_nether",
                new BlockPos(-8, 72, 15), now + 1L, "铁匠认领");
    }

    private static ProductionOrder openOrder(long now, String workstationDimension,
                                             BlockPos workstationPosition) {
        return ProductionOrder.open(UUID.randomUUID(),
                SubjectRef.institution(UUID.randomUUID()),
                SubjectRef.household(UUID.randomUUID()), "sagadyssey:iron_pickaxe",
                List.of(new ProductionMaterial(
                        new ItemFingerprint("minecraft:iron_ingot", 0), 3)),
                new ProductionMaterial(new ItemFingerprint("minecraft:iron_pickaxe", 0), 1),
                400, UUID.randomUUID(), workstationDimension, workstationPosition,
                UUID.randomUUID(), "minecraft:overworld", new BlockPos(20, 65, 8),
                now, "等待铁匠");
    }

    private static void assertLocation(Map<String, String> params,
                                       String dimension, BlockPos position) {
        assertEquals(dimension, params.get(BlacksmithTaskIds.DIMENSION));
        assertEquals(Integer.toString(position.getX()),
                params.get(BlacksmithTaskIds.X));
        assertEquals(Integer.toString(position.getY()),
                params.get(BlacksmithTaskIds.Y));
        assertEquals(Integer.toString(position.getZ()),
                params.get(BlacksmithTaskIds.Z));
    }
}
