package com.jgeted.sagadyssey.social.world;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

public record DynamicWorldEventRecord(UUID id, DynamicWorldEventType type,
                                      @Nullable UUID settlementId, String factionId,
                                      long startDay, long endDay, int severity) {
    public boolean activeOn(long day) { return day <= endDay; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.putString("Type", type.name());
        if (settlementId != null) tag.store("Settlement", UUIDUtil.CODEC, settlementId);
        tag.putString("Faction", factionId == null ? "" : factionId);
        tag.putLong("StartDay", startDay);
        tag.putLong("EndDay", endDay);
        tag.putInt("Severity", severity);
        return tag;
    }

    public static DynamicWorldEventRecord load(CompoundTag tag) {
        return new DynamicWorldEventRecord(tag.read("Id", UUIDUtil.CODEC).orElseThrow(),
                DynamicWorldEventType.byName(tag.getStringOr("Type", "")),
                tag.read("Settlement", UUIDUtil.CODEC).isPresent() ? tag.read("Settlement", UUIDUtil.CODEC).orElseThrow() : null,
                tag.getStringOr("Faction", ""), tag.getLongOr("StartDay", 0L), tag.getLongOr("EndDay", 0L),
                Math.max(1, Math.min(5, tag.getIntOr("Severity", 0))));
    }
}
