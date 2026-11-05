package com.jgeted.sagadyssey.social.character;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/** Last known physical body occupied by a stable social character. */
public record CharacterBodyRef(String dimensionId, UUID entityId, long observedGameTime) {
    public CharacterBodyRef {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimensionId must not be blank");
        }
        if (entityId == null) throw new IllegalArgumentException("entityId must not be null");
        observedGameTime = Math.max(0L, observedGameTime);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", dimensionId);
        tag.putUUID("Entity", entityId);
        tag.putLong("ObservedAt", observedGameTime);
        return tag;
    }

    @Nullable
    public static CharacterBodyRef load(CompoundTag tag) {
        if (!tag.hasUUID("Entity") || tag.getString("Dimension").isBlank()) return null;
        return new CharacterBodyRef(tag.getString("Dimension"), tag.getUUID("Entity"),
                tag.getLong("ObservedAt"));
    }
}
