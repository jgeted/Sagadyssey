package com.jgeted.sagadyssey.social.market;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AppraisalData extends NbtSavedData {
    private static final String DATA_NAME = "sagadyssey_market_appraisals";
    private static final Factory<AppraisalData> FACTORY =
            new Factory<>(AppraisalData::new, AppraisalData::load);
    private final Map<UUID, AppraisalRecord> records = new HashMap<>();

    public static AppraisalData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.type(DATA_NAME));
    }

    public void put(AppraisalRecord record) {
        records.put(record.markId(), record);
        setDirty();
    }

    @Nullable public AppraisalRecord byId(UUID id) { return records.get(id); }

    @Nullable
    public AppraisalRecord latest(UUID containerId, int slot, long now) {
        return records.values().stream().filter(value -> value.containerId().equals(containerId)
                        && value.slot() == slot && now < value.expiresAt())
                .max(Comparator.comparingLong(AppraisalRecord::issuedAt)).orElse(null);
    }

    public List<AppraisalRecord> records() { return List.copyOf(records.values()); }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        records.values().forEach(value -> list.add(value.save()));
        tag.put("Appraisals", list);
        return tag;
    }

    static AppraisalData load(CompoundTag tag, HolderLookup.Provider registries) {
        AppraisalData data = new AppraisalData();
        ListTag list = MinecraftApi.listOfType(tag, "Appraisals", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            try {
                AppraisalRecord record = AppraisalRecord.load(list.getCompoundOrEmpty(index));
                data.records.put(record.markId(), record);
            } catch (RuntimeException ignored) { }
        }
        return data;
    }
}
