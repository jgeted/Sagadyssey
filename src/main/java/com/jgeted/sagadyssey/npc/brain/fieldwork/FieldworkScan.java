package com.jgeted.sagadyssey.npc.brain.fieldwork;

import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/** 可保存、可限流的发现阶段。一次扫描不加载区块、不改世界，也不替执行器预留目标。 */
public final class FieldworkScan {
    public static final int MAX_BUDGET = 4_096;

    private FieldworkScan() {
    }

    public record Cursor(FieldworkSearchArea area, long nextIndex,
                         boolean sawUnloaded, long retryAt) {
        public Cursor {
            Objects.requireNonNull(area, "area");
            if (nextIndex < 0L || nextIndex > area.size() || retryAt < 0L) {
                throw new IllegalArgumentException("invalid scan cursor");
            }
        }

        public static Cursor begin(FieldworkSearchArea area) {
            return new Cursor(area, 0L, false, 0L);
        }
    }

    public enum ProbeResult { AVAILABLE, NONE, UNLOADED, DEFERRED }
    public enum Status { SEARCHING, FOUND, EMPTY, UNLOADED, DEFERRED }

    @FunctionalInterface
    public interface Probe {
        ProbeResult inspect(BlockPos position);
    }

    public record Result(Status status, Cursor cursor, Optional<BlockPos> target,
                         int inspected) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(cursor, "cursor");
            Objects.requireNonNull(target, "target");
            target = target.map(BlockPos::immutable);
            if ((status == Status.FOUND) != target.isPresent() || inspected < 0) {
                throw new IllegalArgumentException("invalid scan result");
            }
        }
    }

    /**
     * DEFERRED 的重试时刻必须由已保存的不可达记忆/预留租期提供，不能返回永久等待。
     * NONE 代表本次没有工作，只有完整检查且没有未加载/暂缓目标时才能报告 EMPTY。
     */
    public static Result advance(Cursor cursor, int budget, long now, long deferredRetryAt,
                                 Probe probe) {
        return advance(cursor, budget, now, deferredRetryAt, probe, Long.MAX_VALUE, () -> 0L);
    }

    /** 时间片只在完整探测之间让出；至少探测一格，保存游标后下次继续，不缩小搜索区域。 */
    public static Result advanceTimed(Cursor cursor, int budget, long now, long deferredRetryAt,
                                      long nanosBudget, Probe probe) {
        return advance(cursor, budget, now, deferredRetryAt, probe, nanosBudget, System::nanoTime);
    }

    static Result advance(Cursor cursor, int budget, long now, long deferredRetryAt,
                          Probe probe, long nanosBudget, LongSupplier clock) {
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(clock, "clock");
        if (budget < 1 || budget > MAX_BUDGET || now < 0L || deferredRetryAt <= now || nanosBudget < 1L) {
            throw new IllegalArgumentException("invalid scan budget or retry deadline");
        }
        long started = clock.getAsLong();
        long index = cursor.nextIndex();
        boolean unloaded = cursor.sawUnloaded();
        long retryAt = cursor.retryAt();
        int inspected = 0;
        while (index < cursor.area().size() && inspected < budget) {
            if (inspected > 0 && clock.getAsLong() - started >= nanosBudget) break;
            BlockPos position = cursor.area().position(index);
            ProbeResult result = Objects.requireNonNull(probe.inspect(position), "probe result");
            index++;
            inspected++;
            if (result == ProbeResult.UNLOADED) unloaded = true;
            if (result == ProbeResult.DEFERRED) {
                retryAt = retryAt == 0L ? deferredRetryAt : Math.min(retryAt, deferredRetryAt);
            }
            Cursor next = new Cursor(cursor.area(), index, unloaded, retryAt);
            if (result == ProbeResult.AVAILABLE) {
                return new Result(Status.FOUND, next, Optional.of(position), inspected);
            }
        }
        Cursor next = new Cursor(cursor.area(), index, unloaded, retryAt);
        Status status = index < cursor.area().size() ? Status.SEARCHING
                : unloaded ? Status.UNLOADED : retryAt > 0L ? Status.DEFERRED : Status.EMPTY;
        return new Result(status, next, Optional.empty(), inspected);
    }
}
