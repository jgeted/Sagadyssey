package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPriority;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStepStatus;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class NpcTaskExecutorTest {
    private static final ResourceLocation EXECUTOR_ID = ResourceLocation.fromNamespaceAndPath(
            "sagadyssey", "no_op");

    @Test
    void defaultCancellationIsASafeNoOp() {
        NpcTaskExecutor executor = new NpcTaskExecutor() {
            @Override
            public ResourceLocation id() {
                return EXECUTOR_ID;
            }

            @Override
            public TaskTickResult start(NpcExecutionContext context, NpcPlan plan,
                                        NpcPlanStep step) {
                return TaskTickResult.running("started");
            }

            @Override
            public TaskTickResult tick(NpcExecutionContext context, NpcPlan plan,
                                       NpcPlanStep step) {
                return TaskTickResult.stepSucceeded("finished");
            }
        };
        NpcExecutionContext context = new TestExecutionContext(UUID.randomUUID(), 20L);
        NpcPlanStep step = new NpcPlanStep(0, NpcPlanPhase.ACTING, EXECUTOR_ID, Map.of(),
                NpcPlanStepStatus.RUNNING, 20L, "running");
        NpcPlan plan = new NpcPlan(UUID.randomUUID(), context.npcId(), "test:no-op",
                EXECUTOR_ID, NpcPlanKind.WORK, NpcPlanPriority.NORMAL,
                NpcPlanStatus.ACTING, java.util.List.of(step), 0,
                20L, 20L, 20L, 100L, null, "test");

        assertDoesNotThrow(() -> executor.cancel(context, plan, step,
                PlanCancellationReason.MANUAL));
    }

    private record TestExecutionContext(UUID npcId, long gameTime)
            implements NpcExecutionContext {
    }
}
