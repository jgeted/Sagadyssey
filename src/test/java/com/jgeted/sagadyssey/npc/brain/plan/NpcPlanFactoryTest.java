package com.jgeted.sagadyssey.npc.brain.plan;

import com.jgeted.sagadyssey.npc.brain.candidate.NpcActivityCandidate;
import com.jgeted.sagadyssey.npc.brain.candidate.NpcPlanStepTemplate;
import com.jgeted.sagadyssey.npc.brain.context.NpcDecisionContext;
import com.jgeted.sagadyssey.npc.brain.context.NpcPerceptionSnapshot;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NpcPlanFactoryTest {
    @Test
    void preservesExplicitMultiStepWorkflow() {
        NpcDecisionContext context = context(Set.of("has_order"));
        NpcActivityCandidate candidate = candidate(Set.of("has_order"), Set.of());

        NpcPlan plan = NpcPlanFactory.create(UUID.randomUUID(), context, candidate,
                40L, 100L, "smith order");

        assertEquals(List.of(NpcPlanPhase.RESERVING, NpcPlanPhase.TRAVELLING,
                        NpcPlanPhase.ACTING, NpcPlanPhase.SETTLING),
                plan.steps().stream().map(NpcPlanStep::phase).toList());
        assertEquals(List.of(0, 1, 2, 3),
                plan.steps().stream().map(NpcPlanStep::index).toList());
    }

    @Test
    void refusesToCreatePlanFromBlockedOrUnsatisfiedCandidate() {
        NpcDecisionContext context = context(Set.of());
        assertThrows(IllegalArgumentException.class,
                () -> NpcPlanFactory.create(UUID.randomUUID(), context,
                        candidate(Set.of("has_order"), Set.of()), 40L, 100L, "missing"));
        assertThrows(IllegalArgumentException.class,
                () -> NpcPlanFactory.create(UUID.randomUUID(), context,
                        candidate(Set.of(), Set.of("forge_busy")), 40L, 100L, "blocked"));
    }

    private static NpcActivityCandidate candidate(Set<String> prerequisites,
                                                   Set<String> blockers) {
        return new NpcActivityCandidate("blacksmith:order-3", id("provider"),
                NpcPlanKind.WORK, NpcPlanPriority.NORMAL, null, "forge:main",
                List.of(
                        template(NpcPlanPhase.RESERVING, "reserve"),
                        template(NpcPlanPhase.TRAVELLING, "travel"),
                        template(NpcPlanPhase.ACTING, "smith"),
                        template(NpcPlanPhase.SETTLING, "settle")),
                30.0D, 2.0D, 3.0D, 1.0D, false, 0L, 100L, 40L,
                prerequisites, blockers, Map.of("order", "3"));
    }

    private static NpcPlanStepTemplate template(NpcPlanPhase phase, String executor) {
        return new NpcPlanStepTemplate(phase, id(executor), Map.of());
    }

    private static NpcDecisionContext context(Set<String> prerequisites) {
        return new NpcDecisionContext(UUID.randomUUID(), 10L, 1L,
                new NpcPerceptionSnapshot(10L, Set.of(), Set.of(), Set.of()),
                prerequisites, Map.of());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("sagadyssey", path);
    }
}
