package com.jgeted.sagadyssey.social.scenario;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/** 一次可跨重启继续执行的场景部署任务。 */
public final class FixedScenarioDeployment {
    public enum Phase { SETTLEMENTS, TERRAIN, STRUCTURES, WAREHOUSES, NPCS, FINALIZE, COMPLETE, CANCELLED, FAILED }

    private final UUID id;
    private final String scenarioId;
    private final int definitionRevision;
    private final int previousDefinitionRevision;
    private final String dimension;
    private final BlockPos anchor;
    @Nullable private final UUID requestedBy;
    private final long startedGameTime;
    private final boolean repair;
    private Phase phase;
    private int cursor;
    private String error;
    private String testProfile;

    public FixedScenarioDeployment(UUID id, String scenarioId, int definitionRevision,
                                   int previousDefinitionRevision,
                                   String dimension, BlockPos anchor,
                                   @Nullable UUID requestedBy, long startedGameTime, boolean repair) {
        this.id = id;
        this.scenarioId = scenarioId;
        this.definitionRevision = Math.max(0, definitionRevision);
        this.previousDefinitionRevision = Math.max(0, previousDefinitionRevision);
        this.dimension = dimension;
        this.anchor = anchor.immutable();
        this.requestedBy = requestedBy;
        this.startedGameTime = startedGameTime;
        this.repair = repair;
        this.phase = Phase.SETTLEMENTS;
        this.error = "";
        this.testProfile = "";
    }

    public UUID id() { return id; }
    public String scenarioId() { return scenarioId; }
    public int definitionRevision() { return definitionRevision; }
    public int previousDefinitionRevision() { return previousDefinitionRevision; }
    public String dimension() { return dimension; }
    public BlockPos anchor() { return anchor; }
    @Nullable public UUID requestedBy() { return requestedBy; }
    public long startedGameTime() { return startedGameTime; }
    public boolean repair() { return repair; }
    public Phase phase() { return phase; }
    public int cursor() { return cursor; }
    public String error() { return error; }
    public String testProfile() { return testProfile; }
    public boolean active() { return phase.ordinal() <= Phase.FINALIZE.ordinal(); }

    public void advance() { cursor++; }
    public void nextPhase() { phase = Phase.values()[phase.ordinal() + 1]; cursor = 0; }
    public void cancel() { phase = Phase.CANCELLED; error = "由管理员取消"; }
    public void fail(String reason) { phase = Phase.FAILED; error = reason == null ? "未知错误" : reason; }
    public void setTestProfile(String profile) { testProfile = profile == null ? "" : profile; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Scenario", scenarioId);
        tag.putInt("DefinitionRevision", definitionRevision);
        tag.putInt("PreviousDefinitionRevision", previousDefinitionRevision);
        tag.putString("Dimension", dimension);
        tag.putLong("Anchor", anchor.asLong());
        if (requestedBy != null) tag.putUUID("RequestedBy", requestedBy);
        tag.putLong("Started", startedGameTime);
        tag.putBoolean("Repair", repair);
        tag.putString("Phase", phase.name());
        tag.putInt("Cursor", cursor);
        tag.putString("Error", error);
        tag.putString("TestProfile", testProfile);
        return tag;
    }

    @Nullable
    public static FixedScenarioDeployment load(CompoundTag tag) {
        if (!tag.hasUUID("Id") || !tag.contains("Scenario") || !tag.contains("Dimension")) return null;
        FixedScenarioDeployment deployment = new FixedScenarioDeployment(tag.getUUID("Id"),
                tag.getString("Scenario"), tag.getInt("DefinitionRevision"),
                tag.getInt("PreviousDefinitionRevision"), tag.getString("Dimension"),
                BlockPos.of(tag.getLong("Anchor")),
                tag.hasUUID("RequestedBy") ? tag.getUUID("RequestedBy") : null,
                tag.getLong("Started"), tag.getBoolean("Repair"));
        try { deployment.phase = Phase.valueOf(tag.getString("Phase")); }
        catch (IllegalArgumentException ignored) { deployment.phase = Phase.FAILED; }
        deployment.cursor = Math.max(0, tag.getInt("Cursor"));
        deployment.error = tag.getString("Error");
        deployment.testProfile = tag.getString("TestProfile");
        return deployment;
    }
}
