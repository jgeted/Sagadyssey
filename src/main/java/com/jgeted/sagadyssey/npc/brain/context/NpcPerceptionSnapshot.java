package com.jgeted.sagadyssey.npc.brain.context;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * NPC 在某一时刻拥有的主观感知快照。
 *
 * <p>快照只记录 NPC 实际看见、记得或获知的内容，不应混入管理员掌握的全局真相。</p>
 */
public record NpcPerceptionSnapshot(
        long capturedAt,
        Set<UUID> visibleCharacters,
        Set<String> knownLocationKeys,
        Set<String> knownFactKeys
) {
    public NpcPerceptionSnapshot {
        if (capturedAt < 0L) throw new IllegalArgumentException("capturedAt cannot be negative");
        Objects.requireNonNull(visibleCharacters, "visibleCharacters");
        Objects.requireNonNull(knownLocationKeys, "knownLocationKeys");
        Objects.requireNonNull(knownFactKeys, "knownFactKeys");

        visibleCharacters = Set.copyOf(visibleCharacters);
        knownLocationKeys = Set.copyOf(knownLocationKeys);
        knownFactKeys = Set.copyOf(knownFactKeys);
    }
}
