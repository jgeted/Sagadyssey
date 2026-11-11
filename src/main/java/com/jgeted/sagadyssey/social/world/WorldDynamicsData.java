package com.jgeted.sagadyssey.social.world;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 当前活跃事件及调度时钟。 */
public final class WorldDynamicsData extends NbtSavedData {
    private static final int MAX_ACTIVE_EVENTS = 32;
    private static final String DATA_NAME = "sagadyssey_world_dynamics";
    private static final Factory<WorldDynamicsData> FACTORY =
            new Factory<>(WorldDynamicsData::new, WorldDynamicsData::load);

    private long lastProcessedDay = -1L;
    private long lastRollDay = -3L;
    private boolean monotonicClock;
    private long dayOffset;
    private final List<DynamicWorldEventRecord> activeEvents = new ArrayList<>();

    public static WorldDynamicsData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.type(DATA_NAME));
    }

    public boolean beginDay(long day) {
        if (day <= lastProcessedDay) return false;
        lastProcessedDay = day;
        setDirty();
        return true;
    }
    /** One-time clock migration preserves old event dates; /time set cannot replay a day. */
    public long simulationDay(long gameTime,long displayedTime) {
        if(gameTime<0||displayedTime<0)throw new IllegalArgumentException("invalid world clock");
        if(!monotonicClock){dayOffset=Math.max(lastProcessedDay,displayedTime/24000)-gameTime/24000;monotonicClock=true;setDirty();}
        return gameTime/24000+dayOffset;
    }

    public boolean canRoll(long day) { return day - lastRollDay >= 3L; }
    public void markRolled(long day) { lastRollDay = day; setDirty(); }
    public List<DynamicWorldEventRecord> activeEvents() { return List.copyOf(activeEvents); }

    @Nullable
    public DynamicWorldEventRecord start(MinecraftServer server, DynamicWorldEventType type,
                                         @Nullable UUID settlementId, String factionId,
                                         long day, int durationDays, int severity) {
        DynamicWorldEventRecord existing = activeEvents.stream()
                .filter(event -> event.activeOn(day) && event.type() == type)
                .filter(event -> java.util.Objects.equals(event.settlementId(), settlementId))
                .findFirst().orElse(null);
        if (existing != null) return null;
        if (activeEvents.size() >= MAX_ACTIVE_EVENTS) {
            activeEvents.removeIf(event -> !event.activeOn(day));
            if (activeEvents.size() >= MAX_ACTIVE_EVENTS) return null;
        }
        DynamicWorldEventRecord record = new DynamicWorldEventRecord(UUID.randomUUID(), type,
                settlementId, factionId == null ? "" : factionId, day,
                day + Math.max(1, durationDays) - 1L, Math.max(1, Math.min(5, severity)));
        activeEvents.add(record);
        lastRollDay = day;
        setDirty();
        WorldHistoryData.get(server).append(server.overworld().getGameTime(), "world_event_started",
                settlementId, type.displayName(), record.factionId(), 45 + record.severity() * 8);
        return record;
    }

    public void expire(MinecraftServer server, long day) {
        List<DynamicWorldEventRecord> expired = activeEvents.stream().filter(event -> !event.activeOn(day)).toList();
        if (expired.isEmpty()) return;
        activeEvents.removeAll(expired);
        for (DynamicWorldEventRecord event : expired) {
            WorldHistoryData.get(server).append(server.overworld().getGameTime(), "world_event_ended",
                    event.settlementId(), event.type().displayName(), event.factionId(), 20);
        }
        setDirty();
    }

    public int foodMultiplier(UUID settlementId) {
        return activeEvents.stream().anyMatch(event -> event.type() == DynamicWorldEventType.FAMINE
                && (event.settlementId() == null || event.settlementId().equals(settlementId))) ? 2 : 1;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("LastProcessedDay", lastProcessedDay);
        tag.putLong("LastRollDay", lastRollDay);
        tag.putBoolean("MonotonicClock",monotonicClock);tag.putLong("DayOffset",dayOffset);
        ListTag list = new ListTag();
        activeEvents.forEach(event -> list.add(event.save()));
        tag.put("ActiveEvents", list);
        return tag;
    }

    private static WorldDynamicsData load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldDynamicsData data = new WorldDynamicsData();
        data.lastProcessedDay = tag.contains("LastProcessedDay") ? tag.getLongOr("LastProcessedDay", 0L) : -1L;
        data.lastRollDay = tag.contains("LastRollDay") ? tag.getLongOr("LastRollDay", 0L) : -3L;
        data.monotonicClock=tag.getBooleanOr("MonotonicClock", false);data.dayOffset=tag.getLongOr("DayOffset", 0L);
        ListTag list = MinecraftApi.listOfType(tag, "ActiveEvents", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) data.activeEvents.add(DynamicWorldEventRecord.load(list.getCompoundOrEmpty(i)));
        return data;
    }
}
