package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanBlocker;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * 单个计划步骤的一次执行结果。
 *
 * <p>受阻结果必须说明结构化阻塞原因；其他状态不得残留阻塞信息。</p>
 */
public record TaskTickResult(State state, String detail, @Nullable NpcPlanBlocker blocker) {
    public TaskTickResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) throw new IllegalArgumentException("detail must not be blank");
        boolean hasBlocker = blocker != null;
        if ((state == State.BLOCKED) != hasBlocker) {
            throw new IllegalArgumentException("Only BLOCKED results may carry a blocker");
        }
    }

    public static TaskTickResult running(String detail) {
        return new TaskTickResult(State.RUNNING, detail, null);
    }

    public static TaskTickResult stepSucceeded(String detail) {
        return new TaskTickResult(State.STEP_SUCCEEDED, detail, null);
    }

    public static TaskTickResult blocked(String detail, NpcPlanBlocker blocker) {
        return new TaskTickResult(State.BLOCKED, detail, Objects.requireNonNull(blocker, "blocker"));
    }

    public static TaskTickResult failed(String detail) {
        return new TaskTickResult(State.FAILED, detail, null);
    }

    public enum State {
        RUNNING,
        STEP_SUCCEEDED,
        BLOCKED,
        FAILED
    }
}
