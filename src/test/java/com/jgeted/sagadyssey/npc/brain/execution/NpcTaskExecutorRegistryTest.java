package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcTaskExecutorRegistryTest {
    private static final ResourceLocation EXECUTOR_ID = ResourceLocation.fromNamespaceAndPath(
            "sagadyssey", "test_executor");

    @Test
    void duplicateExecutorIdsAreRejected() {
        NpcTaskExecutorRegistry registry = new NpcTaskExecutorRegistry();
        registry.register(new TestExecutor(EXECUTOR_ID));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> registry.register(new TestExecutor(EXECUTOR_ID)));

        assertTrue(exception.getMessage().contains(EXECUTOR_ID.toString()));
        assertEquals(1, registry.snapshot().size());
    }

    @Test
    void lookupReturnsRegisteredExecutorAndSnapshotIsImmutable() {
        NpcTaskExecutorRegistry registry = new NpcTaskExecutorRegistry();
        TestExecutor executor = new TestExecutor(EXECUTOR_ID);
        registry.register(executor);

        assertSame(executor, registry.find(EXECUTOR_ID).orElseThrow());
        assertTrue(registry.find(ResourceLocation.fromNamespaceAndPath(
                "sagadyssey", "missing")).isEmpty());

        Map<ResourceLocation, NpcTaskExecutor> snapshot = registry.snapshot();
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put(ResourceLocation.fromNamespaceAndPath(
                        "sagadyssey", "other"), new TestExecutor(EXECUTOR_ID)));

        registry.register(new TestExecutor(ResourceLocation.fromNamespaceAndPath(
                "sagadyssey", "second")));
        assertEquals(1, snapshot.size());
        assertEquals(2, registry.snapshot().size());
    }

    private record TestExecutor(ResourceLocation id) implements NpcTaskExecutor {
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
    }
}
