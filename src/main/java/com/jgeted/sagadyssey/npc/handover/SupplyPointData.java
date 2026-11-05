package com.jgeted.sagadyssey.npc.handover;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent supply policies. The stock itself always remains in the block container. */
public final class SupplyPointData extends SavedData {
    private static final String DATA_NAME = "sagadyssey_supply_points_v2";
    private static final int DATA_VERSION = 1;
    private static final Factory<SupplyPointData> FACTORY =
            new Factory<>(SupplyPointData::new, SupplyPointData::load);

    private final Map<UUID, SupplyPointPolicy> policies = new HashMap<>();
    private final Map<String, Long> lastClaims = new HashMap<>();

    public static SupplyPointData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public void put(SupplyPointPolicy policy) {
        policies.put(policy.id(), policy);
        setDirty();
    }

    @Nullable public SupplyPointPolicy policy(UUID id) { return policies.get(id); }

    public List<SupplyPointPolicy> policiesFor(UUID beneficiary) {
        return policies.values().stream().filter(policy -> policy.allows(beneficiary))
                .sorted(Comparator.comparing(SupplyPointPolicy::dimension)
                        .thenComparing(policy -> policy.position().asLong())).toList();
    }

    public long lastClaimAt(UUID policyId, UUID beneficiary) {
        return lastClaims.getOrDefault(key(policyId, beneficiary), Long.MIN_VALUE / 2L);
    }

    public void recordClaim(UUID policyId, UUID beneficiary, long gameTime) {
        lastClaims.put(key(policyId, beneficiary), gameTime);
        setDirty();
    }

    private static String key(UUID policyId, UUID beneficiary) {
        return policyId + ":" + beneficiary;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("DataVersion", DATA_VERSION);
        ListTag policyTags = new ListTag();
        policies.values().forEach(policy -> policyTags.add(policy.save()));
        tag.put("Policies", policyTags);
        ListTag claimTags = new ListTag();
        lastClaims.forEach((key, time) -> {
            CompoundTag claim = new CompoundTag();
            claim.putString("Key", key);
            claim.putLong("GameTime", time);
            claimTags.add(claim);
        });
        tag.put("LastClaims", claimTags);
        return tag;
    }

    private static SupplyPointData load(CompoundTag tag, HolderLookup.Provider registries) {
        SupplyPointData data = new SupplyPointData();
        ListTag policyTags = tag.getList("Policies", Tag.TAG_COMPOUND);
        for (int index = 0; index < policyTags.size(); index++) {
            try {
                SupplyPointPolicy policy = SupplyPointPolicy.load(policyTags.getCompound(index));
                data.policies.put(policy.id(), policy);
            } catch (RuntimeException ignored) { }
        }
        ListTag claimTags = tag.getList("LastClaims", Tag.TAG_COMPOUND);
        for (int index = 0; index < claimTags.size(); index++) {
            CompoundTag claim = claimTags.getCompound(index);
            if (!claim.getString("Key").isBlank()) {
                data.lastClaims.put(claim.getString("Key"), claim.getLong("GameTime"));
            }
        }
        return data;
    }
}
