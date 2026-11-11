package com.jgeted.sagadyssey.worldsim.rights;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Quantity-level claim preventing two systems from promising the same physical stock. */
public record AssetReservation(UUID id, UUID containerId, ItemFingerprint fingerprint,
                               int quantity, SubjectRef reservedFor, String token,
                               String purpose, long createdAt, long expiresAt) {
    public AssetReservation {
        if (id == null || containerId == null || fingerprint == null || reservedFor == null
                || quantity <= 0 || token == null || token.isBlank() || expiresAt <= createdAt) {
            throw new IllegalArgumentException("invalid asset reservation");
        }
        purpose = purpose == null ? "" : purpose;
    }

    public boolean activeAt(long gameTime) { return gameTime < expiresAt; }

    public AssetReservation withQuantity(int nextQuantity) {
        return new AssetReservation(id, containerId, fingerprint, nextQuantity, reservedFor,
                token, purpose, createdAt, expiresAt);
    }

    public AssetReservation withExpiresAt(long nextExpiresAt) {
        if (nextExpiresAt < expiresAt) {
            throw new IllegalArgumentException("reservation expiry cannot move backwards");
        }
        return new AssetReservation(id, containerId, fingerprint, quantity, reservedFor,
                token, purpose, createdAt, nextExpiresAt);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Container", UUIDUtil.CODEC, containerId);
        tag.put("Fingerprint", fingerprint.save());
        tag.putInt("Quantity", quantity);
        tag.put("ReservedFor", reservedFor.save());
        tag.putString("Token", token);
        tag.putString("Purpose", purpose);
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("ExpiresAt", expiresAt);
        return tag;
    }

    public static AssetReservation load(CompoundTag tag) {
        return new AssetReservation(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Container", UUIDUtil.CODEC).orElseThrow(),
                ItemFingerprint.load(tag.getCompoundOrEmpty("Fingerprint")), tag.getIntOr("Quantity", 0),
                SubjectRef.load(tag.getCompoundOrEmpty("ReservedFor")), tag.getStringOr("Token", ""),
                tag.getStringOr("Purpose", ""), tag.getLongOr("CreatedAt", 0L), tag.getLongOr("ExpiresAt", 0L));
    }
}
