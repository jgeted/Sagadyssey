package com.jgeted.sagadyssey.social.institution;

import net.minecraft.core.UUIDUtil;

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
        tag.store("Source", UUIDUtil.CODEC, sourceInstitutionId);
        tag.store("Target", UUIDUtil.CODEC, targetInstitutionId);
        tag.putString("Kind", kind.name());
        tag.putLong("EstablishedAt", establishedAt);
        tag.putLong("ExpiresAt", expiresAt);
        tag.putString("Basis", basis);
        return tag;
    }

    static InstitutionRelation load(CompoundTag tag) {
        return new InstitutionRelation(tag.read("Source", UUIDUtil.CODEC).orElseThrow(), tag.read("Target", UUIDUtil.CODEC).orElseThrow(),
                InstitutionRelationKind.valueOf(tag.getStringOr("Kind", "")),
                tag.getLongOr("EstablishedAt", 0L), tag.getLongOr("ExpiresAt", 0L), tag.getStringOr("Basis", ""));
    }
}
