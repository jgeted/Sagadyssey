package com.jgeted.sagadyssey.npc.brain.plan;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * NPC 已经承诺执行的具体计划。
 *
 * <p>对象本身不可变；所有状态变化都返回新快照，以便之后写入审计历史。</p>
 */
public record NpcPlan(UUID id, UUID npcId, String candidateKey, ResourceLocation providerId,
                      NpcPlanKind kind, NpcPlanPriority priority, NpcPlanStatus status,
                      List<NpcPlanStep> steps, int currentStepIndex, long createdAt,
                      long updatedAt, long commitmentUntil, long expiresAt,
                      @Nullable NpcPlanBlocker blocker, String explanation) {
    public NpcPlan {
        if (id == null || npcId == null) throw new IllegalArgumentException("plan ids are required");
        if (candidateKey == null || candidateKey.isBlank()) {
            throw new IllegalArgumentException("candidateKey is required");
        }
        if (providerId == null || kind == null || priority == null || status == null) {
            throw new IllegalArgumentException("plan classification is required");
        }
        steps = List.copyOf(steps == null ? List.of() : steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("plan requires at least one step");
        if (currentStepIndex < 0 || currentStepIndex >= steps.size()) {
            throw new IllegalArgumentException("currentStepIndex is outside the step list");
        }
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).index() != index) {
                throw new IllegalArgumentException("step indexes must be contiguous");
            }
            if (index < currentStepIndex && steps.get(index).status() != NpcPlanStepStatus.SUCCEEDED) {
                throw new IllegalArgumentException("steps before the current step must have succeeded");
            }
            if (index > 0 && !steps.get(index).phase().canFollow(steps.get(index - 1).phase())) {
                throw new IllegalArgumentException("step phases cannot move backwards");
            }
        }
        if (createdAt < 0L || updatedAt < createdAt) {
            throw new IllegalArgumentException("invalid plan timestamps");
        }
        if (commitmentUntil < 0L || expiresAt < 0L) {
            throw new IllegalArgumentException("plan deadlines cannot be negative");
        }
        if (commitmentUntil != 0L && commitmentUntil < createdAt) {
            throw new IllegalArgumentException("commitment cannot end before plan creation");
        }
        if (expiresAt != 0L && expiresAt < createdAt) {
            throw new IllegalArgumentException("plan cannot expire before creation");
        }
        if (commitmentUntil != 0L && expiresAt != 0L && commitmentUntil > expiresAt) {
            throw new IllegalArgumentException("commitment cannot outlive plan expiry");
        }
        if (status == NpcPlanStatus.BLOCKED && blocker == null) {
            throw new IllegalArgumentException("blocked plan requires a blocker");
        }
        if (status != NpcPlanStatus.BLOCKED && status != NpcPlanStatus.WAITING && blocker != null) {
            throw new IllegalArgumentException("only blocked or waiting plans may retain a blocker");
        }
        if (status.terminal() && steps.stream().anyMatch(step -> !step.status().terminal())) {
            throw new IllegalArgumentException("terminal plan cannot retain unfinished steps");
        }
        if (status == NpcPlanStatus.SUCCEEDED
                && steps.stream().anyMatch(step -> step.status() != NpcPlanStepStatus.SUCCEEDED)) {
            throw new IllegalArgumentException("successful plan requires every step to succeed");
        }
        explanation = explanation == null ? "" : explanation;
    }

    public boolean terminal() {
        return status.terminal();
    }

    public NpcPlanStep currentStep() {
        return steps.get(currentStepIndex);
    }

    public NpcPlan transition(NpcPlanStatus next, long now, String nextExplanation) {
        return transition(next, now, null, nextExplanation);
    }

    public NpcPlan transition(NpcPlanStatus next, long now,
                              @Nullable NpcPlanBlocker nextBlocker, String nextExplanation) {
        status.requireTransitionTo(next);
        if (now < updatedAt) throw new IllegalArgumentException("plan time cannot move backwards");
        if (next == NpcPlanStatus.BLOCKED && nextBlocker == null) {
            throw new IllegalArgumentException("blocked plan requires a blocker");
        }
        if (next != NpcPlanStatus.BLOCKED && next != NpcPlanStatus.WAITING
                && nextBlocker != null) {
            throw new IllegalArgumentException("blocker must be cleared when resuming a plan");
        }
        if (next == NpcPlanStatus.SUCCEEDED
                && steps.stream().anyMatch(step -> step.status() != NpcPlanStepStatus.SUCCEEDED)) {
            throw new IllegalStateException("plan cannot succeed before every step succeeds");
        }
        List<NpcPlanStep> nextSteps = switch (next) {
            case FAILED -> failUnfinishedSteps(now, nextExplanation);
            case CANCELLED -> cancelUnfinishedSteps(now, nextExplanation);
            default -> steps;
        };
        return new NpcPlan(id, npcId, candidateKey, providerId, kind, priority, next,
                nextSteps, currentStepIndex, createdAt, now, commitmentUntil, expiresAt,
                nextBlocker, nextExplanation);
    }

    public NpcPlan replaceCurrentStep(NpcPlanStep replacement) {
        if (replacement == null || replacement.index() != currentStepIndex) {
            throw new IllegalArgumentException("replacement must target the current step");
        }
        NpcPlanStep current = currentStep();
        if (current.phase() != replacement.phase()
                || !current.executorId().equals(replacement.executorId())
                || !current.params().equals(replacement.params())) {
            throw new IllegalArgumentException("step identity cannot change during replacement");
        }
        if (replacement.updatedAt() < updatedAt) {
            throw new IllegalArgumentException("replacement time cannot move backwards");
        }
        List<NpcPlanStep> replaced = new ArrayList<>(steps);
        replaced.set(currentStepIndex, replacement);
        return new NpcPlan(id, npcId, candidateKey, providerId, kind, priority, status,
                replaced, currentStepIndex, createdAt, replacement.updatedAt(), commitmentUntil,
                expiresAt, blocker, explanation);
    }

    public NpcPlan advanceStep(long now, String nextExplanation) {
        if (terminal()) throw new IllegalStateException("terminal plan cannot advance");
        if (currentStep().status() != NpcPlanStepStatus.SUCCEEDED) {
            throw new IllegalStateException("current step has not succeeded");
        }
        if (currentStepIndex + 1 >= steps.size()) {
            throw new IllegalStateException("plan has no next step");
        }
        if (now < updatedAt) throw new IllegalArgumentException("plan time cannot move backwards");
        return new NpcPlan(id, npcId, candidateKey, providerId, kind, priority, status,
                steps, currentStepIndex + 1, createdAt, now, commitmentUntil, expiresAt,
                blocker, nextExplanation);
    }

    private List<NpcPlanStep> cancelUnfinishedSteps(long now, String detail) {
        List<NpcPlanStep> closed = new ArrayList<>(steps.size());
        for (NpcPlanStep step : steps) {
            closed.add(step.status().terminal()
                    ? step : step.transition(NpcPlanStepStatus.CANCELLED, now, detail));
        }
        return List.copyOf(closed);
    }

    private List<NpcPlanStep> failUnfinishedSteps(long now, String detail) {
        List<NpcPlanStep> closed = new ArrayList<>(steps.size());
        boolean failureRecorded = false;
        for (NpcPlanStep step : steps) {
            if (step.status().terminal()) {
                closed.add(step);
                if (step.status() == NpcPlanStepStatus.FAILED) failureRecorded = true;
            } else if (!failureRecorded && step.index() >= currentStepIndex) {
                closed.add(step.transition(NpcPlanStepStatus.FAILED, now, detail));
                failureRecorded = true;
            } else {
                closed.add(step.transition(NpcPlanStepStatus.CANCELLED, now, detail));
            }
        }
        return List.copyOf(closed);
    }
}
