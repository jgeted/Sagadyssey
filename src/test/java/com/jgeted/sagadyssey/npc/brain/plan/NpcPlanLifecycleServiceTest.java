package com.jgeted.sagadyssey.npc.brain.plan;

import com.jgeted.sagadyssey.npc.brain.candidate.NpcActivityCandidate;
import com.jgeted.sagadyssey.npc.brain.candidate.NpcPlanStepTemplate;
import com.jgeted.sagadyssey.npc.brain.context.NpcDecisionContext;
import com.jgeted.sagadyssey.npc.brain.context.NpcPerceptionSnapshot;
import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationKey;
import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationService;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcPlanLifecycleServiceTest {
    @Test
    void cancellingActivePlanClosesStepsAndReleasesEveryReservation() {
        UUID npcId = UUID.randomUUID();
        NpcDecisionContext context = new NpcDecisionContext(npcId, 10L, 4L,
                new NpcPerceptionSnapshot(10L, Set.of(), Set.of(), Set.of()),
                Set.of(), Map.of());
        NpcActivityCandidate candidate = new NpcActivityCandidate("blacksmith:order-7",
                id("provider"), NpcPlanKind.WORK, NpcPlanPriority.NORMAL, null,
                "forge:1", List.of(new NpcPlanStepTemplate(NpcPlanPhase.ACTING,
                id("executor"), Map.of())), 20.0D, 1.0D, 1.0D, 1.0D,
                false, 0L, 100L, 20L, Set.of(), Set.of(), Map.of());
        NpcPlan plan = NpcPlanFactory.create(UUID.randomUUID(), context, candidate,
                30L, 100L, "test");
        InMemoryNpcPlanStore store = new InMemoryNpcPlanStore();
        NpcReservationService reservations = new NpcReservationService();
        NpcPlanLifecycleService lifecycle = new NpcPlanLifecycleService(store, reservations);
        lifecycle.save(plan);
        reservations.acquire(new NpcReservationKey("workstation", "forge:1"),
                plan.id(), npcId, 10L, 100L);
        reservations.acquire(new NpcReservationKey("target", "order:7"),
                plan.id(), npcId, 10L, 100L);

        NpcPlanLifecycleResult result = lifecycle.cancelActive(npcId, 20L, "threat");

        assertEquals(NpcPlanStatus.CANCELLED, result.plan().status());
        assertEquals(2, result.releasedReservations().size());
        assertTrue(result.plan().steps().stream().allMatch(step -> step.status().terminal()));
        assertFalse(store.active(npcId).isPresent());
        assertEquals(1, store.history(npcId).size());
        assertTrue(reservations.snapshot().isEmpty());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("sagadyssey", path);
    }
}
