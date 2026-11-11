package com.jgeted.sagadyssey.social.justice;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record ProtectionOrder(UUID id, UUID disputeId, UUID institutionId,
                              UUID settlementId, SubjectRef protectedSubject,
                              SubjectRef restrainedSubject, SubjectRef issuedBy,
                              long issuedAt, long expiresAt, String terms) {
    public ProtectionOrder {
        if (id == null || disputeId == null || institutionId == null || settlementId == null
                || protectedSubject == null || restrainedSubject == null || issuedBy == null
                || expiresAt <= issuedAt) throw new IllegalArgumentException("invalid protection order");
        terms = terms == null ? "" : terms;
    }

    public boolean activeAt(long now) { return now >= issuedAt && now < expiresAt; }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Dispute", UUIDUtil.CODEC, disputeId);
        tag.store("Institution", UUIDUtil.CODEC, institutionId);
        tag.store("Settlement", UUIDUtil.CODEC, settlementId);
        tag.put("Protected", protectedSubject.save());
        tag.put("Restrained", restrainedSubject.save());
        tag.put("IssuedBy", issuedBy.save());
        tag.putLong("IssuedAt", issuedAt);
        tag.putLong("ExpiresAt", expiresAt);
        tag.putString("Terms", terms);
        return tag;
    }

    static ProtectionOrder load(CompoundTag tag) {
        return new ProtectionOrder(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Dispute", UUIDUtil.CODEC).orElseThrow(),
                tag.read("Institution", UUIDUtil.CODEC).orElseThrow(), tag.read("Settlement", UUIDUtil.CODEC).orElseThrow(),
                SubjectRef.load(tag.getCompoundOrEmpty("Protected")),
                SubjectRef.load(tag.getCompoundOrEmpty("Restrained")),
                SubjectRef.load(tag.getCompoundOrEmpty("IssuedBy")), tag.getLongOr("IssuedAt", 0L),
                tag.getLongOr("ExpiresAt", 0L), tag.getStringOr("Terms", ""));
    }
}
