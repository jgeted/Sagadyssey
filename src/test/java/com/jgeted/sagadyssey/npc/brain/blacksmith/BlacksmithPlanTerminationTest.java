package com.jgeted.sagadyssey.npc.brain.blacksmith;

import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.brain.reservation.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BlacksmithPlanTerminationTest {
    @Test
    void expiryAndFailureCloseCommitmentsButRecoverableInterruptionDoesNot() {
        NpcPlan plan = plan(order(), 12L, 112L);
        NpcPlan expired = plan.transition(NpcPlanStatus.CANCELLED, 112L, "到期");
        NpcPlan interrupted = plan.transition(NpcPlanStatus.CANCELLED, 20L, "战斗或换班");
        NpcPlan failed = plan.transition(NpcPlanStatus.FAILED, 20L, "失败");

        assertTrue(BlacksmithPlanTermination.closesCommitment(update(expired)));
        assertTrue(BlacksmithPlanTermination.closesCommitment(
                new NpcPlanExecutionUpdate(NpcPlanExecutionUpdate.Outcome.FAILED, failed, "失败")));
        assertFalse(BlacksmithPlanTermination.closesCommitment(update(interrupted)));
        assertFalse(BlacksmithPlanTermination.closesCommitment(NpcPlanExecutionUpdate.idle("")));
    }

    @Test
    void oldExpiredHistoryIsSelectedRegardlessOfHistoryInputOrder() {
        NpcPlan expired = plan(order(), 12L, 112L).transition(NpcPlanStatus.CANCELLED, 112L, "旧版本到期");
        NpcPlan interruption = plan(order(), 12L, 112L).transition(NpcPlanStatus.CANCELLED, 20L, "中断");

        var result = BlacksmithPlanTermination.historyToClose(List.of(interruption, expired), Optional.empty());

        assertEquals(List.of(expired), result);
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test
    void activeReplacementPlanProtectsTheSameOrderFromLegacyExpiryCleanup() {
        ProductionOrder order = order();
        NpcPlan expired = plan(order, 12L, 112L).transition(NpcPlanStatus.CANCELLED, 112L, "到期");
        NpcPlan replacement = plan(order, 120L, 220L);

        assertTrue(BlacksmithPlanTermination.historyToClose(List.of(expired), Optional.of(replacement)).isEmpty());
    }

    @Test
    void laterRecoverableInterruptionAlsoProtectsTheSameOrder() {
        ProductionOrder order = order();
        NpcPlan expired = plan(order, 12L, 112L).transition(NpcPlanStatus.CANCELLED, 112L, "到期");
        NpcPlan newer = plan(order, 120L, 220L).transition(NpcPlanStatus.CANCELLED, 130L, "战斗");

        assertTrue(BlacksmithPlanTermination.historyToClose(List.of(expired, newer), Optional.empty()).isEmpty());
    }

    @Test
    void failedPlanRemainsEligibleForRetryingItsCommitmentClosure() {
        NpcPlan failed = plan(order(), 12L, 112L).transition(NpcPlanStatus.FAILED, 20L, "失败");

        assertEquals(List.of(failed), BlacksmithPlanTermination.historyToClose(List.of(failed), Optional.empty()));
    }

    @Test
    void realBlockedExecutionExpiryReleasesBothLedgersAndClosesTheMissingRequest() {
        ProductionOrder order = order();
        ProductionOrderData orders = new ProductionOrderData();
        // 持久账本从 OPEN 开始，随后按实际生命周期认领。
        ProductionOrder open = ProductionOrder.open(order.id(), order.issuer(), order.outputOwner(),
                order.recipeId(), order.inputs(), order.output(), order.requiredWorkTicks(),
                order.batchId(), order.workstationDimension(), order.workstationPosition(),
                order.targetContainerId(), order.targetContainerDimension(), order.targetContainerPosition(),
                10L, "");
        orders.put(open);
        orders.put(order);
        orders.put(order.block(new ProductionOrderBlocker("MISSING_INPUT", "缺煤", 1000L), 13L, "缺煤"));
        ProductionMaterialRequestData requests = new ProductionMaterialRequestData();
        requests.openOrGet(order.id(), order.targetContainerId(), order.inputs().getFirst(), 13L, "缺煤");
        RightsStateData rights = new RightsStateData();
        var input = order.inputs().getFirst();
        assertTrue(rights.reserve(new AssetReservation(UUID.randomUUID(), order.targetContainerId(),
                input.fingerprint(), input.quantity(), order.outputOwner(), "production:" + order.batchId(),
                "旧预留", 12L, 1000L), 3, 12L));
        InMemoryNpcPlanStore store = new InMemoryNpcPlanStore();
        NpcReservationService reservations = new NpcReservationService();
        NpcPlan plan = plan(order, 12L, 112L);
        store.save(plan);
        reservations.acquire(new NpcReservationKey("workstation", "forge"), plan.id(), plan.npcId(), 12L, 1000L);
        NpcTaskExecutorRegistry registry = new NpcTaskExecutorRegistry();
        registry.register(new NpcTaskExecutor() {
            public net.minecraft.resources.Identifier id() { return BlacksmithTaskIds.RESERVE_ORDER; }
            public TaskTickResult start(NpcExecutionContext context, NpcPlan current, NpcPlanStep step) {
                return TaskTickResult.blocked("缺煤", new NpcPlanBlocker("MISSING_INPUT", "缺煤", 1000L, order.id().toString()));
            }
            public TaskTickResult tick(NpcExecutionContext context, NpcPlan current, NpcPlanStep step) {
                return start(context, current, step);
            }
        });
        NpcPlanExecutionService execution = new NpcPlanExecutionService(store,
                new NpcPlanLifecycleService(store, reservations), registry);
        assertEquals(NpcPlanExecutionUpdate.Outcome.WAITING, execution.tick(context(plan.npcId(), 13L)).outcome());

        NpcPlanExecutionUpdate expired = execution.tick(context(plan.npcId(), 112L));
        assertTrue(BlacksmithPlanTermination.closesCommitment(expired));
        ProductionCommitmentService.close(order.id(), orders, new ProductionBatchData(), requests, rights, 112L, expired.detail());

        assertTrue(store.active(plan.npcId()).isEmpty());
        assertTrue(reservations.snapshot().isEmpty());
        assertTrue(rights.reservations().isEmpty());
        assertTrue(requests.openRequests().isEmpty());
        assertEquals(ProductionOrderStatus.CANCELLED, orders.byId(order.id()).orElseThrow().status());
    }

    private static NpcPlanExecutionUpdate update(NpcPlan plan) {
        return new NpcPlanExecutionUpdate(NpcPlanExecutionUpdate.Outcome.CANCELLED, plan, "取消");
    }

    private static NpcExecutionContext context(UUID npcId, long gameTime) {
        return new NpcExecutionContext() {
            public UUID npcId() { return npcId; }
            public long gameTime() { return gameTime; }
        };
    }

    private static NpcPlan plan(ProductionOrder order, long now, long expiry) {
        return BlacksmithOrderPlanFactory.create(UUID.randomUUID(), order.assignedWorkerId(),
                order.assignedWorkerId(), order, now, expiry);
    }

    private static ProductionOrder order() {
        UUID worker = UUID.randomUUID();
        SubjectRef owner = SubjectRef.character(worker);
        return ProductionOrder.open(UUID.randomUUID(), owner, owner, "sagadyssey:test_iron_pickaxe",
                List.of(new ProductionMaterial(new ItemFingerprint("minecraft:coal", 0), 1)),
                new ProductionMaterial(new ItemFingerprint("minecraft:iron_pickaxe", 0), 1),
                80, UUID.randomUUID(), "minecraft:overworld", new BlockPos(2, 64, 0),
                UUID.randomUUID(), "minecraft:overworld", new BlockPos(4, 64, 0), 10L, "")
                .claim(worker, 11L, "认领");
    }
}
