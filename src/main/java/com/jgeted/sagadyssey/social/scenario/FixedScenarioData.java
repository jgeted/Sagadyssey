package com.jgeted.sagadyssey.social.scenario;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 固定世界部署任务和完成标记。 */
public final class FixedScenarioData extends NbtSavedData {
    private static final String DATA_NAME = "sagadyssey_fixed_scenarios";
    private static final Factory<FixedScenarioData> FACTORY =
            new Factory<>(FixedScenarioData::new, FixedScenarioData::load);
    private static final int MAX_DEPLOYMENTS = 16;

    private final Map<UUID, FixedScenarioDeployment> deployments = new HashMap<>();

    public static FixedScenarioData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.type(DATA_NAME));
    }

    public List<FixedScenarioDeployment> deployments() {
        return deployments.values().stream()
                .sorted(Comparator.comparingLong(FixedScenarioDeployment::startedGameTime).reversed()).toList();
    }

    @Nullable
    public FixedScenarioDeployment activeIn(String dimension) {
        return deployments.values().stream().filter(FixedScenarioDeployment::active)
                .filter(deployment -> deployment.dimension().equals(dimension)).findFirst().orElse(null);
    }

    @Nullable
    public FixedScenarioDeployment latestIn(String dimension) {
        return deployments().stream()
                .filter(deployment -> deployment.dimension().equals(dimension))
                .findFirst().orElse(null);
    }

    @Nullable
    public FixedScenarioDeployment start(String scenarioId, String dimension, BlockPos anchor,
                                         @Nullable UUID requestedBy, long gameTime, boolean force) {
        return start(scenarioId, dimension, anchor, requestedBy, gameTime, force, false);
    }

    @Nullable
    public FixedScenarioDeployment start(String scenarioId, String dimension, BlockPos anchor,
                                         @Nullable UUID requestedBy, long gameTime,
                                         boolean force, boolean repair) {
        if (activeIn(dimension) != null) return null;
        FixedScenarioDefinition definition = FixedScenarioDefinition.get(scenarioId);
        if (definition == null) return null;
        UUID id = deploymentId(scenarioId, dimension, anchor);
        FixedScenarioDeployment existing = deployments.get(id);
        if (existing != null && !force) return null;
        int previousRevision = existing == null ? 0 : existing.definitionRevision();
        FixedScenarioDeployment deployment = new FixedScenarioDeployment(id, scenarioId, definition.revision(),
                previousRevision, dimension, anchor, requestedBy, gameTime, repair);
        deployments.put(id, deployment);
        trimOldDeployments();
        setDirty();
        return deployment;
    }

    public boolean cancel(String dimension) {
        FixedScenarioDeployment deployment = activeIn(dimension);
        if (deployment == null) return false;
        deployment.cancel();
        setDirty();
        return true;
    }

    public void changed() { setDirty(); }

    public static UUID deploymentId(String scenarioId, String dimension, BlockPos anchor) {
        String key = scenarioId + "|" + dimension + "|" + anchor.asLong();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public static UUID settlementId(FixedScenarioDeployment deployment, String settlementKey) {
        String key = deployment.id() + "|settlement|" + settlementKey;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public static UUID npcId(FixedScenarioDeployment deployment, int index) {
        String key = deployment.id() + "|npc|" + index;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private void trimOldDeployments() {
        if (deployments.size() <= MAX_DEPLOYMENTS) return;
        deployments.values().stream().filter(deployment -> !deployment.active())
                .sorted(Comparator.comparingLong(FixedScenarioDeployment::startedGameTime))
                .limit(deployments.size() - MAX_DEPLOYMENTS).map(FixedScenarioDeployment::id)
                .toList().forEach(deployments::remove);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        deployments.values().forEach(deployment -> list.add(deployment.save()));
        tag.put("Deployments", list);
        return tag;
    }

    private static FixedScenarioData load(CompoundTag tag, HolderLookup.Provider registries) {
        FixedScenarioData data = new FixedScenarioData();
        ListTag list = MinecraftApi.listOfType(tag, "Deployments", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            FixedScenarioDeployment deployment = FixedScenarioDeployment.load(list.getCompoundOrEmpty(i));
            if (deployment != null && FixedScenarioDefinition.get(deployment.scenarioId()) != null)
                data.deployments.put(deployment.id(), deployment);
        }
        data.trimOldDeployments();
        return data;
    }
}
