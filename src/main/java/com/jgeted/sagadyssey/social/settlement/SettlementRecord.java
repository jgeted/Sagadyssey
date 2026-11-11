package com.jgeted.sagadyssey.social.settlement;

import net.minecraft.core.UUIDUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/** 一个有空间边界和政治归属的聚落，不代表虚拟资源库存。 */
public record SettlementRecord(UUID id, String name, SettlementType type, String dimension,
                               BlockPos center, int radius, String factionId,
                               @Nullable UUID founderCharacterId, long foundedGameTime) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.putString("Name", name);
        tag.putString("Type", type.name());
        tag.putString("Dimension", dimension);
        tag.putLong("Center", center.asLong());
        tag.putInt("Radius", radius);
        tag.putString("Faction", factionId);
        if (founderCharacterId != null) tag.store("Founder", UUIDUtil.CODEC, founderCharacterId);
        tag.putLong("Founded", foundedGameTime);
        return tag;
    }

    public static SettlementRecord load(CompoundTag tag) {
        return new SettlementRecord(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), cleanName(tag.getStringOr("Name", "")),
                SettlementType.byName(tag.getStringOr("Type", "")), tag.getStringOr("Dimension", ""),
                BlockPos.of(tag.getLongOr("Center", 0L)), Math.max(16, tag.getIntOr("Radius", 0)),
                normalizeFaction(tag.getStringOr("Faction", "")),
                tag.read("Founder", UUIDUtil.CODEC).isPresent() ? tag.read("Founder", UUIDUtil.CODEC).orElseThrow() : null,
                tag.getLongOr("Founded", 0L));
    }

    public static String cleanName(String name) {
        if (name == null || name.isBlank()) return "未命名聚落";
        String cleaned = name.replaceAll("§[0-9a-fk-or]", "").trim();
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }

    public static String normalizeFaction(String factionId) {
        if (factionId == null || factionId.isBlank()) return "sagadyssey:wilderness";
        return factionId.contains(":") ? factionId : "sagadyssey:" + factionId;
    }
}
