package com.jgeted.sagadyssey.social.household;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record HouseholdMemberRecord(UUID characterId, HouseholdRole role, long joinedAt) {
    public HouseholdMemberRecord {
        role = role == null ? HouseholdRole.KIN : role;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Character", characterId);
        tag.putString("Role", role.name());
        tag.putLong("Joined", joinedAt);
        return tag;
    }

    public static HouseholdMemberRecord load(CompoundTag tag) {
        return new HouseholdMemberRecord(tag.getUUID("Character"),
                HouseholdRole.parse(tag.getString("Role")), tag.getLong("Joined"));
    }
}
