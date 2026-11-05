package com.jgeted.sagadyssey.social.justice;

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
        tag.putUUID("Id", id);
        tag.putUUID("Dispute", disputeId);
        tag.putUUID("Institution", institutionId);
        tag.putUUID("Settlement", settlementId);
        tag.put("Protected", protectedSubject.save());
        tag.put("Restrained", restrainedSubject.save());
        tag.put("IssuedBy", issuedBy.save());
        tag.putLong("IssuedAt", issuedAt);
        tag.putLong("ExpiresAt", expiresAt);
        tag.putString("Terms", terms);
        return tag;
    }

    static ProtectionOrder load(CompoundTag tag) {
        return new ProtectionOrder(tag.getUUID("Id"), tag.getUUID("Dispute"),
                tag.getUUID("Institution"), tag.getUUID("Settlement"),
                SubjectRef.load(tag.getCompound("Protected")),
                SubjectRef.load(tag.getCompound("Restrained")),
                SubjectRef.load(tag.getCompound("IssuedBy")), tag.getLong("IssuedAt"),
                tag.getLong("ExpiresAt"), tag.getString("Terms"));
    }
}
