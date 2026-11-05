package com.jgeted.sagadyssey.npc.brain.fieldwork;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class FieldworkScanTest {
    @Test
    void scansEntireAreaIncludingTargetEightyBlocksAwayWithinPerTickBudget() {
        FieldworkSearchArea area = new FieldworkSearchArea("minecraft:overworld",
                new BlockPos(0, -60, 0), new BlockPos(80, -60, 0));
        FieldworkScan.Cursor cursor = FieldworkScan.Cursor.begin(area);
        Set<BlockPos> seen = new HashSet<>();
        FieldworkScan.Result result;
        do {
            result = FieldworkScan.advance(cursor, 16, 10L, 610L, position -> {
                assertTrue(seen.add(position));
                return position.getX() == 80 ? FieldworkScan.ProbeResult.AVAILABLE : FieldworkScan.ProbeResult.NONE;
            });
            assertTrue(result.inspected() <= 16);
            cursor = result.cursor();
        } while (result.status() == FieldworkScan.Status.SEARCHING);
        assertEquals(FieldworkScan.Status.FOUND, result.status());
        assertEquals(new BlockPos(80, -60, 0), result.target().orElseThrow());
        assertEquals(81, seen.size());
    }

    @Test
    void indexesEveryBlockExactlyOnceAcrossNegativeCoordinatesAndHeights() {
        FieldworkSearchArea area = new FieldworkSearchArea("minecraft:overworld",
                new BlockPos(-2, -61, -3), new BlockPos(1, -59, 2));
        Set<BlockPos> positions = new HashSet<>();
        for (long index = 0; index < area.size(); index++) assertTrue(positions.add(area.position(index)));
        assertEquals(72L, area.size());
        assertEquals(area.min(), area.position(0));
        assertEquals(area.max(), area.position(area.size() - 1));
        assertThrows(IllegalArgumentException.class, () -> area.position(-1L));
        assertThrows(IllegalArgumentException.class, () -> area.position(area.size()));
    }

    @Test
    void distinguishesUnloadedAreaFromTrulyEmptyAreaWithoutCallingAnyChunkLoader() {
        var area = line(3);
        var result = FieldworkScan.advance(FieldworkScan.Cursor.begin(area), 16, 20L, 620L,
                position -> position.getX() == 1 ? FieldworkScan.ProbeResult.UNLOADED : FieldworkScan.ProbeResult.NONE);
        assertEquals(FieldworkScan.Status.UNLOADED, result.status());
        assertTrue(result.cursor().sawUnloaded());
        assertEquals(4, result.inspected());
        var empty = FieldworkScan.advance(FieldworkScan.Cursor.begin(area), 16, 20L, 620L,
                position -> FieldworkScan.ProbeResult.NONE);
        assertEquals(FieldworkScan.Status.EMPTY, empty.status());
    }

    @Test
    void retainsUnloadedAndDeferredEvidenceBetweenBatches() {
        var first = FieldworkScan.advance(FieldworkScan.Cursor.begin(line(2)), 1, 10L, 610L,
                position -> FieldworkScan.ProbeResult.UNLOADED);
        var second = FieldworkScan.advance(first.cursor(), 1, 20L, 620L,
                position -> FieldworkScan.ProbeResult.DEFERRED);
        var last = FieldworkScan.advance(second.cursor(), 1, 30L, 630L,
                position -> FieldworkScan.ProbeResult.NONE);
        assertEquals(FieldworkScan.Status.UNLOADED, last.status());
        assertEquals(620L, last.cursor().retryAt());
        assertTrue(last.cursor().sawUnloaded());
    }

    @Test
    void temporarilyUnreachableOrReservedTargetsDoNotBecomeEmptyWorkZone() {
        var first = FieldworkScan.advance(FieldworkScan.Cursor.begin(line(1)), 1, 10L, 610L,
                position -> FieldworkScan.ProbeResult.DEFERRED);
        var second = FieldworkScan.advance(first.cursor(), 1, 20L, 420L,
                position -> FieldworkScan.ProbeResult.DEFERRED);
        assertEquals(FieldworkScan.Status.DEFERRED, second.status());
        assertEquals(420L, second.cursor().retryAt());
    }

    @Test
    void completedScanDoesNotWrapAndExploreForever() {
        var result = FieldworkScan.advance(FieldworkScan.Cursor.begin(line(1)), 16, 10L, 610L,
                position -> FieldworkScan.ProbeResult.NONE);
        AtomicInteger calls = new AtomicInteger();
        var repeated = FieldworkScan.advance(result.cursor(), 16, 11L, 611L, position -> {
            calls.incrementAndGet();
            return FieldworkScan.ProbeResult.AVAILABLE;
        });
        assertEquals(FieldworkScan.Status.EMPTY, repeated.status());
        assertEquals(0, calls.get());
        assertEquals(0, repeated.inspected());
    }

    @Test
    void copiesMutableBoundsAndRejectsInvalidBudgetAndCoordinateOverflow() {
        BlockPos.MutableBlockPos corner = new BlockPos.MutableBlockPos(0, -60, 0);
        var area = new FieldworkSearchArea("minecraft:overworld", corner, new BlockPos(2, -60, 0));
        corner.set(100, 80, 200);
        assertEquals(new BlockPos(0, -60, 0), area.min());
        assertThrows(IllegalArgumentException.class, () -> FieldworkScan.advance(
                FieldworkScan.Cursor.begin(area), 0, 10L, 610L, position -> FieldworkScan.ProbeResult.NONE));
        assertThrows(IllegalArgumentException.class, () -> FieldworkScan.advance(
                FieldworkScan.Cursor.begin(area), 4_097, 10L, 610L, position -> FieldworkScan.ProbeResult.NONE));
        assertThrows(IllegalArgumentException.class, () -> FieldworkScan.advance(
                FieldworkScan.Cursor.begin(area), 1, 10L, 10L, position -> FieldworkScan.ProbeResult.NONE));
        assertThrows(IllegalArgumentException.class, () -> new FieldworkSearchArea("minecraft:overworld",
                new BlockPos(1, 0, 0), BlockPos.ZERO));
        assertThrows(ArithmeticException.class, () -> new FieldworkSearchArea("minecraft:overworld",
                new BlockPos(-30_000_000, -2_048, -30_000_000), new BlockPos(30_000_000, 2_047, 30_000_000)));
    }

    @Test
    void timeSliceResumesWithoutSkippingOrRepeatingDistantTargets() {
        AtomicLong clock = new AtomicLong();
        Set<BlockPos> seen = new HashSet<>();
        var cursor = FieldworkScan.Cursor.begin(line(80));
        FieldworkScan.Result result;
        do {
            result = FieldworkScan.advance(cursor, 512, 10L, 610L, position -> {
                assertTrue(seen.add(position));
                clock.addAndGet(100L);
                return position.getX() == 80 ? FieldworkScan.ProbeResult.AVAILABLE : FieldworkScan.ProbeResult.NONE;
            }, 250L, clock::get);
            assertTrue(result.inspected() >= 1 && result.inspected() <= 3);
            cursor = result.cursor();
        } while (result.status() == FieldworkScan.Status.SEARCHING);
        assertEquals(FieldworkScan.Status.FOUND, result.status());
        assertEquals(81, seen.size());
    }

    @Test
    void slowProbeStillCommitsProgressAndRetainsUnloadedEvidence() {
        AtomicLong clock = new AtomicLong();
        var first = FieldworkScan.advance(FieldworkScan.Cursor.begin(line(1)), 512, 10L, 610L, position -> {
            clock.addAndGet(1_000L);
            return FieldworkScan.ProbeResult.UNLOADED;
        }, 1L, clock::get);
        assertEquals(FieldworkScan.Status.SEARCHING, first.status());
        assertEquals(1, first.inspected());
        assertTrue(first.cursor().sawUnloaded());
        var last = FieldworkScan.advance(first.cursor(), 512, 10L, 610L, position -> {
            clock.addAndGet(1_000L);
            return FieldworkScan.ProbeResult.NONE;
        }, 1L, clock::get);
        assertEquals(FieldworkScan.Status.UNLOADED, last.status());
        assertEquals(2, last.cursor().nextIndex());
    }

    @Test
    void timedScanRetainsHardCountLimitAndRejectsNonpositiveTimeBudget() {
        var cursor = FieldworkScan.Cursor.begin(line(80));
        var result = FieldworkScan.advance(cursor, 2, 10L, 610L,
                position -> FieldworkScan.ProbeResult.NONE, 250L, () -> 0L);
        assertEquals(2, result.inspected());
        assertThrows(IllegalArgumentException.class, () -> FieldworkScan.advanceTimed(cursor, 2, 10L, 610L,
                0L, position -> FieldworkScan.ProbeResult.NONE));
    }

    private static FieldworkSearchArea line(int lastX) {
        return new FieldworkSearchArea("minecraft:overworld", BlockPos.ZERO, new BlockPos(lastX, 0, 0));
    }
}
