package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPriority;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStepStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaitUntilTaskExecutorTest {
    private final WaitUntilTaskExecutor executor = new WaitUntilTaskExecutor();

    @Test
    void waitsUntilDeclaredGameTime() {
        NpcPlanStep step = step(Map.of(WaitUntilTaskExecutor.UNTIL_GAME_TIME, "20"));

        assertEquals(TaskTickResult.State.RUNNING,
                executor.start(context(19L), plan(step), step).state());
        assertEquals(TaskTickResult.State.STEP_SUCCEEDED,
                executor.tick(context(20L), plan(step), step).state());
    }

    @Test
    void rejectsMissingOrMalformedDeadline() {
        assertEquals(TaskTickResult.State.FAILED,
                executor.start(context(10L), plan(step(Map.of())), step(Map.of())).state());
        NpcPlanStep malformed = step(Map.of(WaitUntilTaskExecutor.UNTIL_GAME_TIME, "later"));
        assertEquals(TaskTickResult.State.FAILED,
                executor.start(context(10L), plan(malformed), malformed).state());
    }

    private static NpcPlanStep step(Map<String, String> params) {
        return new NpcPlanStep(0, NpcPlanPhase.ACTING, WaitUntilTaskExecutor.ID,
                params, NpcPlanStepStatus.PENDING, 0L, "");
    }

    private static NpcPlan plan(NpcPlanStep step) {
        long now = 0L;
        return new NpcPlan(UUID.randomUUID(), UUID.randomUUID(), "test:wait",
                WaitUntilTaskExecutor.ID, NpcPlanKind.AMBIENT, NpcPlanPriority.NORMAL,
                NpcPlanStatus.SELECTED, java.util.List.of(step), 0,
                now, now, now, 100L, null, "test");
    }

    private static NpcExecutionContext context(long gameTime) {
        UUID npcId = UUID.randomUUID();
        return new NpcExecutionContext() {
            @Override
            public UUID npcId() {
                return npcId;
            }

            @Override
            public long gameTime() {
                return gameTime;
            }
        };
    }
}
