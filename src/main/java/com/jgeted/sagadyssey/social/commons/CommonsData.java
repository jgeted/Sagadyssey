package com.jgeted.sagadyssey.social.commons;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class CommonsData extends NbtSavedData {
    private static final String DATA_NAME = "sagadyssey_commons";
    private static final Factory<CommonsData> FACTORY =
            new Factory<>(CommonsData::new, CommonsData::load);
    private final Map<UUID, CommonsPolicy> policies = new HashMap<>();
    private final Map<UUID, CommonsWithdrawal> withdrawals = new HashMap<>();

    public static CommonsData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.type(DATA_NAME));
    }

    boolean addPolicy(CommonsPolicy policy) {
        if (policies.putIfAbsent(policy.id(), policy) != null) return false;
        setDirty();
        return true;
    }

    @Nullable public CommonsPolicy policy(UUID id) { return policies.get(id); }
    @Nullable public CommonsWithdrawal withdrawal(UUID id) { return withdrawals.get(id); }

    int usedToday(UUID policyId, SubjectRef member, long now) {
        long day = Math.floorDiv(now, 24_000L);
        return withdrawals.values().stream().filter(value -> value.policyId().equals(policyId)
                && value.member().equals(member) && value.day() == day)
                .mapToInt(CommonsWithdrawal::quantity).sum();
    }

    boolean record(CommonsWithdrawal value) {
        if (withdrawals.putIfAbsent(value.id(), value) != null) return false;
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag policyTags = new ListTag();
        policies.values().forEach(value -> policyTags.add(value.save()));
        tag.put("Policies", policyTags);
        ListTag withdrawalTags = new ListTag();
        withdrawals.values().forEach(value -> withdrawalTags.add(value.save()));
        tag.put("Withdrawals", withdrawalTags);
        return tag;
    }

    private static CommonsData load(CompoundTag tag, HolderLookup.Provider registries) {
        CommonsData data = new CommonsData();
        ListTag policies = MinecraftApi.listOfType(tag, "Policies", Tag.TAG_COMPOUND);
        for (int index = 0; index < policies.size(); index++) {
            CommonsPolicy value = CommonsPolicy.load(policies.getCompoundOrEmpty(index));
            data.policies.put(value.id(), value);
        }
        ListTag withdrawals = MinecraftApi.listOfType(tag, "Withdrawals", Tag.TAG_COMPOUND);
        for (int index = 0; index < withdrawals.size(); index++) {
            CommonsWithdrawal value = CommonsWithdrawal.load(withdrawals.getCompoundOrEmpty(index));
            data.withdrawals.put(value.id(), value);
        }
        return data;
    }
}
