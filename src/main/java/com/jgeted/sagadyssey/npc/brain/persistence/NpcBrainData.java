package com.jgeted.sagadyssey.npc.brain.persistence;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStore;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 世界级 NPC 计划持久化容器；按 NPC 保证至多一个活动计划。 */
public final class NpcBrainData extends SavedData implements NpcPlanStore {
    public static final String DATA_NAME = "sagadyssey_npc_brain_v3";
    public static final int SCHEMA_VERSION = 1;
    public static final Factory<NpcBrainData> FACTORY =
            new Factory<>(NpcBrainData::new, NpcBrainData::load);

    private static final int HISTORY_LIMIT = 32;

    /** 只保存非默认值，未知 NPC 自然回退为 LEGACY。 */
    private final Map<UUID, NpcBehaviorMode> modes = new HashMap<>();
    private final Map<UUID, NpcPlan> activePlans = new HashMap<>();
    private final Map<UUID, Deque<NpcPlan>> histories = new HashMap<>();

    public static NpcBrainData get(MinecraftServer server) {
        if (server == null) throw new IllegalArgumentException("server is required");
        return com.jgeted.sagadyssey.worldsim.PersistentDataAccess.getOrCreate(server, FACTORY, DATA_NAME);
    }

    public synchronized NpcBehaviorMode mode(UUID npcId) {
        requireNpcId(npcId);
        return modes.getOrDefault(npcId, NpcBehaviorMode.LEGACY);
    }

    public synchronized boolean isPlanned(UUID npcId) {
        return mode(npcId) == NpcBehaviorMode.PLANNED;
    }

    public synchronized void setMode(UUID npcId, NpcBehaviorMode mode) {
        requireNpcId(npcId);
        if (mode == null) throw new IllegalArgumentException("mode is required");
        NpcBehaviorMode previous = mode(npcId);
        if (mode == NpcBehaviorMode.LEGACY) {
            modes.remove(npcId);
        } else {
            modes.put(npcId, mode);
        }
        if (previous != mode) setDirty();
    }

    @Override
    public synchronized Optional<NpcPlan> active(UUID npcId) {
        requireNpcId(npcId);
        return Optional.ofNullable(activePlans.get(npcId));
    }

    @Override
    public synchronized List<NpcPlan> history(UUID npcId) {
        requireNpcId(npcId);
        Deque<NpcPlan> history = histories.get(npcId);
        return history == null ? List.of() : List.copyOf(new ArrayList<>(history));
    }

    @Override
    public synchronized void save(NpcPlan plan) {
        if (plan == null) throw new IllegalArgumentException("plan is required");
        NpcPlan active = activePlans.get(plan.npcId());
        if (!plan.terminal()) {
            if (active != null && !active.id().equals(plan.id())) {
                throw new IllegalStateException("npc already has another active plan");
            }
            validateUpdate(active, plan);
            activePlans.put(plan.npcId(), plan);
            setDirty();
            return;
        }
        if (active == null || !active.id().equals(plan.id())) {
            throw new IllegalStateException("terminal snapshot does not match the active plan");
        }
        validateUpdate(active, plan);
        activePlans.remove(plan.npcId());
        Deque<NpcPlan> history = histories.computeIfAbsent(
                plan.npcId(), ignored -> new ArrayDeque<>());
        history.addFirst(plan);
        while (history.size() > HISTORY_LIMIT) history.removeLast();
        setDirty();
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Schema", SCHEMA_VERSION);
        ListTag brains = new ListTag();
        for (UUID npcId : allNpcIds()) {
            CompoundTag brain = new CompoundTag();
            brain.putUUID("NpcId", npcId);
            brain.putString("Mode", mode(npcId).name());

            NpcPlan active = activePlans.get(npcId);
            if (active != null) brain.put("Active", NpcPlanNbtCodec.save(active));

            ListTag historyTags = new ListTag();
            Deque<NpcPlan> history = histories.get(npcId);
            if (history != null) {
                int count = 0;
                for (NpcPlan plan : history) {
                    if (count++ >= HISTORY_LIMIT) break;
                    historyTags.add(NpcPlanNbtCodec.save(plan));
                }
            }
            brain.put("History", historyTags);
            brains.add(brain);
        }
        tag.put("Brains", brains);
        return tag;
    }

