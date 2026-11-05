package com.jgeted.sagadyssey.npc.brain.plan;

import com.jgeted.sagadyssey.npc.brain.candidate.CandidateRejection;
import com.jgeted.sagadyssey.npc.brain.candidate.NpcActivityCandidate;
import com.jgeted.sagadyssey.npc.brain.context.NpcDecisionContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * 对不可变候选进行过滤、稳定评分和承诺锁判定，不直接改变计划仓库或世界。
 */
public final class NpcPlanScheduler {
    private static final double PRIORITY_WEIGHT = 1_000.0D;
    private static final double URGENT_BONUS = 250.0D;
    private static final double CONTINUITY_BONUS = 25.0D;
    private static final double NOISE_RANGE = 1.0D;

    public NpcSchedulingDecision select(NpcDecisionContext context,
                                        Collection<NpcActivityCandidate> candidates,
                                        @Nullable NpcPlan currentPlan) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(candidates, "candidates");
        validateCurrentPlan(context, currentPlan);

        List<NpcActivityCandidate> ordered = candidates.stream()
                .map(candidate -> Objects.requireNonNull(candidate, "candidate"))
                .sorted(Comparator.comparing(NpcActivityCandidate::candidateKey))
                .toList();
        requireUniqueKeys(ordered);

        List<CandidateRejection> rejections = new ArrayList<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        List<ScoredCandidate> eligible = new ArrayList<>();
        for (NpcActivityCandidate candidate : ordered) {
            CandidateRejection rejection = reject(context, candidate);
            if (rejection != null) {
                rejections.add(rejection);
                continue;
            }
            double score = score(context, candidate, currentPlan);
            scores.put(candidate.candidateKey(), score);
            eligible.add(new ScoredCandidate(candidate, score));
        }

        boolean currentExists = currentPlan != null && !currentPlan.terminal();
        boolean currentActive = currentExists && !expired(currentPlan, context.gameTime());
        boolean commitmentLocked = currentActive
                && context.gameTime() < currentPlan.commitmentUntil();
        if (commitmentLocked) {
            List<ScoredCandidate> preemptors = eligible.stream()
                    .filter(value -> canPreemptLocked(currentPlan, value.candidate()))
                    .toList();
            if (preemptors.isEmpty()) {
                rejectForCommitment(eligible, currentPlan, rejections);
                return new NpcSchedulingDecision(NpcSchedulingDecision.Action.KEEP, null,
                        scores, rejections, "当前具体计划仍处于承诺期，没有合法紧急抢占候选");
            }
            ScoredCandidate selected = best(preemptors);
            rejectForCommitment(eligible.stream()
                    .filter(value -> !preemptors.contains(value)).toList(), currentPlan, rejections);
            return new NpcSchedulingDecision(NpcSchedulingDecision.Action.REPLACE,
                    selected.candidate(), scores, rejections,
                    "更高优先级的紧急候选合法抢占当前计划");
        }

        if (eligible.isEmpty()) {
            if (currentActive) {
                return new NpcSchedulingDecision(NpcSchedulingDecision.Action.KEEP, null,
                        scores, rejections, "没有新的可行候选，继续当前计划");
            }
            if (currentExists) {
                return new NpcSchedulingDecision(NpcSchedulingDecision.Action.CANCEL, null,
                        scores, rejections, "当前计划已经过期，且没有可替代候选");
            }
            return new NpcSchedulingDecision(NpcSchedulingDecision.Action.NONE, null,
                    scores, rejections, "没有可行候选，也没有可继续的当前计划");
        }

