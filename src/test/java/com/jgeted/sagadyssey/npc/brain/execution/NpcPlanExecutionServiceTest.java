package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.npc.brain.plan.InMemoryNpcPlanStore;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanBlocker;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanLifecycleService;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPriority;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStepStatus;
import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationKey;
import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationService;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcPlanExecutionServiceTest {
    private static final ResourceLocation PROVIDER = ResourceLocation.fromNamespaceAndPath(
            "sagadyssey", "test_provider");
    private static final ResourceLocation FIRST_EXECUTOR = ResourceLocation.fromNamespaceAndPath(
            "sagadyssey", "test_first");
    private static final ResourceLocation SECOND_EXECUTOR = ResourceLocation.fromNamespaceAndPath(
            "sagadyssey", "test_second");

    @Test
    void advancesMultiStepPlanAndStoresTerminalHistory() {
        Fixture fixture = fixture();
        fixture.executors.register(new StubExecutor(FIRST_EXECUTOR,
                TaskTickResult.stepSucceeded("预留完成")));
        fixture.executors.register(new StubExecutor(SECOND_EXECUTOR,
                TaskTickResult.stepSucceeded("行动完成")));
        fixture.store.save(plan(fixture.npcId, 10L, 100L, List.of(
                step(0, NpcPlanPhase.RESERVING, FIRST_EXECUTOR, 10L),
                step(1, NpcPlanPhase.ACTING, SECOND_EXECUTOR, 10L))));

        NpcPlanExecutionUpdate first = fixture.service.tick(context(fixture.npcId, 11L));
        assertEquals(NpcPlanExecutionUpdate.Outcome.STEP_ADVANCED, first.outcome());
        assertEquals(1, fixture.store.active(fixture.npcId).orElseThrow().currentStepIndex());

        NpcPlanExecutionUpdate second = fixture.service.tick(context(fixture.npcId, 12L));
        assertEquals(NpcPlanExecutionUpdate.Outcome.SUCCEEDED, second.outcome());
        assertTrue(fixture.store.active(fixture.npcId).isEmpty());
        assertEquals(NpcPlanStatus.SUCCEEDED,
                fixture.store.history(fixture.npcId).getFirst().status());
    }

    @Test
    void blockedStepWaitsUntilRetryTimeThenResumes() {
        Fixture fixture = fixture();
        StubExecutor executor = new StubExecutor(FIRST_EXECUTOR,
                TaskTickResult.blocked("工作站忙",
                        new NpcPlanBlocker("WORKSTATION_BUSY", "铁砧被占用", 20L, "forge:1")),
                TaskTickResult.stepSucceeded("重试成功"));
        fixture.executors.register(executor);
        fixture.store.save(plan(fixture.npcId, 10L, 100L,
                List.of(step(0, NpcPlanPhase.ACTING, FIRST_EXECUTOR, 10L))));

        assertEquals(NpcPlanExecutionUpdate.Outcome.WAITING,
                fixture.service.tick(context(fixture.npcId, 11L)).outcome());
        assertEquals(NpcPlanExecutionUpdate.Outcome.WAITING,
                fixture.service.tick(context(fixture.npcId, 19L)).outcome());
        assertEquals(1, executor.startCalls);

        assertEquals(NpcPlanExecutionUpdate.Outcome.SUCCEEDED,
                fixture.service.tick(context(fixture.npcId, 20L)).outcome());
        assertEquals(2, executor.startCalls);
    }

    @Test
    void missingExecutorFailsPlanAndReleasesReservations() {
        Fixture fixture = fixture();
        NpcPlan plan = plan(fixture.npcId, 10L, 100L,
                List.of(step(0, NpcPlanPhase.ACTING, FIRST_EXECUTOR, 10L)));
        fixture.store.save(plan);
        fixture.reservations.acquire(new NpcReservationKey("workstation", "forge:1"),
                plan.id(), fixture.npcId, 10L, 100L);

        NpcPlanExecutionUpdate update = fixture.service.tick(context(fixture.npcId, 11L));

        assertEquals(NpcPlanExecutionUpdate.Outcome.FAILED, update.outcome());
        assertTrue(fixture.store.active(fixture.npcId).isEmpty());
        assertTrue(fixture.reservations.snapshot().isEmpty());
    }

    @Test
    void expiredPlanCancelsExecutorAndReleasesReservations() {
        Fixture fixture = fixture();
        StubExecutor executor = new StubExecutor(FIRST_EXECUTOR,
                TaskTickResult.running("执行中"));
        fixture.executors.register(executor);
        NpcPlan plan = plan(fixture.npcId, 10L, 15L,
                List.of(step(0, NpcPlanPhase.ACTING, FIRST_EXECUTOR, 10L)));
        fixture.store.save(plan);
        fixture.reservations.acquire(new NpcReservationKey("workstation", "forge:1"),
                plan.id(), fixture.npcId, 10L, 100L);

        NpcPlanExecutionUpdate update = fixture.service.tick(context(fixture.npcId, 15L));

        assertEquals(NpcPlanExecutionUpdate.Outcome.CANCELLED, update.outcome());
        assertEquals(1, executor.cancelCalls);
        assertTrue(fixture.reservations.snapshot().isEmpty());
        assertEquals(NpcPlanStatus.CANCELLED,
                fixture.store.history(fixture.npcId).getFirst().status());
    }

    @Test
    void runningStepUsesStartOnceThenTick() {
        Fixture fixture = fixture();
        StubExecutor executor = new StubExecutor(FIRST_EXECUTOR,
                TaskTickResult.running("开始"),
                TaskTickResult.running("继续"));
        fixture.executors.register(executor);
        fixture.store.save(plan(fixture.npcId, 10L, 100L,
                List.of(step(0, NpcPlanPhase.ACTING, FIRST_EXECUTOR, 10L))));

        fixture.service.tick(context(fixture.npcId, 11L));
        fixture.service.tick(context(fixture.npcId, 12L));

        assertEquals(1, executor.startCalls);
        assertEquals(1, executor.tickCalls);
        assertFalse(fixture.store.active(fixture.npcId).isEmpty());
        assertEquals("继续", fixture.store.active(fixture.npcId).orElseThrow().currentStep().detail());
    }

    @Test
    void newExecutionServiceRecoversPersistedRunningStepBeforeTicking() {
        Fixture fixture = fixture();
        StubExecutor firstRuntime = new StubExecutor(FIRST_EXECUTOR,
                TaskTickResult.running("开始"));
        fixture.executors.register(firstRuntime);
        fixture.store.save(plan(fixture.npcId, 10L, 100L,
                List.of(step(0, NpcPlanPhase.ACTING, FIRST_EXECUTOR, 10L))));
        fixture.service.tick(context(fixture.npcId, 11L));

        NpcTaskExecutorRegistry reloadedExecutors = new NpcTaskExecutorRegistry();
        StubExecutor recovered = new StubExecutor(FIRST_EXECUTOR,
                TaskTickResult.stepSucceeded("恢复完成"));
        reloadedExecutors.register(recovered);
        NpcPlanExecutionService reloadedService = new NpcPlanExecutionService(
                fixture.store,
                new NpcPlanLifecycleService(fixture.store, fixture.reservations),
                reloadedExecutors);

        assertEquals(NpcPlanExecutionUpdate.Outcome.SUCCEEDED,
                reloadedService.tick(context(fixture.npcId, 12L)).outcome());
        assertEquals(1, recovered.recoverCalls);
        assertEquals(0, recovered.startCalls);
        assertEquals(0, recovered.tickCalls);
    }

    @Test
    void executorExceptionFailsPlanInsteadOfLeavingRunningSnapshot() {
        Fixture fixture = fixture();
        fixture.executors.register(new NpcTaskExecutor() {
            @Override
            public ResourceLocation id() {
                return FIRST_EXECUTOR;
            }

            @Override
            public TaskTickResult start(NpcExecutionContext context, NpcPlan plan,
                                        NpcPlanStep step) {
                throw new IllegalStateException("boom");
            }

            @Override
            public TaskTickResult tick(NpcExecutionContext context, NpcPlan plan,
                                       NpcPlanStep step) {
                return TaskTickResult.running("不应执行");
            }
        });
        fixture.store.save(plan(fixture.npcId, 10L, 100L,
                List.of(step(0, NpcPlanPhase.ACTING, FIRST_EXECUTOR, 10L))));

        NpcPlanExecutionUpdate update = fixture.service.tick(context(fixture.npcId, 11L));

        assertEquals(NpcPlanExecutionUpdate.Outcome.FAILED, update.outcome());
        assertTrue(fixture.store.active(fixture.npcId).isEmpty());
        assertTrue(update.detail().contains("boom"));
    }

    private static Fixture fixture() {
        UUID npcId = UUID.randomUUID();
        InMemoryNpcPlanStore store = new InMemoryNpcPlanStore();
        NpcReservationService reservations = new NpcReservationService();
        NpcTaskExecutorRegistry executors = new NpcTaskExecutorRegistry();
        NpcPlanLifecycleService lifecycle = new NpcPlanLifecycleService(store, reservations);
        return new Fixture(npcId, store, reservations, executors,
                new NpcPlanExecutionService(store, lifecycle, executors));
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

    private static NpcPlan plan(UUID npcId, long createdAt, long expiresAt,
                                List<NpcPlanStep> steps) {
        return new NpcPlan(UUID.randomUUID(), npcId, "test:" + UUID.randomUUID(), PROVIDER,
                NpcPlanKind.WORK, NpcPlanPriority.NORMAL, NpcPlanStatus.SELECTED,
                steps, 0, createdAt, createdAt, createdAt + 3L, expiresAt,
                null, "测试计划");
    }

    private static NpcPlanStep step(int index, NpcPlanPhase phase,
                                    ResourceLocation executorId, long now) {
        return new NpcPlanStep(index, phase, executorId, Map.of(),
                NpcPlanStepStatus.PENDING, now, "");
    }

    private record Fixture(UUID npcId, InMemoryNpcPlanStore store,
                           NpcReservationService reservations,
                           NpcTaskExecutorRegistry executors,
                           NpcPlanExecutionService service) {
    }

    private static final class StubExecutor implements NpcTaskExecutor {
        private final ResourceLocation id;
        private final Deque<TaskTickResult> results;
        private int startCalls;
        private int tickCalls;
        private int recoverCalls;
        private int cancelCalls;

        private StubExecutor(ResourceLocation id, TaskTickResult... results) {
            this.id = id;
            this.results = new ArrayDeque<>(List.of(results));
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public TaskTickResult start(NpcExecutionContext context, NpcPlan plan,
                                    NpcPlanStep step) {
            startCalls++;
            return next();
        }

        @Override
        public TaskTickResult tick(NpcExecutionContext context, NpcPlan plan,
                                   NpcPlanStep step) {
            tickCalls++;
            return next();
        }

        @Override
        public TaskTickResult recover(NpcExecutionContext context, NpcPlan plan,
                                      NpcPlanStep step) {
            recoverCalls++;
            return next();
        }

        @Override
        public void cancel(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step,
                           PlanCancellationReason reason) {
            cancelCalls++;
        }

        private TaskTickResult next() {
            if (results.isEmpty()) return TaskTickResult.running("等待后续结果");
            return results.removeFirst();
        }
    }
}
