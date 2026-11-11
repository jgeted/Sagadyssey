package com.jgeted.sagadyssey.npc.brain.candidate;

import com.jgeted.sagadyssey.npc.brain.context.NpcDecisionContext;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPriority;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Provider 提出的不可变活动候选。
 *
 * <p>候选只描述意图和评分输入；只有调度器选中并创建计划后，执行器才能改变世界。</p>
 */
public record NpcActivityCandidate(
        String candidateKey,
        Identifier providerId,
        NpcPlanKind kind,
        NpcPlanPriority priority,
        @Nullable UUID subjectId,
        @Nullable String targetKey,
        List<NpcPlanStepTemplate> steps,
        double situationBenefit,
        double risk,
        double effortCost,
        double opportunityCost,
        boolean urgent,
        long earliestStart,
        long latestStart,
        long expectedDuration,
        Set<String> prerequisites,
        Set<String> hardBlockers,
        Map<String, String> metadata
) {
    public NpcActivityCandidate {
        if (candidateKey == null || candidateKey.isBlank()) {
            throw new IllegalArgumentException("candidateKey must not be blank");
        }
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(steps, "steps");
        Objects.requireNonNull(prerequisites, "prerequisites");
        Objects.requireNonNull(hardBlockers, "hardBlockers");
        Objects.requireNonNull(metadata, "metadata");

        requireFinite("situationBenefit", situationBenefit);
        requireFinite("risk", risk);
        requireFinite("effortCost", effortCost);
        requireFinite("opportunityCost", opportunityCost);
        if (risk < 0.0D || effortCost < 0.0D || opportunityCost < 0.0D) {
            throw new IllegalArgumentException("candidate costs must not be negative");
        }

        if (earliestStart < 0L || latestStart < 0L) {
            throw new IllegalArgumentException("start times must not be negative");
        }
        if (latestStart < earliestStart) {
            throw new IllegalArgumentException("latestStart must not precede earliestStart");
        }
        if (expectedDuration < 0L) {
            throw new IllegalArgumentException("expectedDuration must not be negative");
        }

        steps = List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("candidate requires plan steps");
        for (int index = 1; index < steps.size(); index++) {
            if (!steps.get(index).phase().canFollow(steps.get(index - 1).phase())) {
                throw new IllegalArgumentException("plan step phases cannot move backwards");
            }
        }
        prerequisites = Set.copyOf(prerequisites);
        hardBlockers = Set.copyOf(hardBlockers);
        metadata = Map.copyOf(metadata);
    }

    /**
     * 计算不含随机扰动、承诺锁和重复活动衰减的基础效用。
     */
    public double baseUtility(NpcDecisionContext context) {
        Objects.requireNonNull(context, "context");
        double kindModifier = context.utilityModifiers()
                .getOrDefault(kind.name().toLowerCase(Locale.ROOT), 0.0D);
        return situationBenefit + kindModifier
                - risk
                - effortCost
                - opportunityCost;
    }

    private static void requireFinite(String field, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

}