    static NpcBrainData load(CompoundTag tag, HolderLookup.Provider registries) {
        NpcBrainData data = new NpcBrainData();
        if (tag == null || !tag.contains("Schema", Tag.TAG_INT)
                || tag.getInt("Schema") != SCHEMA_VERSION
                || !tag.contains("Brains", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Invalid or unsupported NPC brain schema; recovery required");
        }

        ListTag brains = com.jgeted.sagadyssey.worldsim.PersistentDataAccess.compounds(tag, "Brains");
        Set<UUID> loadedNpcIds = new LinkedHashSet<>();
        for (int index = 0; index < brains.size(); index++) {
            CompoundTag brain = brains.getCompound(index);
            if (!brain.hasUUID("NpcId"))
                throw new IllegalArgumentException("Missing NPC identity at brain record " + index);
            UUID npcId = brain.getUUID("NpcId");
            if (!loadedNpcIds.add(npcId))
                throw new IllegalArgumentException("Duplicate NPC brain identity: " + npcId);

            loadMode(data, npcId, brain);
            loadActive(data, npcId, brain);
            loadHistory(data, npcId, brain);
        }
        return data;
    }

    private static void loadMode(NpcBrainData data, UUID npcId, CompoundTag brain) {
        // A missing mode is the documented legacy default; an unknown value is not.
        if (!brain.contains("Mode")) return;
        if (!brain.contains("Mode", Tag.TAG_STRING))
            throw new IllegalArgumentException("Invalid NPC mode: " + npcId);
        NpcBehaviorMode mode = NpcBehaviorMode.valueOf(brain.getString("Mode"));
        if (mode != NpcBehaviorMode.LEGACY) data.modes.put(npcId, mode);
    }

    private static void loadActive(NpcBrainData data, UUID npcId, CompoundTag brain) {
        if (!brain.contains("Active")) return;
        if (!brain.contains("Active", Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Invalid active plan tag: " + npcId);
        NpcPlan plan = NpcPlanNbtCodec.load(brain.getCompound("Active"));
        if (!plan.npcId().equals(npcId) || plan.terminal())
            throw new IllegalArgumentException("Invalid active plan ownership or status: " + npcId);
        data.activePlans.put(npcId, plan);
    }

    private static void loadHistory(NpcBrainData data, UUID npcId, CompoundTag brain) {
        if (!brain.contains("History")) return;
        ListTag historyTags = com.jgeted.sagadyssey.worldsim.PersistentDataAccess.compounds(brain, "History");
        Deque<NpcPlan> history = new ArrayDeque<>();
        Set<UUID> planIds = new LinkedHashSet<>();
        NpcPlan active = data.activePlans.get(npcId);
        if (active != null) planIds.add(active.id());
        for (int index = 0; index < historyTags.size(); index++) {
            NpcPlan plan = NpcPlanNbtCodec.load(historyTags.getCompound(index));
            if (!plan.npcId().equals(npcId) || !plan.terminal() || !planIds.add(plan.id()))
                throw new IllegalArgumentException("Invalid NPC history: " + npcId + " at " + index);
            if (history.size() < HISTORY_LIMIT) history.addLast(plan);
        }
        if (!history.isEmpty()) data.histories.put(npcId, history);
    }

    private Set<UUID> allNpcIds() {
        Set<UUID> ids = new LinkedHashSet<>();
        ids.addAll(modes.keySet());
        ids.addAll(activePlans.keySet());
        ids.addAll(histories.keySet());
        return ids;
    }

    private static void validateUpdate(NpcPlan previous, NpcPlan next) {
        if (previous == null) {
            if (next.status() != NpcPlanStatus.SELECTED) {
                throw new IllegalStateException("a new active plan must start as SELECTED");
            }
            return;
        }
        if (next.updatedAt() < previous.updatedAt()) {
            throw new IllegalStateException("plan snapshot time cannot move backwards");
        }
        if (previous.status() != next.status()
                && !previous.status().canTransitionTo(next.status())) {
            throw new IllegalStateException("stored plan status transition is illegal");
        }
        if (!previous.candidateKey().equals(next.candidateKey())
                || !previous.providerId().equals(next.providerId())
                || previous.kind() != next.kind()
                || previous.priority() != next.priority()
                || previous.createdAt() != next.createdAt()) {
            throw new IllegalStateException("plan identity fields cannot change");
        }
        if (previous.commitmentUntil() != next.commitmentUntil()
                || previous.expiresAt() != next.expiresAt()
                || previous.steps().size() != next.steps().size()) {
            throw new IllegalStateException("plan deadlines and step count cannot change");
        }
        if (next.currentStepIndex() < previous.currentStepIndex()
                || next.currentStepIndex() > previous.currentStepIndex() + 1) {
            throw new IllegalStateException("current step cannot move backwards or skip steps");
        }
        for (int index = 0; index < previous.steps().size(); index++) {
            NpcPlanStep oldStep = previous.steps().get(index);
            NpcPlanStep newStep = next.steps().get(index);
            if (oldStep.phase() != newStep.phase()
                    || !oldStep.executorId().equals(newStep.executorId())
                    || !oldStep.params().equals(newStep.params())) {
                throw new IllegalStateException("plan step identity cannot change");
            }
            if (oldStep.status() != newStep.status()
                    && !oldStep.status().canTransitionTo(newStep.status())) {
                throw new IllegalStateException("stored step status transition is illegal");
            }
            if (newStep.updatedAt() < oldStep.updatedAt()) {
                throw new IllegalStateException("step time cannot move backwards");
            }
        }
    }

    private static void requireNpcId(UUID npcId) {
        if (npcId == null) throw new IllegalArgumentException("npcId is required");
    }
}
