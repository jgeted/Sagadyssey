package com.jgeted.sagadyssey.social.combat;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

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
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.putString("Name", name);
        if (institutionId != null) tag.store("Institution", UUIDUtil.CODEC, institutionId);
        ListTag memberTags = new ListTag();
        members.forEach(member -> {
            CompoundTag value = new CompoundTag();
            value.store("Character", UUIDUtil.CODEC, member);
            memberTags.add(value);
        });
        tag.put("Members", memberTags);
        tag.putString("Rule", rule.name());
        return tag;
    }

    static CombatSide load(CompoundTag tag) {
        Set<UUID> members = new HashSet<>();
        ListTag memberTags = MinecraftApi.listOfType(tag, "Members", Tag.TAG_COMPOUND);
        for (int index = 0; index < memberTags.size(); index++) {
            members.add(memberTags.getCompoundOrEmpty(index).read("Character", UUIDUtil.CODEC).orElseThrow());
        }
        return new CombatSide(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.getStringOr("Name", ""),
                tag.read("Institution", UUIDUtil.CODEC).isPresent() ? tag.read("Institution", UUIDUtil.CODEC).orElseThrow() : null,
                members, EngagementRule.valueOf(tag.getStringOr("Rule", "")));
    }
}
