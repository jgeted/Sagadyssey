package com.jgeted.sagadyssey.npc.brain.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 阶段一使用的内存计划仓库；历史记录按 NPC 独立限长。 */
public final class InMemoryNpcPlanStore implements NpcPlanStore {
    private static final int DEFAULT_HISTORY_LIMIT = 32;

    private final int historyLimit;
    private final Map<UUID, NpcPlan> activePlans = new HashMap<>();
    private final Map<UUID, Deque<NpcPlan>> histories = new HashMap<>();

    public InMemoryNpcPlanStore() {
        this(DEFAULT_HISTORY_LIMIT);
    }

    public InMemoryNpcPlanStore(int historyLimit) {
        if (historyLimit <= 0) throw new IllegalArgumentException("historyLimit must be positive");
        this.historyLimit = historyLimit;
    }

    @Override
    public synchronized Optional<NpcPlan> active(UUID npcId) {
        if (npcId == null) throw new IllegalArgumentException("npcId is required");
        return Optional.ofNullable(activePlans.get(npcId));
    }

    @Override
    public synchronized List<NpcPlan> history(UUID npcId) {
        if (npcId == null) throw new IllegalArgumentException("npcId is required");
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
            return;
        }
        if (active == null || !active.id().equals(plan.id())) {
            throw new IllegalStateException("terminal snapshot does not match the active plan");
        }
        validateUpdate(active, plan);
        activePlans.remove(plan.npcId());
        Deque<NpcPlan> history = histories.computeIfAbsent(plan.npcId(), ignored -> new ArrayDeque<>());
        history.addFirst(plan);
        while (history.size() > historyLimit) history.removeLast();
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
}
