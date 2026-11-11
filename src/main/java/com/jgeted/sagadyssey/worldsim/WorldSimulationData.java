package com.jgeted.sagadyssey.worldsim;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;

import java.util.EnumSet;
import java.util.Set;

/** Persisted, per-world rollout state for the v2 migration. */
public final class WorldSimulationData extends NbtSavedData {
    public static final String DATA_NAME = "sagadyssey_world_simulation_v2";
    public static final Factory<WorldSimulationData> FACTORY =
            new Factory<>(WorldSimulationData::new, WorldSimulationData::load);

    private int sourceSchemaVersion = WorldSimulationSchema.LEGACY_VERSION;
    private final WorldSimulationFeatureSet features = new WorldSimulationFeatureSet();
    private String lastDryRunDigest = "";
    private long lastDryRunGameTime = -1L;
    private int lastDryRunIssueCount;

    public static WorldSimulationData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.type(DATA_NAME));
    }

    public int sourceSchemaVersion() { return sourceSchemaVersion; }
    public Set<WorldSimulationFeature> enabledFeatures() { return features.snapshot(); }
    public boolean isEnabled(WorldSimulationFeature feature) { return features.isEnabled(feature); }
    public String lastDryRunDigest() { return lastDryRunDigest; }
    public long lastDryRunGameTime() { return lastDryRunGameTime; }
    public int lastDryRunIssueCount() { return lastDryRunIssueCount; }

    public WorldSimulationFeatureSet.FeatureChange enable(WorldSimulationFeature feature) {
        WorldSimulationFeatureSet.FeatureChange change = features.enable(feature);
        if (change.applied()) setDirty();
        return change;
    }

    public WorldSimulationFeatureSet.FeatureChange disable(WorldSimulationFeature feature) {
        WorldSimulationFeatureSet.FeatureChange change = features.disable(feature);
        if (change.applied()) setDirty();
        return change;
    }

    public void recordDryRun(MigrationBaselineReport report, long gameTime) {
        lastDryRunDigest = report.digest();
        lastDryRunGameTime = gameTime;
        lastDryRunIssueCount = report.issues().size();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("SourceSchemaVersion", sourceSchemaVersion);
        tag.putInt("TargetSchemaVersion", WorldSimulationSchema.CURRENT_VERSION);
        ListTag featureTags = new ListTag();
        features.snapshot().stream().map(WorldSimulationFeature::key).sorted()
                .forEach(key -> featureTags.add(StringTag.valueOf(key)));
        tag.put("EnabledFeatures", featureTags);
        tag.putString("LastDryRunDigest", lastDryRunDigest);
        tag.putLong("LastDryRunGameTime", lastDryRunGameTime);
        tag.putInt("LastDryRunIssueCount", lastDryRunIssueCount);
        return tag;
    }

    private static WorldSimulationData load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldSimulationData data = new WorldSimulationData();
        data.sourceSchemaVersion = tag.contains("SourceSchemaVersion")
                ? tag.getIntOr("SourceSchemaVersion", 0) : WorldSimulationSchema.LEGACY_VERSION;
        EnumSet<WorldSimulationFeature> loaded = EnumSet.noneOf(WorldSimulationFeature.class);
        ListTag featureTags = MinecraftApi.listOfType(tag, "EnabledFeatures", Tag.TAG_STRING);
        for (int index = 0; index < featureTags.size(); index++) {
            WorldSimulationFeature feature = WorldSimulationFeature.byKey(featureTags.getStringOr(index, ""));
            if (feature != null) loaded.add(feature);
        }
        for (WorldSimulationFeature feature : WorldSimulationFeature.values()) {
            if (!loaded.contains(feature)) continue;
            data.features.enable(feature);
        }
        data.lastDryRunDigest = tag.getStringOr("LastDryRunDigest", "");
        data.lastDryRunGameTime = tag.contains("LastDryRunGameTime")
                ? tag.getLongOr("LastDryRunGameTime", 0L) : -1L;
        data.lastDryRunIssueCount = tag.getIntOr("LastDryRunIssueCount", 0);
        return data;
    }
}
