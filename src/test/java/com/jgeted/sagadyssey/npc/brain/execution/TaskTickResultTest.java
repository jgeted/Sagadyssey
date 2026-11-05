package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanBlocker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskTickResultTest {
    private static final NpcPlanBlocker BLOCKER = new NpcPlanBlocker(
            "missing_input", "waiting for coal", 120L, "request:coal");

    @Test
    void factoriesCreateResultsWithTheRequiredShape() {
        TaskTickResult running = TaskTickResult.running("moving");
        TaskTickResult succeeded = TaskTickResult.stepSucceeded("arrived");
        TaskTickResult blocked = TaskTickResult.blocked("no coal", BLOCKER);
        TaskTickResult failed = TaskTickResult.failed("target vanished");

        assertEquals(TaskTickResult.State.RUNNING, running.state());
        assertEquals(TaskTickResult.State.STEP_SUCCEEDED, succeeded.state());
        assertEquals(TaskTickResult.State.BLOCKED, blocked.state());
        assertEquals(TaskTickResult.State.FAILED, failed.state());
        assertNull(running.blocker());
        assertNull(succeeded.blocker());
        assertSame(BLOCKER, blocked.blocker());
        assertNull(failed.blocker());
    }

    @Test
    void blockedResultMustHaveABlocker() {
        assertThrows(NullPointerException.class,
                () -> TaskTickResult.blocked("missing", null));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskTickResult(TaskTickResult.State.BLOCKED, "missing", null));
    }

    @Test
    void nonBlockedResultCannotCarryABlocker() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskTickResult(TaskTickResult.State.RUNNING, "moving", BLOCKER));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskTickResult(TaskTickResult.State.STEP_SUCCEEDED, "done", BLOCKER));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskTickResult(TaskTickResult.State.FAILED, "failed", BLOCKER));
        assertThrows(IllegalArgumentException.class,
                () -> TaskTickResult.running(" "));
    }
}
