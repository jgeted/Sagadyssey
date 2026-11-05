package com.jgeted.sagadyssey.social.character;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Legacy entities used their body UUID as identity; v2 persists a separate stable character UUID. */
public final class NpcCharacterIdentityMigration {
    public static final String CHARACTER_ID_KEY = "CharacterId";

    private NpcCharacterIdentityMigration() {}

    public static UUID resolve(CompoundTag tag, UUID entityId) {
        return tag.hasUUID(CHARACTER_ID_KEY) ? tag.getUUID(CHARACTER_ID_KEY) : entityId;
    }
}
