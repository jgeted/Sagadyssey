package com.jgeted.sagadyssey.social.world;

import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** 世界唯一的开局场景；首次创建后固定，不因重启或数据包重载重新抽取。 */
public final class WorldScenarioData extends SavedData {
    private static final String DATA_NAME = "sagadyssey_world_scenario";
    private static final Factory<WorldScenarioData> FACTORY =
            new Factory<>(WorldScenarioData::new, WorldScenarioData::load);

    private boolean initialized;
    private WorldScenarioType scenario = WorldScenarioType.PEACEFUL_REIGN;
    private long establishedGameTime;

    public static WorldScenarioData get(MinecraftServer server) {
        WorldScenarioData data = server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
        data.ensureInitialized(server);
        return data;
    }

    public WorldScenarioType scenario() { return scenario; }
    public long establishedGameTime() { return establishedGameTime; }

    private void ensureInitialized(MinecraftServer server) {
        if (initialized) return;
        initialized = true;
        scenario = WorldScenarioType.fromSeed(server.overworld().getSeed());
        establishedGameTime = server.overworld().getGameTime();
        setDirty();
        WorldHistoryData.get(server).append(establishedGameTime, "world_scenario", null,
                scenario.id(), "", 80);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("Initialized", initialized);
        tag.putString("Scenario", scenario.name());
        tag.putLong("Established", establishedGameTime);
        return tag;
    }

    private static WorldScenarioData load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldScenarioData data = new WorldScenarioData();
        data.initialized = tag.getBoolean("Initialized");
        data.scenario = WorldScenarioType.byName(tag.getString("Scenario"));
        data.establishedGameTime = tag.getLong("Established");
        return data;
    }
}
