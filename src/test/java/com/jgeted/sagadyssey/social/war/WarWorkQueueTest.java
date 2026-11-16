package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.runtime.WarWorkQueue;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class WarWorkQueueTest {
    @Test void aHundredPersistentUnitsReceiveFairBoundedServiceWithoutPollingAllOfThem() {
        var queue = new WarWorkQueue(new WarWorkQueue.Limits(100, 8, 1, 1_000), () -> 0);
        Map<UUID, Integer> visits = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            UUID id = UUID.randomUUID();
            queue.submit(id, quota -> {
                assertTrue(quota.tryConsume());
                visits.merge(id, 1, Integer::sum);
                return WarWorkQueue.Step.next();
            }, 0);
        }
        for (int tick = 0; tick < 26; tick++) queue.tick(tick);
        assertEquals(100, visits.size());
        assertTrue(visits.values().stream().allMatch(count -> count >= 2));
        assertEquals(208, queue.snapshot().steps());
        assertEquals(208, queue.snapshot().operations());
        assertTrue(queue.snapshot().maxWaitTicks() <= 13);
        assertEquals(100, queue.snapshot().ready());
    }

    @Test void timeBudgetStopsFollowingJobsAndReportsAnOversizedSlice() {
        AtomicLong clock = new AtomicLong();
        var queue = new WarWorkQueue(new WarWorkQueue.Limits(3, 3, 2, 10), clock::get);
        AtomicInteger following = new AtomicInteger();
        queue.submit(UUID.randomUUID(), quota -> {
            quota.tryConsume(); clock.addAndGet(20); return WarWorkQueue.Step.complete();
        }, 0);
        queue.submit(UUID.randomUUID(), quota -> { following.incrementAndGet(); return WarWorkQueue.Step.complete(); }, 0);
        queue.tick(0);
        assertEquals(0, following.get());
        assertEquals(1, queue.snapshot().overruns());
        assertEquals(20, queue.snapshot().maxStepNanos());
        queue.tick(1);
        assertEquals(1, following.get());
    }

    @Test void oneUnitCannotRepeatWithinTheSameTickAndQuotaBoundsItsOperations() {
        var queue = new WarWorkQueue(new WarWorkQueue.Limits(2, 2, 3, 100), () -> 0);
        AtomicInteger count = new AtomicInteger();
        queue.submit(UUID.randomUUID(), quota -> {
            while (quota.tryConsume()) count.incrementAndGet();
            return WarWorkQueue.Step.next();
        }, 0);
        queue.tick(0); queue.tick(0);
        assertEquals(3, count.get());
        queue.tick(1);
        assertEquals(6, count.get());
    }

    @Test void dispatchOverheadCannotStarveTheQueueWhenTheTimeBudgetIsVerySmall() {
        AtomicLong clock = new AtomicLong(); AtomicInteger completed = new AtomicInteger();
        var queue = new WarWorkQueue(new WarWorkQueue.Limits(3, 3, 1, 1), clock::getAndIncrement);
        for (int i = 0; i < 3; i++) {
            queue.submit(UUID.randomUUID(), quota -> {
                assertTrue(quota.tryConsume()); completed.incrementAndGet(); return WarWorkQueue.Step.complete();
            }, 0);
        }
        for (int tick = 0; tick < 3; tick++) {
            queue.tick(tick);
            assertEquals(tick + 1, completed.get());
        }
        assertEquals(0, queue.snapshot().ready());
    }

    @Test void aFailureRemainsBlockedAndConsumesCapacityUntilExplicitlyReconciled() {
        var queue = new WarWorkQueue(new WarWorkQueue.Limits(2, 2, 1, 100), () -> 0);
        UUID id = UUID.randomUUID(); AtomicBoolean repaired = new AtomicBoolean();
        queue.submit(id, quota -> {
            if (!repaired.get()) throw new IllegalStateException("domain result uncertain");
            return WarWorkQueue.Step.complete();
        }, 0);
        queue.tick(0);
        assertEquals("WORK_FAILED:IllegalStateException", queue.blockedReason(id).orElseThrow());
        assertEquals(WarWorkQueue.Submission.DUPLICATE, queue.submit(id, quota -> WarWorkQueue.Step.complete(), 1));
        queue.submit(UUID.randomUUID(), quota -> WarWorkQueue.Step.blocked("waiting"), 1);
        assertEquals(WarWorkQueue.Submission.FULL, queue.submit(UUID.randomUUID(), quota -> WarWorkQueue.Step.complete(), 1));
        repaired.set(true);
        assertTrue(queue.resume(id, 1));
        queue.tick(1);
        assertTrue(queue.blockedReason(id).isEmpty());
        assertEquals(1, queue.snapshot().failures());
        assertEquals(1, queue.snapshot().blocked());
    }

    @Test void zeroProgressAndReentrantMutationCannotSpinOrCorruptTheQueue() {
        var queue = new WarWorkQueue(new WarWorkQueue.Limits(2, 2, 1, 100), () -> 0);
        UUID noProgress = UUID.randomUUID(), reentrant = UUID.randomUUID();
        queue.submit(noProgress, quota -> WarWorkQueue.Step.next(), 0);
        queue.submit(reentrant, quota -> {
            queue.cancel(noProgress);
            return WarWorkQueue.Step.complete();
        }, 0);
        queue.tick(0);
        assertEquals("NO_PROGRESS", queue.blockedReason(noProgress).orElseThrow());
        assertEquals("WORK_FAILED:IllegalStateException", queue.blockedReason(reentrant).orElseThrow());
        assertEquals(0, queue.snapshot().ready());
        assertEquals(2, queue.snapshot().blocked());
    }

    @Test void quiescingDrainsExistingWorkAndCannotSilentlyDropItAtClose() {
        var queue = new WarWorkQueue(new WarWorkQueue.Limits(1, 1, 1, 100), () -> 0);
        queue.submit(UUID.randomUUID(), quota -> WarWorkQueue.Step.complete(), 0);
        queue.quiesce();
        assertEquals(WarWorkQueue.Submission.QUIESCING,
                queue.submit(UUID.randomUUID(), quota -> WarWorkQueue.Step.complete(), 0));
        assertThrows(IllegalStateException.class, queue::close);
        queue.tick(0); queue.close();
        assertFalse(queue.snapshot().accepting());
        assertThrows(IllegalStateException.class, () -> queue.tick(1));
    }

    @Test void timeCannotReverseAfterSubmissionAndForeignThreadsAreRejected() {
        var queue = new WarWorkQueue(new WarWorkQueue.Limits(1, 1, 1, 100), () -> 0);
        queue.submit(UUID.randomUUID(), quota -> WarWorkQueue.Step.complete(), 10);
        assertThrows(IllegalArgumentException.class, () -> queue.tick(9));
        queue.tick(10);
        CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, () -> queue.tick(11))).join();
        assertEquals(1, queue.snapshot().steps());
    }
}
