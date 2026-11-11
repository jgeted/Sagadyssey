package com.jgeted.sagadyssey.npc.handover;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

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
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("ContainerId", UUIDUtil.CODEC, containerId);
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
        tag.store("EstablishedBy", UUIDUtil.CODEC, establishedBy);
        tag.putBoolean("Enabled", enabled);
        return tag;
    }

    static SupplyPointPolicy load(CompoundTag tag) {
        Set<UUID> beneficiaries = new HashSet<>();
        ListTag list = MinecraftApi.listOfType(tag, "Beneficiaries", Tag.TAG_STRING);
        for (int index = 0; index < list.size(); index++) {
            try { beneficiaries.add(UUID.fromString(list.getStringOr(index, ""))); }
            catch (IllegalArgumentException ignored) { }
        }
        SupplyCategory category;
        try { category = SupplyCategory.valueOf(tag.getStringOr("Category", "")); }
        catch (IllegalArgumentException ignored) { category = SupplyCategory.MATERIAL; }
        return new SupplyPointPolicy(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("ContainerId", UUIDUtil.CODEC).orElseThrow(),
                tag.getStringOr("Dimension", ""), BlockPos.of(tag.getLongOr("Position", 0L)), category,
                tag.getIntOr("MinimumReserve", 0), tag.getIntOr("MaximumPerClaim", 0),
                tag.getLongOr("CooldownTicks", 0L), beneficiaries, tag.read("EstablishedBy", UUIDUtil.CODEC).orElseThrow(),
                tag.getBooleanOr("Enabled", false));
    }
}
