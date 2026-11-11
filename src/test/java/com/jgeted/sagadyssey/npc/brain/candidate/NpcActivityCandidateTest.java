package com.jgeted.sagadyssey.npc.brain.candidate;

import com.jgeted.sagadyssey.npc.brain.context.NpcDecisionContext;
import com.jgeted.sagadyssey.npc.brain.context.NpcPerceptionSnapshot;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPriority;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NpcActivityCandidateTest {
    private static final Identifier PROVIDER =
            Identifier.fromNamespaceAndPath("sagadyssey", "profession");
    private static final Identifier EXECUTOR =
            Identifier.fromNamespaceAndPath("sagadyssey", "smithing");

    @Test
    void constructorDefensivelyCopiesCollections() {
        Set<String> prerequisites = new HashSet<>(Set.of("has_hammer"));
        Set<String> blockers = new HashSet<>(Set.of("forge_reserved"));
        Map<String, String> parameters = new HashMap<>(Map.of("order", "17"));

        NpcActivityCandidate candidate = candidate(
                8.0D, 1.0D, 2.0D, 0.5D, 10L, 20L, 40L,
                prerequisites, blockers, parameters);
        prerequisites.clear();
        blockers.clear();
        parameters.clear();

        assertEquals(Set.of("has_hammer"), candidate.prerequisites());
        assertEquals(Set.of("forge_reserved"), candidate.hardBlockers());
        assertEquals(Map.of("order", "17"), candidate.metadata());
        assertEquals(Map.of("order", "17"), candidate.steps().getFirst().parameters());
        assertThrows(UnsupportedOperationException.class,
                () -> candidate.prerequisites().add("has_fuel"));
    }

    @Test
    void baseUtilityCombinesKindModifierAndAllCosts() {
        NpcActivityCandidate candidate = candidate(
                12.0D, 1.5D, 2.0D, 0.5D, 10L, 20L, 40L,
                Set.of(), Set.of(), Map.of());
        String kindKey = candidate.kind().name().toLowerCase(java.util.Locale.ROOT);
        NpcDecisionContext context = new NpcDecisionContext(
                UUID.randomUUID(), 12L, 99L,
                new NpcPerceptionSnapshot(12L, Set.of(), Set.of(), Set.of()),
                Set.of(), Map.of(kindKey, 4.0D));

        assertEquals(12.0D, candidate.baseUtility(context), 0.000001D);
    }

    @Test
    void negativeCostsAreRejectedInsteadOfIncreasingUtility() {
        assertThrows(IllegalArgumentException.class, () -> candidate(
                6.0D, -1.0D, 0.0D, 0.0D, 10L, 20L, 40L,
                Set.of(), Set.of(), Map.of()));
    }

    @Test
    void constructorRejectsBlankKeyInvalidTimesAndInvalidNumbers() {
        assertThrows(IllegalArgumentException.class,
                () -> create(" ", 1.0D, 0.0D, 0.0D, 0.0D, 0L, 1L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> create("test", 1.0D, 0.0D, 0.0D, 0.0D, 2L, 1L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> create("test", 1.0D, 0.0D, 0.0D, 0.0D, 0L, 1L, -1L));
        assertThrows(IllegalArgumentException.class,
                () -> create("test", Double.NaN, 0.0D, 0.0D, 0.0D, 0L, 1L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> create("test", 1.0D, 0.0D, Double.POSITIVE_INFINITY, 0.0D,
                        0L, 1L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> create("test", 1.0D, 0.0D, 0.0D, 0.0D, -1L, 1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new NpcActivityCandidate(
                "backwards", PROVIDER, NpcPlanKind.WORK, NpcPlanPriority.NORMAL,
                null, null, List.of(
                new NpcPlanStepTemplate(NpcPlanPhase.ACTING, EXECUTOR, Map.of()),
                new NpcPlanStepTemplate(NpcPlanPhase.RESERVING, EXECUTOR, Map.of())),
                1.0D, 0.0D, 0.0D, 0.0D, false, 0L, 10L, 1L,
                Set.of(), Set.of(), Map.of()));
    }

    private static NpcActivityCandidate candidate(
            double benefit,
            double risk,
            double effort,
            double opportunity,
            long earliest,
            long latest,
            long duration,
            Set<String> prerequisites,
            Set<String> blockers,
            Map<String, String> parameters
    ) {
        return new NpcActivityCandidate(
                "smith:order:17", PROVIDER, firstKind(), firstPriority(), null, "forge:main",
                List.of(new NpcPlanStepTemplate(NpcPlanPhase.ACTING, EXECUTOR, parameters)),
                benefit, risk, effort, opportunity, false, earliest, latest, duration,
                prerequisites, blockers, parameters);
    }

    private static NpcActivityCandidate create(
            String key,
            double benefit,
            double risk,
            double effort,
            double opportunity,
            long earliest,
            long latest,
            long duration
    ) {
        return new NpcActivityCandidate(
                key, PROVIDER, firstKind(), firstPriority(), null, null,
                List.of(new NpcPlanStepTemplate(NpcPlanPhase.ACTING, EXECUTOR, Map.of())),
                benefit, risk, effort, opportunity, false, earliest, latest, duration,
                Set.of(), Set.of(), Map.of());
    }

    private static NpcPlanKind firstKind() {
        return NpcPlanKind.values()[0];
    }

    private static NpcPlanPriority firstPriority() {
        return NpcPlanPriority.values()[0];
    }
}
