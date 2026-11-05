package com.jgeted.sagadyssey.social.combat;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record MoraleProfile(UUID characterId, int baseline, int current,
                            long lastChangedAt, int revision) {
    public MoraleProfile {
        if (characterId == null || revision < 0) throw new IllegalArgumentException("invalid morale profile");
        baseline = clamp(baseline);
        current = clamp(current);
    }

    public MoraleProfile apply(int delta, long now) {
        return new MoraleProfile(characterId, baseline, current + delta, now, revision + 1);
    }

    public MoraleProfile recover(long now) {
        long steps = Math.max(0L, (now - lastChangedAt) / 6_000L);
        if (steps == 0L || current == baseline) return this;
        int movement = (int) Math.min(Math.abs(current - baseline), steps);
        int next = current < baseline ? current + movement : current - movement;
        return new MoraleProfile(characterId, baseline, next, now, revision + 1);
    }

    private static int clamp(int value) { return Math.max(0, Math.min(100, value)); }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Character", characterId);
        tag.putInt("Baseline", baseline);
        tag.putInt("Current", current);
        tag.putLong("LastChangedAt", lastChangedAt);
        tag.putInt("Revision", revision);
        return tag;
    }

    static MoraleProfile load(CompoundTag tag) {
        return new MoraleProfile(tag.getUUID("Character"), tag.getInt("Baseline"),
                tag.getInt("Current"), tag.getLong("LastChangedAt"), tag.getInt("Revision"));
    }
}
