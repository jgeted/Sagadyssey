package com.jgeted.sagadyssey.social.combat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public record CombatSide(UUID id, String name, @Nullable UUID institutionId,
                         Set<UUID> members, EngagementRule rule) {
    public CombatSide {
        if (id == null || members == null || members.isEmpty() || rule == null) {
            throw new IllegalArgumentException("invalid combat side");
        }
        name = name == null || name.isBlank() ? "未命名一方" : name;
        members = Set.copyOf(members);
    }

    public boolean contains(UUID characterId) { return members.contains(characterId); }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        if (institutionId != null) tag.putUUID("Institution", institutionId);
        ListTag memberTags = new ListTag();
        members.forEach(member -> {
            CompoundTag value = new CompoundTag();
            value.putUUID("Character", member);
            memberTags.add(value);
        });
        tag.put("Members", memberTags);
        tag.putString("Rule", rule.name());
        return tag;
    }

    static CombatSide load(CompoundTag tag) {
        Set<UUID> members = new HashSet<>();
        ListTag memberTags = tag.getList("Members", Tag.TAG_COMPOUND);
        for (int index = 0; index < memberTags.size(); index++) {
            members.add(memberTags.getCompound(index).getUUID("Character"));
        }
        return new CombatSide(tag.getUUID("Id"), tag.getString("Name"),
                tag.hasUUID("Institution") ? tag.getUUID("Institution") : null,
                members, EngagementRule.valueOf(tag.getString("Rule")));
    }
}
