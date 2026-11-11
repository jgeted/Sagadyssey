package com.jgeted.sagadyssey.worldsim.rights;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

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
        tag.store("Id", UUIDUtil.CODEC, claimId);
        tag.store("Container", UUIDUtil.CODEC, containerId);
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
        return new AssetClaim(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Container", UUIDUtil.CODEC).orElseThrow(), tag.getIntOr("Slot", 0),
                ItemFingerprint.load(tag.getCompoundOrEmpty("Fingerprint")), tag.getIntOr("Quantity", 0),
                SubjectRef.load(tag.getCompoundOrEmpty("Owner")), SubjectRef.load(tag.getCompoundOrEmpty("Custodian")),
                strings(MinecraftApi.listOfType(tag, "Encumbrances", Tag.TAG_STRING)),
                strings(MinecraftApi.listOfType(tag, "Reservations", Tag.TAG_STRING)),
                tag.getStringOr("Provenance", ""), tag.getLongOr("Revision", 0L));
    }

    private static ListTag stringList(Set<String> values) {
        ListTag list = new ListTag();
        new TreeSet<>(values).forEach(value -> list.add(StringTag.valueOf(value)));
        return list;
    }

    private static Set<String> strings(ListTag list) {
        Set<String> values = new TreeSet<>();
        for (int i = 0; i < list.size(); i++) values.add(list.getStringOr(i, ""));
        return values;
    }
}
