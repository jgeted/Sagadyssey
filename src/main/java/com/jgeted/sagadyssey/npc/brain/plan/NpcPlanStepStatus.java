package com.jgeted.sagadyssey.npc.brain.plan;

/** 单个执行步骤的状态；步骤终结后不可重新启动。 */
public enum NpcPlanStepStatus {
    PENDING,
    RUNNING,
    BLOCKED,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean canTransitionTo(NpcPlanStepStatus next) {
        if (next == null || terminal() || this == next) return false;
        return switch (this) {
            case PENDING -> next == RUNNING || next == BLOCKED
                    || next == FAILED || next == CANCELLED;
            case RUNNING -> next == BLOCKED || next == SUCCEEDED
                    || next == FAILED || next == CANCELLED;
            case BLOCKED -> next == PENDING || next == RUNNING
                    || next == FAILED || next == CANCELLED;
            case SUCCEEDED, FAILED, CANCELLED -> false;
        };
    }

    public void requireTransitionTo(NpcPlanStepStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException("illegal plan step transition: " + this + " -> " + next);
        }
    }
}
