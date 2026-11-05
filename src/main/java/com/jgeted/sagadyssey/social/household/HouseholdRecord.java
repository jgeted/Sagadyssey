package com.jgeted.sagadyssey.social.household;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 一个户籍家庭；共享财产、住所和成员身份与职业组织完全分离。 */
public record HouseholdRecord(
        UUID id,
        String name,
        String dimension,
        @Nullable UUID settlementId,
        UUID headCharacterId,
        @Nullable BlockPos homeAnchor,
        @Nullable BlockPos sharedStorage,
        List<HouseholdMemberRecord> members,
        long createdAt) {

    public static final int MAX_MEMBERS = 24;

    public HouseholdRecord {
        name = cleanName(name);
        dimension = dimension == null ? "" : dimension;
        members = members == null ? List.of() : List.copyOf(members);
    }

    public HouseholdRole roleOf(UUID characterId) {
        return members.stream().filter(member -> member.characterId().equals(characterId))
                .map(HouseholdMemberRecord::role).findFirst().orElse(null);
    }

    public boolean contains(UUID characterId) { return roleOf(characterId) != null; }

    public HouseholdRecord withMember(UUID characterId, HouseholdRole role, long gameTime) {
        List<HouseholdMemberRecord> updated = new ArrayList<>(members);
        HouseholdMemberRecord existing = updated.stream()
                .filter(member -> member.characterId().equals(characterId)).findFirst().orElse(null);
        updated.removeIf(member -> member.characterId().equals(characterId));
        updated.add(new HouseholdMemberRecord(characterId, role,
                existing == null ? gameTime : existing.joinedAt()));
        return copy(headCharacterId, homeAnchor, sharedStorage, updated);
    }

    public HouseholdRecord withoutMember(UUID characterId) {
        return copy(headCharacterId, homeAnchor, sharedStorage,
                members.stream().filter(member -> !member.characterId().equals(characterId)).toList());
    }

    public HouseholdRecord withHead(UUID characterId, long gameTime) {
        List<HouseholdMemberRecord> updated = new ArrayList<>();
        for (HouseholdMemberRecord member : members) {
            HouseholdRole role = member.characterId().equals(characterId) ? HouseholdRole.HEAD
                    : member.characterId().equals(headCharacterId) && member.role() == HouseholdRole.HEAD
                    ? HouseholdRole.KIN : member.role();
            updated.add(new HouseholdMemberRecord(member.characterId(), role, member.joinedAt()));
        }
        if (updated.stream().noneMatch(member -> member.characterId().equals(characterId))) {
            updated.add(new HouseholdMemberRecord(characterId, HouseholdRole.HEAD, gameTime));
        }
        return copy(characterId, homeAnchor, sharedStorage, updated);
    }

    public HouseholdRecord withHome(@Nullable BlockPos anchor, @Nullable BlockPos storage) {
        return copy(headCharacterId, anchor, storage, members);
    }

    private HouseholdRecord copy(UUID head, @Nullable BlockPos anchor, @Nullable BlockPos storage,
                                 List<HouseholdMemberRecord> updatedMembers) {
        return new HouseholdRecord(id, name, dimension, settlementId, head, anchor,
                storage, updatedMembers, createdAt);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        tag.putString("Dimension", dimension);
        if (settlementId != null) tag.putUUID("Settlement", settlementId);
        tag.putUUID("Head", headCharacterId);
        if (homeAnchor != null) tag.putLong("Home", homeAnchor.asLong());
        if (sharedStorage != null) tag.putLong("SharedStorage", sharedStorage.asLong());
        ListTag memberList = new ListTag();
        members.forEach(member -> memberList.add(member.save()));
        tag.put("Members", memberList);
        tag.putLong("Created", createdAt);
        return tag;
    }

    @Nullable
    public static HouseholdRecord load(CompoundTag tag) {
        if (!tag.hasUUID("Id") || !tag.hasUUID("Head")) return null;
        List<HouseholdMemberRecord> members = new ArrayList<>();
        ListTag memberList = tag.getList("Members", Tag.TAG_COMPOUND);
        for (int index = 0; index < memberList.size(); index++) {
            CompoundTag memberTag = memberList.getCompound(index);
            if (memberTag.hasUUID("Character")) members.add(HouseholdMemberRecord.load(memberTag));
        }
        UUID head = tag.getUUID("Head");
        if (members.stream().noneMatch(member -> member.characterId().equals(head))) {
            members.add(new HouseholdMemberRecord(head, HouseholdRole.HEAD, tag.getLong("Created")));
        }
        members = members.stream().map(member -> new HouseholdMemberRecord(member.characterId(),
                member.characterId().equals(head) ? HouseholdRole.HEAD
                        : member.role() == HouseholdRole.HEAD ? HouseholdRole.KIN : member.role(),
                member.joinedAt())).toList();
        return new HouseholdRecord(tag.getUUID("Id"), tag.getString("Name"),
                tag.getString("Dimension"), tag.hasUUID("Settlement") ? tag.getUUID("Settlement") : null,
                head, tag.contains("Home") ? BlockPos.of(tag.getLong("Home")) : null,
                tag.contains("SharedStorage") ? BlockPos.of(tag.getLong("SharedStorage")) : null,
                members, tag.getLong("Created"));
    }

    public static String cleanName(String value) {
        if (value == null || value.isBlank()) return "未命名家庭";
        String cleaned = value.replaceAll("§[0-9a-fk-or]", "").trim();
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }
}
