package com.jgeted.sagadyssey.social.justice;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Reference to evidence owned by another authoritative ledger, not a copied story. */
public record EvidenceRef(UUID id, EvidenceKind kind, UUID recordId,
                          SubjectRef submittedBy, long submittedAt, String note) {
    public EvidenceRef {
        if (id == null || kind == null || recordId == null || submittedBy == null) {
            throw new IllegalArgumentException("invalid evidence reference");
        }
        note = note == null ? "" : note;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Kind", kind.name());
        tag.putUUID("Record", recordId);
        tag.put("SubmittedBy", submittedBy.save());
        tag.putLong("SubmittedAt", submittedAt);
        tag.putString("Note", note);
        return tag;
    }

    public static EvidenceRef load(CompoundTag tag) {
        return new EvidenceRef(tag.getUUID("Id"), EvidenceKind.valueOf(tag.getString("Kind")),
                tag.getUUID("Record"), SubjectRef.load(tag.getCompound("SubmittedBy")),
                tag.getLong("SubmittedAt"), tag.getString("Note"));
    }
}
