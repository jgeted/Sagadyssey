package com.jgeted.sagadyssey.social.world;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

public record DynamicWorldEventRecord(UUID id, DynamicWorldEventType type,
                                      @Nullable UUID settlementId, String factionId,
                                      long startDay, long endDay, int severity) {
    public boolean activeOn(long day) { return day <= endDay; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Type", type.name());
        if (settlementId != null) tag.putUUID("Settlement", settlementId);
        tag.putString("Faction", factionId == null ? "" : factionId);
        tag.putLong("StartDay", startDay);
        tag.putLong("EndDay", endDay);
        tag.putInt("Severity", severity);
        return tag;
    }

    public static DynamicWorldEventRecord load(CompoundTag tag) {
        return new DynamicWorldEventRecord(tag.getUUID("Id"),
                DynamicWorldEventType.byName(tag.getString("Type")),
                tag.hasUUID("Settlement") ? tag.getUUID("Settlement") : null,
                tag.getString("Faction"), tag.getLong("StartDay"), tag.getLong("EndDay"),
                Math.max(1, Math.min(5, tag.getInt("Severity"))));
    }
}
