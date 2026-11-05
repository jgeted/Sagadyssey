package com.jgeted.sagadyssey.npc.brain.plan;

import java.util.EnumSet;
import java.util.Set;

/** 计划生命周期；转换表刻意保持严格，防止执行层跳过预留或结算。 */
public enum NpcPlanStatus {
    SELECTED,
    RESERVING,
    TRAVELLING,
    ACTING,
    SETTLING,
    BLOCKED,
    WAITING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    private static final Set<NpcPlanStatus> TERMINAL = EnumSet.of(
            SUCCEEDED, FAILED, CANCELLED);

    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    public boolean canTransitionTo(NpcPlanStatus next) {
        if (next == null || terminal() || this == next) return false;
        if (next == FAILED || next == CANCELLED) return true;
        return switch (this) {
            case SELECTED -> next == RESERVING || next == TRAVELLING
                    || next == ACTING || next == SETTLING;
            case RESERVING -> next == TRAVELLING || next == ACTING
                    || next == SETTLING || next == BLOCKED || next == SUCCEEDED;
            case TRAVELLING -> next == ACTING || next == SETTLING
                    || next == BLOCKED || next == SUCCEEDED;
            case ACTING -> next == SETTLING || next == BLOCKED || next == SUCCEEDED;
            case SETTLING -> next == SUCCEEDED || next == BLOCKED;
            case BLOCKED -> next == WAITING;
            case WAITING -> next == BLOCKED || next == RESERVING || next == TRAVELLING
                    || next == ACTING || next == SETTLING;
            case SUCCEEDED, FAILED, CANCELLED -> false;
        };
    }

    public void requireTransitionTo(NpcPlanStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException("illegal plan transition: " + this + " -> " + next);
        }
    }
}