        ScoredCandidate selected = best(eligible);
        if (!currentExists) {
            return new NpcSchedulingDecision(NpcSchedulingDecision.Action.START,
                    selected.candidate(), scores, rejections, "选择当前最高优先级与效用候选");
        }
        if (!currentActive) {
            return new NpcSchedulingDecision(NpcSchedulingDecision.Action.REPLACE,
                    selected.candidate(), scores, rejections, "当前计划已经过期，必须先终结再替换");
        }
        if (currentPlan.candidateKey().equals(selected.candidate().candidateKey())) {
            return new NpcSchedulingDecision(NpcSchedulingDecision.Action.KEEP, null,
                    scores, rejections, "当前计划仍是最优候选，保持连续性");
        }
        return new NpcSchedulingDecision(NpcSchedulingDecision.Action.REPLACE,
                selected.candidate(), scores, rejections, "承诺期已结束，替换为更合适的候选");
    }

    private static void validateCurrentPlan(NpcDecisionContext context,
                                            @Nullable NpcPlan currentPlan) {
        if (currentPlan != null && !currentPlan.npcId().equals(context.npcId())) {
            throw new IllegalArgumentException("current plan belongs to another NPC");
        }
    }

    private static void requireUniqueKeys(List<NpcActivityCandidate> candidates) {
        Set<String> seen = new java.util.HashSet<>();
        for (NpcActivityCandidate candidate : candidates) {
            if (!seen.add(candidate.candidateKey())) {
                throw new IllegalArgumentException("duplicate candidate key: "
                        + candidate.candidateKey());
            }
        }
    }

    @Nullable
    private static CandidateRejection reject(NpcDecisionContext context,
                                             NpcActivityCandidate candidate) {
        if (!candidate.hardBlockers().isEmpty()) {
            return rejection(candidate, CandidateRejection.Code.HARD_BLOCKER,
                    "硬性阻塞=" + String.join(",", candidate.hardBlockers().stream().sorted().toList()));
        }
        if (context.gameTime() < candidate.earliestStart()) {
            return rejection(candidate, CandidateRejection.Code.TOO_EARLY,
                    "最早开始时间=" + candidate.earliestStart());
        }
        if (context.gameTime() > candidate.latestStart()) {
            return rejection(candidate, CandidateRejection.Code.EXPIRED,
                    "最迟开始时间=" + candidate.latestStart());
        }
        List<String> missing = candidate.prerequisites().stream()
                .filter(value -> !context.satisfiedPrerequisites().contains(value))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            return rejection(candidate, CandidateRejection.Code.MISSING_PREREQUISITE,
                    "缺少前置条件=" + String.join(",", missing));
        }
        return null;
    }

    private static CandidateRejection rejection(NpcActivityCandidate candidate,
                                                 CandidateRejection.Code code,
                                                 String detail) {
        return new CandidateRejection(candidate.candidateKey(), code, detail);
    }

    private static double score(NpcDecisionContext context, NpcActivityCandidate candidate,
                                @Nullable NpcPlan currentPlan) {
        double continuity = currentPlan != null
                && currentPlan.candidateKey().equals(candidate.candidateKey())
                ? CONTINUITY_BONUS : 0.0D;
        double urgent = candidate.urgent() ? URGENT_BONUS : 0.0D;
        return candidate.priority().rank() * PRIORITY_WEIGHT
                + candidate.baseUtility(context) + urgent + continuity
                + deterministicNoise(context.randomSeed(), candidate.candidateKey());
    }

    private static double deterministicNoise(long seed, String candidateKey) {
        long mixed = seed ^ ((long) candidateKey.hashCode() << 32)
                ^ candidateKey.length() * 0x9E3779B97F4A7C15L;
        return new SplittableRandom(mixed).nextDouble(-NOISE_RANGE, NOISE_RANGE);
    }

    private static boolean canPreemptLocked(NpcPlan current,
                                            NpcActivityCandidate candidate) {
        return candidate.urgent() && candidate.priority().isHigherThan(current.priority());
    }

    private static boolean expired(NpcPlan plan, long now) {
        return plan.expiresAt() != 0L && now > plan.expiresAt();
    }

    private static void rejectForCommitment(List<ScoredCandidate> candidates,
                                            NpcPlan current,
                                            List<CandidateRejection> rejections) {
        for (ScoredCandidate candidate : candidates) {
            if (current.candidateKey().equals(candidate.candidate().candidateKey())) continue;
            rejections.add(rejection(candidate.candidate(),
                    CandidateRejection.Code.COMMITMENT_LOCKED,
                    "当前计划 " + current.candidateKey() + " 锁定至 "
                            + current.commitmentUntil()));
        }
    }

    private static ScoredCandidate best(List<ScoredCandidate> candidates) {
        return candidates.stream()
                .max(Comparator.comparingInt((ScoredCandidate value) ->
                                value.candidate().priority().rank())
                        .thenComparingDouble(ScoredCandidate::score)
                        .thenComparing(value -> value.candidate().candidateKey(),
                                Comparator.reverseOrder()))
                .orElseThrow();
    }

    private record ScoredCandidate(NpcActivityCandidate candidate, double score) {
    }
}
