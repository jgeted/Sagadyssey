package com.jgeted.sagadyssey.social.war.runtime;

import java.util.*;
import java.util.function.LongSupplier;

/**
 * 主线程主动驱动的、可分片规划工作队列；不是世界副作用或财务事务队列。
 * 与现有被动FairTickBudget使用相同的公平/有界原则，但只访问实际获准的队首项，
 * 无需每tick让所有实体轮询request。工作状态由调用方持久模型拥有，队列可重建。
 */
public final class WarWorkQueue {
    public enum Submission { ACCEPTED, DUPLICATE, FULL, QUIESCING }
    public enum Outcome { COMPLETE, CONTINUE, BLOCKED }
    public record Step(Outcome outcome, String reason) {
        public Step {
            Objects.requireNonNull(outcome);
            reason = reason == null ? "" : reason;
            if (reason.length() > 512) throw new IllegalArgumentException("reason too long");
        }
        public static Step complete() { return new Step(Outcome.COMPLETE, ""); }
        public static Step next() { return new Step(Outcome.CONTINUE, ""); }
        public static Step blocked(String reason) { return new Step(Outcome.BLOCKED, reason); }
    }
    @FunctionalInterface public interface Work { Step run(Quota quota); }

    /** 每个实际基础操作前领取配额；Java回调不可被强行抢占，耗时超限会记录并停止后续项。 */
    public static final class Quota {
        private final int limit;
        private int used;
        private Quota(int limit) { this.limit = limit; }
        public boolean tryConsume() {
            if (used == limit) return false;
            used++; return true;
        }
        public int remaining() { return limit - used; }
    }

    public record Limits(int capacity, int stepsPerTick, int operationsPerStep, long nanosPerTick) {
        public Limits {
            if (capacity < 1 || stepsPerTick < 1 || stepsPerTick > capacity
                    || operationsPerStep < 1 || nanosPerTick < 1) throw new IllegalArgumentException("invalid work limits");
        }
    }
    public record Snapshot(long ticks, long steps, long operations, long failures, long overruns,
                           long maxStepNanos, long maxTickNanos, long maxWaitTicks,
                           int ready, int blocked, boolean accepting) {}
    private record Entry(Work work, long queuedAtTick) {}
    private record Blocked(Entry entry, String reason) {}

    private final Thread owner = Thread.currentThread();
    private final Limits limits;
    private final LongSupplier nanoClock;
    private final LinkedHashMap<UUID, Entry> ready = new LinkedHashMap<>();
    private final Map<UUID, Blocked> blocked = new HashMap<>();
    private long lastTick = -1, observedTick, ticks, steps, operations, failures, overruns, maxStepNanos, maxTickNanos, maxWaitTicks;
    private boolean accepting = true, ticking, closed;

    public WarWorkQueue(Limits limits, LongSupplier nanoClock) {
        this.limits = Objects.requireNonNull(limits);
        this.nanoClock = Objects.requireNonNull(nanoClock);
    }

    public Submission submit(UUID key, Work work, long now) {
        mutation(now);
        Objects.requireNonNull(key); Objects.requireNonNull(work);
        if (!accepting) return Submission.QUIESCING;
        if (ready.containsKey(key) || blocked.containsKey(key)) return Submission.DUPLICATE;
        if (ready.size() + blocked.size() >= limits.capacity()) return Submission.FULL;
        ready.put(key, new Entry(work, now));
        return Submission.ACCEPTED;
    }

    public boolean cancel(UUID key) {
        mutation(observedTick);
        return ready.remove(key) != null || blocked.remove(key) != null;
    }

    /** 调用方完成领域核对后显式恢复；不自动重演失败动作。 */
    public boolean resume(UUID key, long now) {
        mutation(now);
        Blocked value = blocked.remove(key);
        if (value == null) return false;
        ready.put(key, new Entry(value.entry().work(), now));
        return true;
    }

    public Optional<String> blockedReason(UUID key) {
        checkThread();
        return Optional.ofNullable(blocked.get(key)).map(Blocked::reason);
    }

    public void tick(long now) {
        mutation(now);
        if (now < 0) throw new IllegalArgumentException("negative tick");
        if (now == lastTick) return;
        lastTick = now; ticks++;
        long start = nanoClock.getAsLong();
        int count = Math.min(ready.size(), limits.stepsPerTick());
        ticking = true;
        try {
            for (int i = 0; i < count; i++) {
                // Always admit one bounded slice: clock/dispatch overhead alone must not starve all work.
                if (i > 0 && elapsed(start) >= limits.nanosPerTick()) break;
                var iterator = ready.entrySet().iterator();
                var first = iterator.next();
                UUID key = first.getKey(); Entry entry = first.getValue(); iterator.remove();
                maxWaitTicks = Math.max(maxWaitTicks, now - entry.queuedAtTick());
                Quota quota = new Quota(limits.operationsPerStep());
                long stepStart = nanoClock.getAsLong();
                Step result;
                try {
                    result = Objects.requireNonNull(entry.work().run(quota), "null work result");
                    if (result.outcome() == Outcome.CONTINUE && quota.used == 0) {
                        result = Step.blocked("NO_PROGRESS");
                    }
                } catch (RuntimeException exception) {
                    failures++;
                    result = Step.blocked("WORK_FAILED:" + exception.getClass().getSimpleName());
                }
                steps++; operations += quota.used;
                long duration = elapsed(stepStart);
                maxStepNanos = Math.max(maxStepNanos, duration);
                if (duration > limits.nanosPerTick()) overruns++;
                if (result.outcome() == Outcome.CONTINUE) ready.put(key, new Entry(entry.work(), now));
                if (result.outcome() == Outcome.BLOCKED) blocked.put(key, new Blocked(entry, result.reason()));
            }
        } finally {
            ticking = false;
            maxTickNanos = Math.max(maxTickNanos, elapsed(start));
        }
    }

    public void quiesce() { mutation(observedTick); accepting = false; }
    public void close() {
        mutation(observedTick);
        if (!ready.isEmpty() || !blocked.isEmpty()) throw new IllegalStateException("work still pending");
        accepting = false; closed = true;
    }
    public Snapshot snapshot() {
        checkThread();
        return new Snapshot(ticks, steps, operations, failures, overruns, maxStepNanos,
                maxTickNanos, maxWaitTicks, ready.size(), blocked.size(), accepting);
    }
    private long elapsed(long start) { return Math.max(0, nanoClock.getAsLong() - start); }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("work queue owner thread required");
    }
    private void mutation(long now) {
        checkThread();
        if (closed || ticking) throw new IllegalStateException("queue closed or reentrant mutation");
        if (now < observedTick) throw new IllegalArgumentException("queue time cannot move backwards");
        observedTick = now;
    }
}
