package com.jgeted.sagadyssey.social.logistics;

import net.minecraft.core.UUIDUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** 聚落中的真实容器节点。 */
public record WarehouseRecord(UUID id, UUID settlementId, String name, String dimension,
                              BlockPos position, UUID registeredByCharacter, long registeredGameTime) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Settlement", UUIDUtil.CODEC, settlementId);
        tag.putString("Name", cleanName(name));
        tag.putString("Dimension", dimension);
        tag.putLong("Position", position.asLong());
        tag.store("RegisteredBy", UUIDUtil.CODEC, registeredByCharacter);
        tag.putLong("Registered", registeredGameTime);
        return tag;
    }

    public static WarehouseRecord load(CompoundTag tag) {
        return new WarehouseRecord(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Settlement", UUIDUtil.CODEC).orElseThrow(),
                cleanName(tag.getStringOr("Name", "")), tag.getStringOr("Dimension", ""),
                BlockPos.of(tag.getLongOr("Position", 0L)), tag.read("RegisteredBy", UUIDUtil.CODEC).orElseThrow(),
                tag.getLongOr("Registered", 0L));
    }

    public static String cleanName(String name) {
        if (name == null || name.isBlank()) return "仓库";
        String cleaned = name.replaceAll("§[0-9a-fk-or]", "").trim();
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }
}
