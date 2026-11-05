package com.jgeted.sagadyssey.social.institution;

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
        tag.putUUID("Id", id);
        tag.putUUID("Institution", institutionId);
        tag.put("Member", member.save());
        tag.putString("Rank", rank.name());
        tag.putInt("Standing", standing);
        tag.putLong("JoinedAt", joinedAt);
        tag.putBoolean("Active", active);
        tag.putString("Note", note);
        return tag;
    }

    static InstitutionMembership load(CompoundTag tag) {
        return new InstitutionMembership(tag.getUUID("Id"), tag.getUUID("Institution"),
                SubjectRef.load(tag.getCompound("Member")),
                MembershipRank.valueOf(tag.getString("Rank")), tag.getInt("Standing"),
                tag.getLong("JoinedAt"), tag.getBoolean("Active"), tag.getString("Note"));
    }
}
