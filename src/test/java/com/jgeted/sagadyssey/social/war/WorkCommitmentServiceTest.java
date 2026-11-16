package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.work.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static com.jgeted.sagadyssey.social.work.WorkCommitment.Operation.*;
import static com.jgeted.sagadyssey.social.work.WorkCommitmentService.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkCommitmentServiceTest {
    @TempDir Path directory;
    private static WorkCommitment.Intent intent() { return new WorkCommitment.Intent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "sagadyssey:war", UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64)); }
    private static WorkCommitmentService.Reply durable(Supplier<WorkCommitmentService.Reply> action) {
        long until = System.nanoTime() + 5_000_000_000L;
        do { var reply = action.get(); if (reply.status() == APPLIED) return reply;
            assertEquals(PENDING, reply.status(), reply.reason()); LockSupport.parkNanos(1_000_000);
        } while (System.nanoTime() < until); throw new AssertionError("work journal did not become durable");
    }
    @Test void preparingAlreadyExcludesOtherWorkAndReleaseWaitsForDurability() throws Exception {
        var intent = intent(); UUID claim = UUID.randomUUID(), activate = UUID.randomUUID(), release = UUID.randomUUID();
        try (var work = new WorkCommitmentService(directory.resolve("work.sgw"))) {
            work.acquire(intent, claim, 10); assertTrue(work.holds(intent.character()));
            var held = durable(() -> work.lookup(intent, CLAIM, claim)); assertEquals(10, held.receipt().orElseThrow().occurredAt());
            durable(() -> work.advance(intent, ACTIVATE, activate, 20));
            var pending = work.advance(intent, RELEASE, release, 30); if (pending.status() == PENDING) assertTrue(work.holds(intent.character()));
            durable(() -> work.lookup(intent, RELEASE, release)); assertFalse(work.holds(intent.character()));
            assertEquals(3, work.find(intent.id()).orElseThrow().receipts().size());
        }
    }
    @Test void aCharacterAndItsOriginalBodyEachHaveOnlyOneOwnerIncludingPreparing() throws Exception {
        var first = intent(); var second = intent();
        try (var work = new WorkCommitmentService(directory.resolve("work.sgw"))) {
            UUID operation = UUID.randomUUID(); work.acquire(first, operation, 0);
            var samePerson = new WorkCommitment.Intent(second.id(), first.character(), second.body(), second.ownerKind(), second.owner(), second.principal(), second.descriptorHash());
            var sameBody = new WorkCommitment.Intent(second.id(), second.character(), first.body(), second.ownerKind(), second.owner(), second.principal(), second.descriptorHash());
            assertEquals(BUSY, work.acquire(samePerson, UUID.randomUUID(), 0).status()); assertEquals(BUSY, work.acquire(sameBody, UUID.randomUUID(), 0).status());
            durable(() -> work.lookup(first, CLAIM, operation));
        }
    }
    @Test void replayAndRestartReturnTheOriginalReceiptAndPreserveRelease() throws Exception {
        var intent = intent(); UUID operation = UUID.randomUUID(), release = UUID.randomUUID(); WorkCommitmentService.Reply original;
        Path file = directory.resolve("work.sgw");
        try (var work = new WorkCommitmentService(file)) { original = durable(() -> work.acquire(intent, operation, 5)); }
        try (var work = new WorkCommitmentService(file)) {
            assertEquals(original, work.acquire(intent, operation, 500)); assertTrue(work.holds(intent.character()));
            durable(() -> work.advance(intent, RELEASE, release, 501));
        }
        try (var work = new WorkCommitmentService(file)) { assertFalse(work.holds(intent.character())); assertEquals(original, work.lookup(intent, CLAIM, operation)); }
    }
    @Test void identitiesOperationIdsAndChronologyCannotBeChangedByRetries() throws Exception {
        var intent = intent(); UUID operation = UUID.randomUUID();
        try (var work = new WorkCommitmentService(directory.resolve("work.sgw"))) {
            durable(() -> work.acquire(intent, operation, 10));
            var changed = new WorkCommitment.Intent(intent.id(), intent.character(), intent.body(), intent.ownerKind(), intent.owner(), intent.principal(), "b".repeat(64));
            assertEquals(CONFLICT, work.acquire(changed, operation, 11).status());
            assertEquals(CONFLICT, work.lookup(intent, CLAIM, UUID.randomUUID()).status());
            assertEquals(CONFLICT, work.advance(intent, RELEASE, UUID.randomUUID(), 9).status()); assertTrue(work.holds(intent.character()));
            assertEquals(1, work.find(intent.id()).orElseThrow().receipts().size());
        }
    }
    @Test void unknownAndFutureFormatsArePreservedWithoutOpeningAnEmptyStore() throws Exception {
        var codec = new WorkCommitmentCodec(); byte[] bytes = codec.write(List.of(WorkCommitment.claim(intent(), UUID.randomUUID(), 0)));
        Path file = directory.resolve("work.sgw"); ByteBuffer.wrap(bytes).putInt(4, 99); Files.write(file, bytes);
        assertThrows(IOException.class, () -> new WorkCommitmentService(file)); assertArrayEquals(bytes, Files.readAllBytes(file));
        bytes[bytes.length - 1] ^= 1; Files.write(file, bytes); assertThrows(IOException.class, () -> new WorkCommitmentService(file));
    }
    @Test void aFailedWriteNeverAcknowledgesOrReleasesTheHeldPerson() throws Exception {
        var intent = intent(); Path file = directory.resolve("work.sgw");
        try (var work = new WorkCommitmentService(file, (temporary, destination) -> { throw new IOException("publication failure"); })) {
            UUID operation = UUID.randomUUID(); var reply = work.acquire(intent, operation, 0); long until = System.nanoTime() + 5_000_000_000L;
            while (reply.status() == PENDING && System.nanoTime() < until) { LockSupport.parkNanos(1_000_000); reply = work.lookup(intent, CLAIM, operation); }
            assertEquals(UNKNOWN, reply.status()); assertTrue(work.holds(intent.character())); assertFalse(Files.exists(file));
        }
    }
    @Test void inactiveStoresCreateNothingAndWrongThreadsCannotAcquire() throws Exception {
        Path file = directory.resolve("not-created/work.sgw");
        try (var work = new WorkCommitmentService(file)) {
            assertFalse(Files.exists(file.getParent())); assertFalse(work.holds(UUID.randomUUID()));
            CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, () -> work.acquire(intent(), UUID.randomUUID(), 0))).join();
        }
        assertFalse(Files.exists(file.getParent()));
    }
    @Test void codecRejectsDuplicatePeopleButAllowsHistoricalReleasedService() throws Exception {
        var intent = intent(); var first = WorkCommitment.claim(intent, UUID.randomUUID(), 0); var other = intent();
        var duplicate = WorkCommitment.claim(new WorkCommitment.Intent(other.id(), intent.character(), other.body(), other.ownerKind(), other.owner(), other.principal(), other.descriptorHash()), UUID.randomUUID(), 1);
        var codec = new WorkCommitmentCodec(); assertThrows(IOException.class, () -> codec.write(List.of(first, duplicate)));
        var released = first.advance(RELEASE, UUID.randomUUID(), 1); var encoded = codec.write(List.of(released, duplicate));
        assertEquals(2, codec.read(encoded).size());
    }
}
