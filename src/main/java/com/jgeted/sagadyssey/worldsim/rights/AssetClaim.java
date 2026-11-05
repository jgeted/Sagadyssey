package com.jgeted.sagadyssey.worldsim.rights;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Exception claim for a physical stack; absent claims inherit the container defaults. */
public record AssetClaim(UUID claimId, UUID containerId, int slot, ItemFingerprint fingerprint,
                         int quantity, SubjectRef beneficialOwner, SubjectRef custodian,
                         Set<String> encumbrances, Set<String> reservations, String provenance,
                         long revision) {
    public AssetClaim {
        if (claimId == null || containerId == null || fingerprint == null
                || beneficialOwner == null || custodian == null || slot < 0 || quantity <= 0) {
            throw new IllegalArgumentException("invalid asset claim");
        }
        encumbrances = Set.copyOf(encumbrances == null ? Set.of() : encumbrances);
        reservations = Set.copyOf(reservations == null ? Set.of() : reservations);
        provenance = provenance == null ? "" : provenance;
        revision = Math.max(0L, revision);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", claimId);
        tag.putUUID("Container", containerId);
        tag.putInt("Slot", slot);
        tag.put("Fingerprint", fingerprint.save());
        tag.putInt("Quantity", quantity);
        tag.put("Owner", beneficialOwner.save());
        tag.put("Custodian", custodian.save());
        tag.put("Encumbrances", stringList(encumbrances));
        tag.put("Reservations", stringList(reservations));
        tag.putString("Provenance", provenance);
        tag.putLong("Revision", revision);
        return tag;
    }

    public static AssetClaim load(CompoundTag tag) {
        return new AssetClaim(tag.getUUID("Id"), tag.getUUID("Container"), tag.getInt("Slot"),
                ItemFingerprint.load(tag.getCompound("Fingerprint")), tag.getInt("Quantity"),
                SubjectRef.load(tag.getCompound("Owner")), SubjectRef.load(tag.getCompound("Custodian")),
                strings(tag.getList("Encumbrances", Tag.TAG_STRING)),
                strings(tag.getList("Reservations", Tag.TAG_STRING)),
                tag.getString("Provenance"), tag.getLong("Revision"));
    }

    private static ListTag stringList(Set<String> values) {
        ListTag list = new ListTag();
        new TreeSet<>(values).forEach(value -> list.add(StringTag.valueOf(value)));
        return list;
    }

    private static Set<String> strings(ListTag list) {
        Set<String> values = new TreeSet<>();
        for (int i = 0; i < list.size(); i++) values.add(list.getString(i));
        return values;
    }
}
