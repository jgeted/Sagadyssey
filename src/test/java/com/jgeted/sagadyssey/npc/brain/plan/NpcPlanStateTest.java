package com.jgeted.sagadyssey.npc.brain.plan;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NpcPlanStateTest {
    private static final Identifier PROVIDER = Identifier.fromNamespaceAndPath(
            "sagadyssey", "test_provider");
    private static final Identifier EXECUTOR = Identifier.fromNamespaceAndPath(
            "sagadyssey", "test_executor");

    @Test
    void priorityRankHasStableStrictOrdering() {
        assertTrue(NpcPlanPriority.CRITICAL.isHigherThan(NpcPlanPriority.HIGH));
        assertTrue(NpcPlanPriority.NORMAL.isHigherThan(NpcPlanPriority.LOW));
        assertFalse(NpcPlanPriority.LOW.isHigherThan(NpcPlanPriority.LOW));
        assertEquals(0, NpcPlanPriority.AMBIENT.rank());
    }

    @Test
    void planStatusOnlyAllowsDeclaredLifecycleEdges() {
        assertTrue(NpcPlanStatus.SELECTED.canTransitionTo(NpcPlanStatus.RESERVING));
        assertTrue(NpcPlanStatus.SELECTED.canTransitionTo(NpcPlanStatus.ACTING));
        assertTrue(NpcPlanStatus.RESERVING.canTransitionTo(NpcPlanStatus.BLOCKED));
        assertTrue(NpcPlanStatus.BLOCKED.canTransitionTo(NpcPlanStatus.WAITING));
        assertTrue(NpcPlanStatus.WAITING.canTransitionTo(NpcPlanStatus.BLOCKED));
        assertTrue(NpcPlanStatus.WAITING.canTransitionTo(NpcPlanStatus.RESERVING));
        assertTrue(NpcPlanStatus.WAITING.canTransitionTo(NpcPlanStatus.TRAVELLING));
        assertTrue(NpcPlanStatus.WAITING.canTransitionTo(NpcPlanStatus.ACTING));
        assertTrue(NpcPlanStatus.ACTING.canTransitionTo(NpcPlanStatus.CANCELLED));
        assertTrue(NpcPlanStatus.SUCCEEDED.terminal());
        assertFalse(NpcPlanStatus.SUCCEEDED.canTransitionTo(NpcPlanStatus.SELECTED));
    }

    @Test
    void stepDefensivelyCopiesParametersAndRejectsTerminalRestart() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("target", "forge");
        NpcPlanStep step = new NpcPlanStep(0, NpcPlanPhase.ACTING, EXECUTOR, mutable,
                NpcPlanStepStatus.PENDING, 10L, "created");
        mutable.put("target", "changed");

        assertEquals("forge", step.params().get("target"));
        assertThrows(UnsupportedOperationException.class,
                () -> step.params().put("extra", "value"));
        NpcPlanStep finished = step.transition(NpcPlanStepStatus.RUNNING, 11L, "started")
                .transition(NpcPlanStepStatus.SUCCEEDED, 12L, "done");
        assertThrows(IllegalStateException.class,
                () -> finished.transition(NpcPlanStepStatus.RUNNING, 13L, "restart"));
    }

    @Test
    void planRequiresBlockerAndExplicitlyClearsItBeforeResume() {
        NpcPlan plan = plan(UUID.randomUUID(), UUID.randomUUID(), 10L, 1);
        plan = plan.transition(NpcPlanStatus.RESERVING, 11L, "reserve");
        assertThrows(IllegalArgumentException.class,
                () -> plan(UUID.randomUUID(), UUID.randomUUID(), 10L, 1)
                        .transition(NpcPlanStatus.RESERVING, 11L, "reserve")
                        .transition(NpcPlanStatus.BLOCKED, 12L, "missing"));

        NpcPlanBlocker blocker = new NpcPlanBlocker(
                "WORKSTATION_BUSY", "forge held", 30L, "forge:main");
        NpcPlan blocked = plan.transition(NpcPlanStatus.BLOCKED, 12L, blocker, "blocked");
        NpcPlan waiting = blocked.transition(NpcPlanStatus.WAITING, 13L, blocker, "waiting");
        NpcPlan resumed = waiting.transition(NpcPlanStatus.RESERVING, 30L, null, "retry");

        assertSame(blocker, waiting.blocker());
        assertNull(resumed.blocker());
        assertDoesNotThrow(() -> resumed.transition(NpcPlanStatus.ACTING, 31L,
                "计划没有旅行步骤，直接恢复行动"));
    }

    @Test
    void successfulPlanRequiresSuccessfulStepsAndCannotTransitionAgain() {
        NpcPlan plan = plan(UUID.randomUUID(), UUID.randomUUID(), 10L, 1)
                .transition(NpcPlanStatus.RESERVING, 11L, "reserve")
                .transition(NpcPlanStatus.TRAVELLING, 12L, "travel")
                .transition(NpcPlanStatus.ACTING, 13L, "act");
        NpcPlan unfinished = plan;
        assertThrows(IllegalStateException.class,
                () -> unfinished.transition(NpcPlanStatus.SETTLING, 14L, "settle")
                        .transition(NpcPlanStatus.SUCCEEDED, 15L, "too early"));

        NpcPlanStep running = plan.currentStep().transition(
                NpcPlanStepStatus.RUNNING, 14L, "running");
        plan = plan.replaceCurrentStep(running);
        NpcPlanStep finished = plan.currentStep().transition(
                NpcPlanStepStatus.SUCCEEDED, 15L, "done");
        plan = plan.replaceCurrentStep(finished)
                .transition(NpcPlanStatus.SETTLING, 16L, "settle")
                .transition(NpcPlanStatus.SUCCEEDED, 17L, "complete");

        assertTrue(plan.terminal());
        NpcPlan completed = plan;
        assertThrows(IllegalStateException.class,
                () -> completed.transition(NpcPlanStatus.CANCELLED, 18L, "late cancel"));
    }

    @Test
    void cancellationClosesEveryUnfinishedStep() {
        NpcPlan cancelled = plan(UUID.randomUUID(), UUID.randomUUID(), 10L, 3)
                .transition(NpcPlanStatus.CANCELLED, 11L, "preempted");

        assertTrue(cancelled.terminal());
        assertTrue(cancelled.steps().stream()
                .allMatch(step -> step.status() == NpcPlanStepStatus.CANCELLED));
        assertThrows(UnsupportedOperationException.class,
                () -> cancelled.steps().add(cancelled.currentStep()));
    }

    private static NpcPlan plan(UUID planId, UUID npcId, long now, int stepCount) {
        List<NpcPlanStep> steps = new ArrayList<>();
        for (int index = 0; index < stepCount; index++) {
            steps.add(new NpcPlanStep(index, phase(index, stepCount), EXECUTOR,
                    Map.of("index", String.valueOf(index)),
                    NpcPlanStepStatus.PENDING, now, ""));
        }
        return new NpcPlan(planId, npcId, "work:test:" + planId, PROVIDER,
                NpcPlanKind.WORK, NpcPlanPriority.NORMAL, NpcPlanStatus.SELECTED,
                steps, 0, now, now, now + 20L, now + 100L, null, "selected");
    }

    private static NpcPlanPhase phase(int index, int count) {
        if (count == 1) return NpcPlanPhase.ACTING;
        if (index == 0) return NpcPlanPhase.RESERVING;
        if (index == count - 1) return NpcPlanPhase.SETTLING;
        return NpcPlanPhase.ACTING;
    }
}
