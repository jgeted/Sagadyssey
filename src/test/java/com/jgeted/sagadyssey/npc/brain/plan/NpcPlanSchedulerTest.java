package com.jgeted.sagadyssey.npc.brain.plan;

import com.jgeted.sagadyssey.npc.brain.candidate.CandidateRejection;
import com.jgeted.sagadyssey.npc.brain.candidate.NpcActivityCandidate;
import com.jgeted.sagadyssey.npc.brain.candidate.NpcPlanStepTemplate;
import com.jgeted.sagadyssey.npc.brain.context.NpcDecisionContext;
import com.jgeted.sagadyssey.npc.brain.context.NpcPerceptionSnapshot;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcPlanSchedulerTest {
    private final NpcPlanScheduler scheduler = new NpcPlanScheduler();

    @Test
    void filtersCandidatesWithStructuredReasons() {
        NpcDecisionContext context = context(UUID.randomUUID(), 100L, Set.of("has_hammer"));
        List<NpcActivityCandidate> candidates = List.of(
                candidate("blocked", NpcPlanPriority.NORMAL, false, 0L, 200L,
                        Set.of(), Set.of("missing_coal"), 100.0D),
                candidate("early", NpcPlanPriority.NORMAL, false, 120L, 200L,
                        Set.of(), Set.of(), 100.0D),
                candidate("expired", NpcPlanPriority.NORMAL, false, 0L, 90L,
                        Set.of(), Set.of(), 100.0D),
                candidate("missing", NpcPlanPriority.NORMAL, false, 0L, 200L,
                        Set.of("has_coal"), Set.of(), 100.0D));

        NpcSchedulingDecision result = scheduler.select(context, candidates, null);

        assertEquals(NpcSchedulingDecision.Action.NONE, result.action());
        assertEquals(Set.of(CandidateRejection.Code.HARD_BLOCKER,
                        CandidateRejection.Code.TOO_EARLY,
                        CandidateRejection.Code.EXPIRED,
                        CandidateRejection.Code.MISSING_PREREQUISITE),
                result.rejections().stream().map(CandidateRejection::code)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void selectionIsDeterministicAndIndependentOfInputOrder() {
        NpcDecisionContext context = context(UUID.randomUUID(), 100L, Set.of());
        NpcActivityCandidate lower = candidate("lower", NpcPlanPriority.NORMAL,
                false, 0L, 200L, Set.of(), Set.of(), 15.0D);
        NpcActivityCandidate higher = candidate("higher", NpcPlanPriority.NORMAL,
                false, 0L, 200L, Set.of(), Set.of(), 30.0D);

        NpcSchedulingDecision first = scheduler.select(context, List.of(lower, higher), null);
        NpcSchedulingDecision second = scheduler.select(context, List.of(higher, lower), null);

        assertEquals(NpcSchedulingDecision.Action.START, first.action());
        assertEquals("higher", first.selectedCandidate().candidateKey());
        assertEquals(first.selectedCandidate().candidateKey(),
                second.selectedCandidate().candidateKey());
        assertEquals(first.eligibleScores(), second.eligibleScores());
    }

    @Test
    void commitmentLockKeepsCurrentPlanUntilHigherUrgentCandidateArrives() {
        UUID npcId = UUID.randomUUID();
        NpcDecisionContext start = context(npcId, 100L, Set.of());
        NpcActivityCandidate work = candidate("work:order-1", NpcPlanPriority.NORMAL,
                false, 0L, 1_000L, Set.of(), Set.of(), 20.0D);
        NpcPlan current = NpcPlanFactory.create(UUID.randomUUID(), start, work,
                300L, 1_000L, "current");
        NpcDecisionContext later = context(npcId, 150L, Set.of());
        NpcActivityCandidate social = candidate("social:visit", NpcPlanPriority.NORMAL,
                false, 0L, 1_000L, Set.of(), Set.of(), 100.0D);

        NpcSchedulingDecision kept = scheduler.select(later, List.of(work, social), current);

        assertEquals(NpcSchedulingDecision.Action.KEEP, kept.action());
        assertTrue(kept.rejections().stream().anyMatch(value ->
                value.candidateKey().equals("social:visit")
                        && value.code() == CandidateRejection.Code.COMMITMENT_LOCKED));

        NpcActivityCandidate danger = candidate("emergency:flee", NpcPlanPriority.CRITICAL,
                true, 0L, 1_000L, Set.of(), Set.of(), 1.0D);
        NpcSchedulingDecision replaced = scheduler.select(later,
                List.of(work, social, danger), current);

        assertEquals(NpcSchedulingDecision.Action.REPLACE, replaced.action());
        assertEquals("emergency:flee", replaced.selectedCandidate().candidateKey());
    }

    @Test
    void rejectsDuplicateCandidateKeysAndForeignCurrentPlan() {
        NpcDecisionContext context = context(UUID.randomUUID(), 100L, Set.of());
        NpcActivityCandidate duplicate = candidate("same", NpcPlanPriority.NORMAL,
                false, 0L, 200L, Set.of(), Set.of(), 1.0D);
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.select(context, List.of(duplicate, duplicate), null));

        NpcDecisionContext other = context(UUID.randomUUID(), 100L, Set.of());
        NpcPlan foreign = NpcPlanFactory.create(UUID.randomUUID(), other, duplicate,
                110L, 200L, "foreign");
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.select(context, List.of(duplicate), foreign));
    }

    @Test
    void expiredCurrentPlanMustBeCancelledOrReplacedBeforeStartingAnother() {
        UUID npcId = UUID.randomUUID();
        NpcDecisionContext initial = context(npcId, 10L, Set.of());
        NpcActivityCandidate oldCandidate = candidate("old", NpcPlanPriority.NORMAL,
                false, 0L, 100L, Set.of(), Set.of(), 1.0D);
        NpcPlan expired = NpcPlanFactory.create(UUID.randomUUID(), initial, oldCandidate,
                15L, 20L, "old");
        NpcDecisionContext later = context(npcId, 30L, Set.of());

        assertEquals(NpcSchedulingDecision.Action.CANCEL,
                scheduler.select(later, List.of(), expired).action());

        NpcActivityCandidate replacement = candidate("new", NpcPlanPriority.NORMAL,
                false, 0L, 100L, Set.of(), Set.of(), 1.0D);
        NpcSchedulingDecision decision = scheduler.select(later,
                List.of(replacement), expired);
        assertEquals(NpcSchedulingDecision.Action.REPLACE, decision.action());
        assertEquals("new", decision.selectedCandidate().candidateKey());
    }

    private static NpcDecisionContext context(UUID npcId, long time,
                                              Set<String> prerequisites) {
        return new NpcDecisionContext(npcId, time, 42L,
                new NpcPerceptionSnapshot(time, Set.of(), Set.of(), Set.of()),
                prerequisites, Map.of());
    }

    private static NpcActivityCandidate candidate(String key, NpcPlanPriority priority,
                                                   boolean urgent, long earliest, long latest,
                                                   Set<String> prerequisites,
                                                   Set<String> blockers, double benefit) {
        return new NpcActivityCandidate(key, id("provider"), NpcPlanKind.WORK, priority,
                null, null, List.of(new NpcPlanStepTemplate(NpcPlanPhase.ACTING,
                id("executor"), Map.of())), benefit, 0.0D, 0.0D, 0.0D,
                urgent, earliest, latest, 20L, prerequisites, blockers, Map.of());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("sagadyssey", path);
    }
}
