package com.jgeted.sagadyssey.npc.handover;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A real container plus a bounded permission to draw supplies from it. */
public record SupplyPointPolicy(UUID id, UUID containerId, String dimension, BlockPos position,
                                SupplyCategory category, int minimumReserve, int maximumPerClaim,
                                long cooldownTicks, Set<UUID> beneficiaries, UUID establishedBy,
                                boolean enabled) {
    public SupplyPointPolicy {
        if (id == null || containerId == null || establishedBy == null || position == null
                || dimension == null || dimension.isBlank() || category == null) {
            throw new IllegalArgumentException("Supply point identity and location are required");
        }
        minimumReserve = Math.max(0, minimumReserve);
        maximumPerClaim = Math.max(1, Math.min(1_728, maximumPerClaim));
        cooldownTicks = Math.max(20L, cooldownTicks);
        beneficiaries = beneficiaries == null ? Set.of() : Set.copyOf(beneficiaries);
    }

    public boolean allows(UUID characterId) {
        return enabled && beneficiaries.contains(characterId);
    }

    public int claimable(int physicalStock, long lastClaimAt, long now) {
        if (!enabled || now - lastClaimAt < cooldownTicks) return 0;
        return Math.min(maximumPerClaim, Math.max(0, physicalStock - minimumReserve));
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("ContainerId", containerId);
        tag.putString("Dimension", dimension);
        tag.putLong("Position", position.asLong());
        tag.putString("Category", category.name());
        tag.putInt("MinimumReserve", minimumReserve);
        tag.putInt("MaximumPerClaim", maximumPerClaim);
        tag.putLong("CooldownTicks", cooldownTicks);
        ListTag beneficiaryTags = new ListTag();
        beneficiaries.stream().map(UUID::toString).sorted()
                .forEach(value -> beneficiaryTags.add(StringTag.valueOf(value)));
        tag.put("Beneficiaries", beneficiaryTags);
        tag.putUUID("EstablishedBy", establishedBy);
        tag.putBoolean("Enabled", enabled);
        return tag;
    }

    static SupplyPointPolicy load(CompoundTag tag) {
        Set<UUID> beneficiaries = new HashSet<>();
        ListTag list = tag.getList("Beneficiaries", Tag.TAG_STRING);
        for (int index = 0; index < list.size(); index++) {
            try { beneficiaries.add(UUID.fromString(list.getString(index))); }
            catch (IllegalArgumentException ignored) { }
        }
        SupplyCategory category;
        try { category = SupplyCategory.valueOf(tag.getString("Category")); }
        catch (IllegalArgumentException ignored) { category = SupplyCategory.MATERIAL; }
        return new SupplyPointPolicy(tag.getUUID("Id"), tag.getUUID("ContainerId"),
                tag.getString("Dimension"), BlockPos.of(tag.getLong("Position")), category,
                tag.getInt("MinimumReserve"), tag.getInt("MaximumPerClaim"),
                tag.getLong("CooldownTicks"), beneficiaries, tag.getUUID("EstablishedBy"),
                tag.getBoolean("Enabled"));
    }
}
