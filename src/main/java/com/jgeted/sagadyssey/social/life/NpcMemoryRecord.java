package com.jgeted.sagadyssey.social.life;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/** 有限、会衰减的近期记忆；重要事件不会因每 tick 决策而无限累积。 */
public record NpcMemoryRecord(String type, @Nullable UUID subjectCharacterId, String summary,
                              int valence, int intensity, long happenedAt, long expiresAt) {
    public NpcMemoryRecord {
        type = type == null || type.isBlank() ? "event" : type;
        summary = summary == null ? "" : summary;
        valence = Math.max(-100, Math.min(100, valence));
        intensity = Math.max(0, Math.min(100, intensity));
    }

    public boolean active(long gameTime) { return expiresAt < 0L || gameTime <= expiresAt; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Type", type);
        if (subjectCharacterId != null) tag.putUUID("Subject", subjectCharacterId);
        tag.putString("Summary", summary);
        tag.putInt("Valence", valence);
        tag.putInt("Intensity", intensity);
        tag.putLong("HappenedAt", happenedAt);
        tag.putLong("ExpiresAt", expiresAt);
        return tag;
    }

    public static NpcMemoryRecord load(CompoundTag tag) {
        return new NpcMemoryRecord(tag.getString("Type"),
                tag.hasUUID("Subject") ? tag.getUUID("Subject") : null,
                tag.getString("Summary"), tag.getInt("Valence"), tag.getInt("Intensity"),
                tag.getLong("HappenedAt"), tag.contains("ExpiresAt") ? tag.getLong("ExpiresAt") : -1L);
    }
}
