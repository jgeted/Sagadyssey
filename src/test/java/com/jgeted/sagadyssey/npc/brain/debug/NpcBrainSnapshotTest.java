package com.jgeted.sagadyssey.npc.brain.debug;

import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanBlocker;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPriority;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStepStatus;
import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservation;
import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationKey;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcBrainSnapshotTest {
    private static final Identifier PROVIDER = Identifier.fromNamespaceAndPath(
            "sagadyssey", "debug_test");
    private static final Identifier EXECUTOR = Identifier.fromNamespaceAndPath(
            "sagadyssey", "debug_executor");

    @Test
    void captureFiltersAndSortsReadOnlyDiagnosticData() {
        UUID npcId = UUID.randomUUID();
        UUID otherNpcId = UUID.randomUUID();
        NpcPlan active = blockedPlan(npcId, 40L);
        NpcPlan oldHistory = succeededPlan(npcId, 20L, "work:old");
        NpcPlan newHistory = succeededPlan(npcId, 30L, "work:new");
        NpcPlan foreignHistory = succeededPlan(otherNpcId, 50L, "work:foreign");

        List<NpcPlan> mutableHistory = new ArrayList<>(List.of(
                oldHistory, foreignHistory, newHistory));
        List<NpcReservation> mutableReservations = new ArrayList<>(List.of(
                reservation("station", "forge", active.id(), npcId, 45L, 90L),
                reservation("station", "stale", UUID.randomUUID(), npcId, 45L, 90L),
                reservation("station", "foreign", active.id(), otherNpcId, 45L, 90L)));

        NpcBrainSnapshot snapshot = NpcBrainSnapshot.capture(npcId, NpcBehaviorMode.PLANNED,
                Optional.of(active), mutableHistory, mutableReservations);
        mutableHistory.clear();
        mutableReservations.clear();

        assertTrue(snapshot.hasActivePlan());
        assertEquals(NpcPlanStatus.BLOCKED, snapshot.activePlan().status());
        assertEquals(NpcPlanPhase.ACTING, snapshot.activePlan().phase());
        assertEquals(NpcPlanStepStatus.BLOCKED, snapshot.activePlan().currentStep().status());
        assertEquals("WORKSTATION_BUSY", snapshot.activePlan().blocker().code());
        assertEquals(List.of("work:new", "work:old"), snapshot.recentHistory().stream()
                .map(NpcBrainSnapshot.HistorySummary::candidateKey)
                .toList());
        assertEquals(List.of("forge", "stale"), snapshot.reservations().stream()
                .map(NpcBrainSnapshot.ReservationSummary::resourceId)
                .toList());
        assertTrue(snapshot.isActiveReservation(snapshot.reservations().getFirst()));
        assertFalse(snapshot.isActiveReservation(snapshot.reservations().getLast()));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.recentHistory().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.reservations().clear());
    }

    @Test
    void captureLimitsHistoryButKeepsReservationsWithoutActivePlan() {
        UUID npcId = UUID.randomUUID();
        List<NpcPlan> history = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            history.add(succeededPlan(npcId, 20L + index * 10L, "work:" + index));
        }
        NpcReservation stale = reservation("station", "stale", UUID.randomUUID(),
                npcId, 1L, 200L);

        NpcBrainSnapshot snapshot = NpcBrainSnapshot.capture(npcId, NpcBehaviorMode.LEGACY,
                Optional.empty(), history, List.of(stale));

        assertFalse(snapshot.hasActivePlan());
        assertEquals(NpcBrainSnapshot.HISTORY_LIMIT, snapshot.recentHistory().size());
        assertEquals("work:11", snapshot.recentHistory().getFirst().candidateKey());
        assertEquals(stale.planId(), snapshot.reservations().getFirst().planId());
        assertFalse(snapshot.isActiveReservation(snapshot.reservations().getFirst()));
    }

    private static NpcPlan blockedPlan(UUID npcId, long createdAt) {
        NpcPlan plan = plan(UUID.randomUUID(), npcId, createdAt, "work:blocked")
                .transition(NpcPlanStatus.ACTING, createdAt + 1L, "开始工作");
        NpcPlanStep running = plan.currentStep().transition(
                NpcPlanStepStatus.RUNNING, createdAt + 2L, "挥锤\n中");
        plan = plan.replaceCurrentStep(running);
        NpcPlanStep blocked = plan.currentStep().transition(
                NpcPlanStepStatus.BLOCKED, createdAt + 3L, "等待铁砧");
        plan = plan.replaceCurrentStep(blocked);
        NpcPlanBlocker blocker = new NpcPlanBlocker(
                "WORKSTATION_BUSY", "铁砧被占用", createdAt + 20L, "forge:main");
        return plan.transition(NpcPlanStatus.BLOCKED, createdAt + 3L, blocker, "等待资源");
    }

    private static NpcPlan succeededPlan(UUID npcId, long createdAt, String candidateKey) {
        NpcPlan plan = plan(UUID.randomUUID(), npcId, createdAt, candidateKey)
                .transition(NpcPlanStatus.ACTING, createdAt + 1L, "开始");
        NpcPlanStep running = plan.currentStep().transition(
                NpcPlanStepStatus.RUNNING, createdAt + 2L, "执行");
        plan = plan.replaceCurrentStep(running);
        NpcPlanStep succeeded = plan.currentStep().transition(
                NpcPlanStepStatus.SUCCEEDED, createdAt + 3L, "完成");
        return plan.replaceCurrentStep(succeeded)
                .transition(NpcPlanStatus.SUCCEEDED, createdAt + 4L, "结束");
    }

    private static NpcPlan plan(UUID planId, UUID npcId, long now, String candidateKey) {
        NpcPlanStep step = new NpcPlanStep(0, NpcPlanPhase.ACTING, EXECUTOR,
                Map.of("target", "forge"), NpcPlanStepStatus.PENDING, now, "");
        return new NpcPlan(planId, npcId, candidateKey, PROVIDER, NpcPlanKind.WORK,
                NpcPlanPriority.NORMAL, NpcPlanStatus.SELECTED, List.of(step), 0,
                now, now, now + 10L, now + 100L, null, "入选");
    }

    private static NpcReservation reservation(String type, String id, UUID planId,
                                               UUID npcId, long acquiredAt, long expiresAt) {
        return new NpcReservation(new NpcReservationKey(type, id), planId, npcId,
                acquiredAt, expiresAt);
    }
}
