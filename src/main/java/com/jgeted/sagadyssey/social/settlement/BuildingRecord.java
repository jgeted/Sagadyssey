package com.jgeted.sagadyssey.social.settlement;

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
        if (settlementId != null) tag.putUUID("Settlement", settlementId);
        tag.putLong("Registered", registeredGameTime);
        return tag;
    }

    public static BuildingRecord load(CompoundTag tag) {
        return new BuildingRecord(tag.getString("Key"), tag.getString("Structure"),
                tag.getString("Category"), tag.getString("Size"), tag.getString("Dimension"),
                BlockPos.of(tag.getLong("Position")),
                tag.hasUUID("Settlement") ? tag.getUUID("Settlement") : null,
                tag.getLong("Registered"));
    }
}
