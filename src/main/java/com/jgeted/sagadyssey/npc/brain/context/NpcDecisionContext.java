package com.jgeted.sagadyssey.npc.brain.context;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 一次候选活动生成与评分所共享的不可变决策上下文。
 */
public record NpcDecisionContext(
        UUID npcId,
        long gameTime,
        long randomSeed,
        NpcPerceptionSnapshot perception,
        Set<String> satisfiedPrerequisites,
        Map<String, Double> utilityModifiers
) {
    public NpcDecisionContext {
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(perception, "perception");
        Objects.requireNonNull(satisfiedPrerequisites, "satisfiedPrerequisites");
        Objects.requireNonNull(utilityModifiers, "utilityModifiers");
        if (gameTime < 0L) throw new IllegalArgumentException("gameTime cannot be negative");
        if (perception.capturedAt() > gameTime) {
            throw new IllegalArgumentException("perception cannot come from the future");
        }

        satisfiedPrerequisites = Set.copyOf(satisfiedPrerequisites);
        utilityModifiers = Map.copyOf(utilityModifiers);
        utilityModifiers.forEach((key, value) -> {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("utility modifier must be finite: " + key);
            }
        });
    }
}
