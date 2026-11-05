package com.jgeted.sagadyssey.social.institution;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record InstitutionRelation(UUID sourceInstitutionId, UUID targetInstitutionId,
                                  InstitutionRelationKind kind, long establishedAt,
                                  long expiresAt, String basis) {
    public InstitutionRelation {
        if (sourceInstitutionId == null || targetInstitutionId == null
                || sourceInstitutionId.equals(targetInstitutionId) || kind == null
                || expiresAt != 0L && expiresAt <= establishedAt) {
            throw new IllegalArgumentException("invalid institution relation");
        }
        basis = basis == null ? "" : basis;
    }

    public boolean activeAt(long now) { return now >= establishedAt && (expiresAt == 0L || now < expiresAt); }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Source", sourceInstitutionId);
        tag.putUUID("Target", targetInstitutionId);
        tag.putString("Kind", kind.name());
        tag.putLong("EstablishedAt", establishedAt);
        tag.putLong("ExpiresAt", expiresAt);
        tag.putString("Basis", basis);
        return tag;
    }

    static InstitutionRelation load(CompoundTag tag) {
        return new InstitutionRelation(tag.getUUID("Source"), tag.getUUID("Target"),
                InstitutionRelationKind.valueOf(tag.getString("Kind")),
                tag.getLong("EstablishedAt"), tag.getLong("ExpiresAt"), tag.getString("Basis"));
    }
}
