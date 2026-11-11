package com.jgeted.sagadyssey.social.institution;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Membership and standing are institution-specific; reputation never leaks globally. */
public record InstitutionMembership(UUID id, UUID institutionId, SubjectRef member,
                                    MembershipRank rank, int standing,
                                    long joinedAt, boolean active, String note) {
    public InstitutionMembership {
        if (id == null || institutionId == null || member == null || rank == null) {
            throw new IllegalArgumentException("incomplete membership");
        }
        standing = Math.max(0, Math.min(100, standing));
        note = note == null ? "" : note;
    }

    public InstitutionMembership withStanding(int delta, String reason) {
        return new InstitutionMembership(id, institutionId, member, rank,
                standing + delta, joinedAt, active, reason);
    }

    public InstitutionMembership suspended(String reason) {
        return new InstitutionMembership(id, institutionId, member, rank,
                standing, joinedAt, false, reason);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Institution", UUIDUtil.CODEC, institutionId);
        tag.put("Member", member.save());
        tag.putString("Rank", rank.name());
        tag.putInt("Standing", standing);
        tag.putLong("JoinedAt", joinedAt);
        tag.putBoolean("Active", active);
        tag.putString("Note", note);
        return tag;
    }

    static InstitutionMembership load(CompoundTag tag) {
        return new InstitutionMembership(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Institution", UUIDUtil.CODEC).orElseThrow(),
                SubjectRef.load(tag.getCompoundOrEmpty("Member")),
                MembershipRank.valueOf(tag.getStringOr("Rank", "")), tag.getIntOr("Standing", 0),
                tag.getLongOr("JoinedAt", 0L), tag.getBooleanOr("Active", false), tag.getStringOr("Note", ""));
    }
}
