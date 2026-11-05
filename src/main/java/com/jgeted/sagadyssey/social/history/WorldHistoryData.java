package com.jgeted.sagadyssey.social.history;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 世界编年史。事件有容量上限，淘汰时优先保留高重要度事件。 */
public final class WorldHistoryData extends SavedData {
    private static final String DATA_NAME = "sagadyssey_history";
    private static final int MAX_EVENTS = 2048;
    private static final Factory<WorldHistoryData> FACTORY =
            new Factory<>(WorldHistoryData::new, WorldHistoryData::load);

    private final List<HistoryEventRecord> events = new ArrayList<>();

    public static WorldHistoryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public void append(long gameTime, String type, @Nullable UUID subjectId,
                       String subjectName, String detail, int importance) {
        events.add(new HistoryEventRecord(UUID.randomUUID(), gameTime, type, subjectId,
                clean(subjectName), clean(detail), Math.max(0, Math.min(100, importance))));
        trimIfNeeded();
        setDirty();
    }

    public List<HistoryEventRecord> recent(int limit) {
        return events.stream()
                .sorted(Comparator.comparingLong(HistoryEventRecord::gameTime).reversed())
                .limit(Math.max(0, limit)).toList();
    }

    public List<HistoryEventRecord> notable(int limit) {
        return events.stream()
                .sorted(Comparator.comparingInt(HistoryEventRecord::importance).reversed()
                        .thenComparing(Comparator.comparingLong(HistoryEventRecord::gameTime).reversed()))
                .limit(Math.max(0, limit)).toList();
    }

    private void trimIfNeeded() {
        if (events.size() <= MAX_EVENTS) return;
        events.sort(Comparator.comparingInt(HistoryEventRecord::importance)
                .thenComparingLong(HistoryEventRecord::gameTime));
        events.subList(0, events.size() - MAX_EVENTS).clear();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        events.forEach(event -> list.add(event.save()));
        tag.put("Events", list);
        return tag;
    }

    private static WorldHistoryData load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldHistoryData data = new WorldHistoryData();
        ListTag list = tag.getList("Events", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) data.events.add(HistoryEventRecord.load(list.getCompound(i)));
        data.trimIfNeeded();
        return data;
    }

    private static String clean(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("§[0-9a-fk-or]", "").trim();
        return cleaned.length() > 128 ? cleaned.substring(0, 128) : cleaned;
    }
}
