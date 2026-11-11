package com.jgeted.sagadyssey.social.settlement;

import net.minecraft.core.UUIDUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/** 对真实世界建筑的登记记录；只索引坐标，不生成物资。 */
public record BuildingRecord(String key, String structureId, String category, String size,
                             String dimension, BlockPos position,
                             @Nullable UUID settlementId, long registeredGameTime) {
    public BuildingRecord assignedTo(UUID settlementId) {
        return new BuildingRecord(key, structureId, category, size, dimension, position,
                settlementId, registeredGameTime);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Key", key);
        tag.putString("Structure", structureId);
        tag.putString("Category", category);
        tag.putString("Size", size);
        tag.putString("Dimension", dimension);
        tag.putLong("Position", position.asLong());
        if (settlementId != null) tag.store("Settlement", UUIDUtil.CODEC, settlementId);
        tag.putLong("Registered", registeredGameTime);
        return tag;
    }

    public static BuildingRecord load(CompoundTag tag) {
        return new BuildingRecord(tag.getStringOr("Key", ""), tag.getStringOr("Structure", ""),
                tag.getStringOr("Category", ""), tag.getStringOr("Size", ""), tag.getStringOr("Dimension", ""),
                BlockPos.of(tag.getLongOr("Position", 0L)),
                tag.read("Settlement", UUIDUtil.CODEC).isPresent() ? tag.read("Settlement", UUIDUtil.CODEC).orElseThrow() : null,
                tag.getLongOr("Registered", 0L));
    }
}
