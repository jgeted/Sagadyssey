package com.jgeted.sagadyssey.npc.brain.plan;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryNpcPlanStoreTest {
    private static final ResourceLocation PROVIDER = ResourceLocation.fromNamespaceAndPath(
            "sagadyssey", "store_test");
    private static final ResourceLocation EXECUTOR = ResourceLocation.fromNamespaceAndPath(
            "sagadyssey", "wait");

    @Test
    void refusesToSilentlyReplaceAnotherActivePlan() {
        InMemoryNpcPlanStore store = new InMemoryNpcPlanStore();
        UUID npcId = UUID.randomUUID();
        NpcPlan first = selected(UUID.randomUUID(), npcId, 10L);
        NpcPlan second = selected(UUID.randomUUID(), npcId, 11L);

        store.save(first);

        assertThrows(IllegalStateException.class, () -> store.save(second));
        assertEquals(first.id(), store.active(npcId).orElseThrow().id());
    }

    @Test
    void terminalSnapshotMovesToBoundedImmutableHistory() {
        InMemoryNpcPlanStore store = new InMemoryNpcPlanStore(2);
        UUID npcId = UUID.randomUUID();
        for (int index = 0; index < 3; index++) {
            NpcPlan plan = selected(UUID.randomUUID(), npcId, 10L + index * 10L);
            store.save(plan);
            store.save(plan.transition(NpcPlanStatus.CANCELLED,
                    11L + index * 10L, "test complete"));
        }

        List<NpcPlan> history = store.history(npcId);
        assertTrue(store.active(npcId).isEmpty());
        assertEquals(2, history.size());
        assertTrue(history.getFirst().createdAt() > history.getLast().createdAt());
        assertThrows(UnsupportedOperationException.class, history::clear);
    }

    @Test
    void newPlanCannotAppearHalfwayThroughLifecycle() {
        InMemoryNpcPlanStore store = new InMemoryNpcPlanStore();
        NpcPlan invalidStart = selected(UUID.randomUUID(), UUID.randomUUID(), 10L)
                .transition(NpcPlanStatus.RESERVING, 11L, "already reserving");

        assertThrows(IllegalStateException.class, () -> store.save(invalidStart));
    }

    @Test
    void activePlanCannotRewritePriorityOrStepIdentity() {
        InMemoryNpcPlanStore store = new InMemoryNpcPlanStore();
        UUID npcId = UUID.randomUUID();
        NpcPlan original = selected(UUID.randomUUID(), npcId, 10L);
        store.save(original);

        NpcPlan changedPriority = new NpcPlan(original.id(), npcId, original.candidateKey(),
                original.providerId(), original.kind(), NpcPlanPriority.CRITICAL,
                original.status(), original.steps(), original.currentStepIndex(),
                original.createdAt(), original.updatedAt(), original.commitmentUntil(),
                original.expiresAt(), null, original.explanation());
        assertThrows(IllegalStateException.class, () -> store.save(changedPriority));

        NpcPlanStep changedStep = new NpcPlanStep(0, NpcPlanPhase.ACTING,
                ResourceLocation.fromNamespaceAndPath("sagadyssey", "other"), Map.of(),
                NpcPlanStepStatus.PENDING, 11L, "changed");
        NpcPlan changedExecutor = new NpcPlan(original.id(), npcId, original.candidateKey(),
                original.providerId(), original.kind(), original.priority(), original.status(),
                List.of(changedStep), 0, original.createdAt(), 11L,
                original.commitmentUntil(), original.expiresAt(), null, "changed");
        assertThrows(IllegalStateException.class, () -> store.save(changedExecutor));
    }

    private static NpcPlan selected(UUID planId, UUID npcId, long now) {
        NpcPlanStep step = new NpcPlanStep(0, NpcPlanPhase.ACTING, EXECUTOR, Map.of(),
                NpcPlanStepStatus.PENDING, now, "");
        return new NpcPlan(planId, npcId, "ambient:wait:" + planId, PROVIDER,
                NpcPlanKind.AMBIENT, NpcPlanPriority.AMBIENT, NpcPlanStatus.SELECTED,
                List.of(step), 0, now, now, now + 5L, now + 20L, null, "selected");
    }
}
