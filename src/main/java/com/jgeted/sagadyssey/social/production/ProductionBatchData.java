package com.jgeted.sagadyssey.social.production;

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

public final class ProductionBatchData extends NbtSavedData {
    private static final String DATA_NAME = "sagadyssey_production_batches_v2";
    private static final int MAX_RETAINED = 1024;
    private static final Factory<ProductionBatchData> FACTORY =
            new Factory<>(ProductionBatchData::new, ProductionBatchData::load);
    private final Map<UUID, ProductionBatch> batches = new HashMap<>();

    public static ProductionBatchData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.type(DATA_NAME));
    }

    public void put(ProductionBatch batch) {
        batches.put(batch.id(), batch);
        prune();
        setDirty();
    }

    @Nullable public ProductionBatch batch(UUID id) { return batches.get(id); }
    public List<ProductionBatch> batches() { return List.copyOf(batches.values()); }

    private void prune() {
        int excess = batches.size() - MAX_RETAINED;
        if (excess <= 0) return;
        batches.values().stream().filter(batch -> batch.status().terminal())
                .sorted(Comparator.comparingLong(ProductionBatch::finishedAt)).limit(excess)
                .map(ProductionBatch::id).toList().forEach(batches::remove);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        batches.values().forEach(batch -> list.add(batch.save()));
        tag.put("Batches", list);
        return tag;
    }

    private static ProductionBatchData load(CompoundTag tag, HolderLookup.Provider registries) {
        ProductionBatchData data = new ProductionBatchData();
        ListTag list = MinecraftApi.listOfType(tag, "Batches", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            try {
                ProductionBatch batch = ProductionBatch.load(list.getCompoundOrEmpty(index));
                data.batches.put(batch.id(), batch);
            } catch (RuntimeException ignored) { }
        }
        return data;
    }
}
