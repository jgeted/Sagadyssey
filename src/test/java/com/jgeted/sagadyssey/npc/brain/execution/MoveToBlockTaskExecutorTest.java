package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPriority;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStepStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveToBlockTaskExecutorTest {
    private static final Identifier OVERWORLD = Identifier.fromNamespaceAndPath(
            "minecraft", "overworld");
    private static final Identifier NETHER = Identifier.fromNamespaceAndPath(
            "minecraft", "the_nether");

    @Test
    void parserRequiresCoordinatesAndValidatesOptionalBounds() {
        MoveToBlockTaskExecutor.Parameters defaults = MoveToBlockTaskExecutor.parseParameters(
                params(OVERWORLD, 12, 64, -9));

        assertEquals(OVERWORLD, defaults.dimension());
        assertEquals(new BlockPos(12, 64, -9), defaults.target());
        assertEquals(MoveToBlockTaskExecutor.DEFAULT_SPEED, defaults.speed());
        assertEquals(MoveToBlockTaskExecutor.DEFAULT_RANGE, defaults.range());

        Map<String, String> custom = new HashMap<>(params(OVERWORLD, 1, 70, 2));
        custom.put(MoveToBlockTaskExecutor.SPEED, "1.25");
        custom.put(MoveToBlockTaskExecutor.RANGE, "2.5");
        MoveToBlockTaskExecutor.Parameters parsed =
                MoveToBlockTaskExecutor.parseParameters(custom);
        assertEquals(1.25D, parsed.speed());
        assertEquals(2.5D, parsed.range());

        Map<String, String> missing = new HashMap<>(custom);
        missing.remove(MoveToBlockTaskExecutor.X);
        assertThrows(IllegalArgumentException.class,
                () -> MoveToBlockTaskExecutor.parseParameters(missing));
        assertThrows(IllegalArgumentException.class,
                () -> MoveToBlockTaskExecutor.parseParameters(with(custom,
                        MoveToBlockTaskExecutor.SPEED, "NaN")));
        assertThrows(IllegalArgumentException.class,
                () -> MoveToBlockTaskExecutor.parseParameters(with(custom,
                        MoveToBlockTaskExecutor.RANGE, "0")));
        assertThrows(IllegalArgumentException.class,
                () -> MoveToBlockTaskExecutor.parseParameters(with(custom,
                        MoveToBlockTaskExecutor.X, "30000001")));
    }

    @Test
    void productionConstructorRejectsNonEntityContext() {
        MoveToBlockTaskExecutor executor = new MoveToBlockTaskExecutor();
        NpcPlanStep step = step(params(OVERWORLD, 1, 64, 1));

        TaskTickResult result = executor.start(context(10L), plan(step), step);

        assertEquals(TaskTickResult.State.FAILED, result.state());
        assertTrue(result.detail().contains("服务端 NPC 实体上下文"));
    }

    @Test
    void crossDimensionFailsWithoutStartingNavigation() {
        FakeDriver driver = new FakeDriver(NETHER);
        MoveToBlockTaskExecutor executor = new MoveToBlockTaskExecutor(ignored -> driver);
        NpcPlanStep step = step(params(OVERWORLD, 1, 64, 1));

        TaskTickResult result = executor.start(context(10L), plan(step), step);

        assertEquals(TaskTickResult.State.FAILED, result.state());
        assertTrue(result.detail().contains("其他维度"));
        assertEquals(0, driver.moveCalls);
        assertEquals(1, driver.stopCalls);
    }

    @Test
    void startAndRecoverRebuildNavigationAndArrivalStopsIt() {
        FakeDriver driver = new FakeDriver(OVERWORLD);
        MoveToBlockTaskExecutor executor = new MoveToBlockTaskExecutor(ignored -> driver);
        NpcPlanStep step = step(params(OVERWORLD, 4, 64, 4));
        NpcPlan plan = plan(step);
        UUID npcId = UUID.randomUUID();

        assertEquals(TaskTickResult.State.RUNNING,
                executor.start(context(npcId, 10L), plan, step).state());
        assertEquals(1, driver.moveCalls);
        assertEquals(1, driver.resetCalls);

        assertEquals(TaskTickResult.State.RUNNING,
                executor.recover(context(npcId, 11L), plan, step).state());
        assertEquals(2, driver.moveCalls);
        assertEquals(2, driver.resetCalls);
        assertEquals(1, driver.stopCalls);

        driver.reached = true;
        assertEquals(TaskTickResult.State.STEP_SUCCEEDED,
                executor.tick(context(npcId, 12L), plan, step).state());
        assertEquals(2, driver.stopCalls);
    }

    @Test
    void unloadedChunkBlocksWithStructuredRetryReason() {
        FakeDriver driver = new FakeDriver(OVERWORLD);
        driver.chunkLoaded = false;
        MoveToBlockTaskExecutor executor = new MoveToBlockTaskExecutor(ignored -> driver);
        NpcPlanStep step = step(params(OVERWORLD, 4, 64, 4));

        TaskTickResult result = executor.start(context(100L), plan(step), step);

        assertEquals(TaskTickResult.State.BLOCKED, result.state());
        assertEquals("TARGET_CHUNK_UNLOADED", result.blocker().code());
        assertEquals(140L, result.blocker().retryAt());
        assertEquals(0, driver.moveCalls);
    }

    @Test
    void threePathFailuresBecomeRetryableBlocker() {
        FakeDriver driver = new FakeDriver(OVERWORLD);
        driver.moveAccepted = false;
        MoveToBlockTaskExecutor executor = new MoveToBlockTaskExecutor(ignored -> driver);
        NpcPlanStep step = step(params(OVERWORLD, 4, 64, 4));
        NpcPlan plan = plan(step);
        UUID npcId = UUID.randomUUID();

        assertEquals(TaskTickResult.State.RUNNING,
                executor.start(context(npcId, 0L), plan, step).state());
        assertEquals(TaskTickResult.State.RUNNING,
                executor.tick(context(npcId, 20L), plan, step).state());
        TaskTickResult third = executor.tick(context(npcId, 40L), plan, step);

        assertEquals(TaskTickResult.State.BLOCKED, third.state());
        assertEquals("PATH_UNREACHABLE", third.blocker().code());
        assertEquals(3, driver.moveCalls);
        assertEquals(1, driver.stopCalls);
    }

    @Test
    void cancelAlwaysStopsNavigationAndClearsSession() {
        FakeDriver driver = new FakeDriver(OVERWORLD);
        MoveToBlockTaskExecutor executor = new MoveToBlockTaskExecutor(ignored -> driver);
        NpcPlanStep step = step(params(OVERWORLD, 4, 64, 4));
        NpcPlan plan = plan(step);
        UUID npcId = UUID.randomUUID();
        NpcExecutionContext context = context(npcId, 10L);
        executor.start(context, plan, step);

        executor.cancel(context, plan, step, PlanCancellationReason.MANUAL);

        assertEquals(1, driver.stopCalls);
        assertEquals(TaskTickResult.State.RUNNING,
                executor.tick(context(npcId, 11L), plan, step).state());
        assertEquals(2, driver.moveCalls);
    }

    private static Map<String, String> params(Identifier dimension, int x, int y, int z) {
        return Map.of(
                MoveToBlockTaskExecutor.DIMENSION, dimension.toString(),
                MoveToBlockTaskExecutor.X, Integer.toString(x),
                MoveToBlockTaskExecutor.Y, Integer.toString(y),
                MoveToBlockTaskExecutor.Z, Integer.toString(z));
    }

    private static Map<String, String> with(Map<String, String> source, String key, String value) {
        Map<String, String> copy = new HashMap<>(source);
        copy.put(key, value);
        return copy;
    }

    private static NpcPlanStep step(Map<String, String> params) {
        return new NpcPlanStep(0, NpcPlanPhase.TRAVELLING,
                MoveToBlockTaskExecutor.ID, params, NpcPlanStepStatus.PENDING, 0L, "");
    }

    private static NpcPlan plan(NpcPlanStep step) {
        return new NpcPlan(UUID.randomUUID(), UUID.randomUUID(), "test:move",
                MoveToBlockTaskExecutor.ID, NpcPlanKind.WORK, NpcPlanPriority.NORMAL,
                NpcPlanStatus.SELECTED, List.of(step), 0,
                0L, 0L, 0L, 1_000L, null, "test");
    }

    private static NpcExecutionContext context(long gameTime) {
        return context(UUID.randomUUID(), gameTime);
    }

    private static NpcExecutionContext context(UUID npcId, long gameTime) {
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

    private static final class FakeDriver implements MoveToBlockTaskExecutor.MovementDriver {
        private final Identifier dimension;
        private boolean alive = true;
        private boolean validHeight = true;
        private boolean chunkLoaded = true;
        private boolean reached;
        private boolean moveAccepted = true;
        private boolean stalled;
        private int moveCalls;
        private int resetCalls;
        private int stopCalls;

        private FakeDriver(Identifier dimension) {
            this.dimension = dimension;
        }

        @Override
        public Identifier dimension() {
            return dimension;
        }

        @Override
        public boolean alive() {
            return alive;
        }

        @Override
        public boolean validHeight(int y) {
            return validHeight;
        }

        @Override
        public boolean chunkLoaded(BlockPos target) {
            return chunkLoaded;
        }

        @Override
        public boolean reached(BlockPos target, double range) {
            return reached;
        }

        @Override
        public boolean moveTo(BlockPos target, double speed) {
            moveCalls++;
            return moveAccepted;
        }

        @Override
        public void resetProgress(BlockPos target) {
            resetCalls++;
        }

        @Override
        public boolean stalled(BlockPos target) {
            return stalled;
        }

        @Override
        public void stop() {
            stopCalls++;
        }
    }
}
