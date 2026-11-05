package com.jgeted.sagadyssey.social.institution;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** A contestable claim to exercise one power in one settlement. */
public record JurisdictionClaim(UUID id, UUID institutionId, UUID settlementId,
                                OfficeAuthority authority, int priority,
                                long assertedAt, long expiresAt, String basis) {
    public JurisdictionClaim {
        if (id == null || institutionId == null || settlementId == null || authority == null
                || priority < 0 || priority > 100 || expiresAt != 0L && expiresAt <= assertedAt) {
            throw new IllegalArgumentException("invalid jurisdiction claim");
        }
        basis = basis == null ? "" : basis;
    }

    public boolean activeAt(long now) { return now >= assertedAt && (expiresAt == 0L || now < expiresAt); }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Institution", institutionId);
        tag.putUUID("Settlement", settlementId);
        tag.putString("Authority", authority.name());
        tag.putInt("Priority", priority);
        tag.putLong("AssertedAt", assertedAt);
        tag.putLong("ExpiresAt", expiresAt);
        tag.putString("Basis", basis);
        return tag;
    }

    static JurisdictionClaim load(CompoundTag tag) {
        return new JurisdictionClaim(tag.getUUID("Id"), tag.getUUID("Institution"),
                tag.getUUID("Settlement"), OfficeAuthority.valueOf(tag.getString("Authority")),
                tag.getInt("Priority"), tag.getLong("AssertedAt"),
                tag.getLong("ExpiresAt"), tag.getString("Basis"));
    }
}
