package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;

import javax.annotation.Nullable;

/** 一次计划执行推进的可观察结果。 */
public record NpcPlanExecutionUpdate(Outcome outcome, @Nullable NpcPlan plan, String detail) {
    public NpcPlanExecutionUpdate {
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        detail = detail == null ? "" : detail;
        if (outcome != Outcome.IDLE && plan == null) {
            throw new IllegalArgumentException("non-idle update requires a plan snapshot");
        }
    }

    public static NpcPlanExecutionUpdate idle(String detail) {
        return new NpcPlanExecutionUpdate(Outcome.IDLE, null, detail);
    }

    public enum Outcome {
        IDLE,
        RUNNING,
        WAITING,
        STEP_ADVANCED,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }
}
