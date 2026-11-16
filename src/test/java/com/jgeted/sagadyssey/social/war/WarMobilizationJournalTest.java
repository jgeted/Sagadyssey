package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.application.WarMobilizationService;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

import static com.jgeted.sagadyssey.social.war.WarMilitiaTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class WarMobilizationJournalTest {
    @TempDir Path directory;
    private final WarMobilizationCodec codec = new WarMobilizationCodec(16 * 1024 * 1024, 128);
    static void durable(Journal journal, WarMobilization record) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var reply = journal.persist(record);
            if (reply.status() == Status.APPLIED) { assertEquals(record, reply.durable().orElseThrow()); return; }
            assertEquals(Status.PENDING, reply.status(), reply.reason()); LockSupport.parkNanos(1_000_000);
        }
        fail("journal acknowledgement timed out");
    }

    @Test void aRealDurableIntentPrecedesTheFirstSharedWriteAndSurvivesALostReply() throws Exception {
        Path file = directory.resolve("militia.dat"); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var backend = new Backend(); backend.throwAfter = WarMobilization.Stage.CLAIM;
        var request = request();
        try (var journal = new WarMobilizationJournal(file, codec, (temporary, destination) -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test publication blocked"); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IOException(exception); }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        })) {
            var service = service(bindings(backend, journal)); service.begin(request, 0);
            assertEquals(WarMobilizationService.Outcome.WAITING, service.advance(request.id(), 1).outcome());
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertFalse(Files.exists(file)); assertEquals(0, backend.calls);
            release.countDown(); durable(journal, service.find(request.id()).orElseThrow());
            assertEquals(WarMobilizationService.Outcome.REVIEW, service.advance(request.id(), 2).outcome());
            assertEquals(1, backend.calls);
        } finally { release.countDown(); }
        try (var reopened = new WarMobilizationJournal(file, codec)) {
            var service = service(bindings(backend, reopened));
            assertEquals(WarMobilization.Stage.CLAIM, service.find(request.id()).orElseThrow().stage());
            assertEquals(WarMobilizationService.Outcome.PROGRESSED, service.advance(request.id(), 3).outcome());
            assertEquals(WarMobilization.Stage.OPEN_PAYROLL, service.find(request.id()).orElseThrow().stage()); assertEquals(1, backend.calls);
        }
    }

    @Test void failedPublicationPreservesTheOldFileAndDoesNotRetryUntilRequested() throws Exception {
        Path file = directory.resolve("militia.dat"); var original = WarMobilization.start(request());
        Files.write(file, codec.write(List.of(original))); byte[] old = Files.readAllBytes(file);
        AtomicInteger publications = new AtomicInteger(); AtomicBoolean repaired = new AtomicBoolean();
        try (var journal = new WarMobilizationJournal(file, codec, (temporary, destination) -> {
            publications.incrementAndGet();
            if (!repaired.get()) throw new IOException("test interrupted rename");
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        })) {
            var next = original.review("waiting for owner evidence", 1); journal.persist(next);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); WriteReply reply;
            do { LockSupport.parkNanos(1_000_000); reply = journal.persist(next); } while (reply.status() == Status.PENDING && System.nanoTime() < deadline);
            assertEquals(Status.UNKNOWN, reply.status()); assertArrayEquals(old, Files.readAllBytes(file));
            for (int i = 0; i < 10; i++) assertEquals(Status.UNKNOWN, journal.persist(next).status());
            assertEquals(1, publications.get()); repaired.set(true); journal.retry(); durable(journal, next);
            assertEquals(List.of(next), codec.read(Files.readAllBytes(file)));
        }
    }

    @Test void aPublisherThatDidNotWriteTheRequestedBytesCannotGiveADurableAcknowledgement() throws Exception {
        Path file = directory.resolve("militia.dat"); var original = WarMobilization.start(request()); Files.write(file, codec.write(List.of(original)));
        try (var journal = new WarMobilizationJournal(file, codec, (temporary, destination) -> {})) {
            var changed = original.review("new state", 1); journal.persist(changed);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); WriteReply reply;
            do { LockSupport.parkNanos(1_000_000); reply = journal.persist(changed); } while (reply.status() == Status.PENDING && System.nanoTime() < deadline);
            assertEquals(Status.UNKNOWN, reply.status()); assertEquals(List.of(original), codec.read(Files.readAllBytes(file)));
        }
    }

    @Test void temporaryAccessDenialRetriesOnlyTheSameFlushedSnapshot() throws Exception {
        Path file = directory.resolve("militia.dat");
        var original = WarMobilization.start(request());
        byte[] previous = codec.write(List.of(original));
        Files.write(file, previous);
        var next = original.review("check saved state", 1);
        var firstTemporary = new AtomicReference<Path>();
        var publications = new AtomicInteger();
        try (var journal = new WarMobilizationJournal(file, codec, (temporary, destination) -> {
            assertArrayEquals(previous, Files.readAllBytes(destination));
            if (publications.incrementAndGet() == 1) {
                firstTemporary.set(temporary);
                throw new AccessDeniedException(destination.toString());
            }
            assertEquals(firstTemporary.get(), temporary);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        })) {
            durable(journal, next);
            assertEquals(2, publications.get());
            assertEquals(List.of(next), codec.read(Files.readAllBytes(file)));
        }
    }

    @Test void persistentAccessDenialRetainsTheOldSnapshotForReview() throws Exception {
        Path file = directory.resolve("militia.dat");
        var original = WarMobilization.start(request());
        byte[] previous = codec.write(List.of(original));
        Files.write(file, previous);
        var next = original.review("check saved state", 1);
        var publications = new AtomicInteger();
        try (var journal = new WarMobilizationJournal(file, codec, (temporary, destination) -> {
            publications.incrementAndGet();
            throw new AccessDeniedException(destination.toString());
        })) {
            var reply = journal.persist(next);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (reply.status() == Status.PENDING && System.nanoTime() < deadline) {
                LockSupport.parkNanos(1_000_000);
                reply = journal.persist(next);
            }
            assertEquals(Status.UNKNOWN, reply.status());
            assertTrue(reply.reason().contains("AccessDeniedException"), reply.reason());
            assertEquals(5, publications.get());
            assertArrayEquals(previous, Files.readAllBytes(file));
        }
    }

    @Test void corruptionAndFutureSchemaNeverOpenAsEmptyWritableJournals() throws Exception {
        Path file = directory.resolve("militia.dat"); byte[] future = codec.write(List.of(WarMobilization.start(request())));
        ByteBuffer.wrap(future).putInt(4, 2); Files.write(file, future);
        assertThrows(IOException.class, () -> new WarMobilizationJournal(file, codec)); assertArrayEquals(future, Files.readAllBytes(file));
        byte[] bad = future.clone(); bad[0] ^= 1; Files.write(file, bad);
        assertThrows(IOException.class, () -> new WarMobilizationJournal(file, codec)); assertArrayEquals(bad, Files.readAllBytes(file));
    }

    @Test void historicalReceiptsAndDemobilizationCannotBeRemovedWithAHigherRevision() throws Exception {
        Path file = directory.resolve("militia.dat"); var initial = WarMobilization.start(request());
        var receipt = new WarMobilization.Receipt(initial.request().id(), initial.request().characterId(), WarMobilization.Stage.CLAIM,
                WarMobilization.operationId(initial.request().id(), WarMobilization.Stage.CLAIM), UUID.randomUUID(), 1, false);
        var claimed = initial.confirmed(receipt, 1);
        try (var journal = new WarMobilizationJournal(file, codec)) {
            durable(journal, claimed);
            var rollback = new WarMobilization(initial.request(), WarMobilization.Stage.CLAIM, 10, 2, false, Map.of(), "");
            assertEquals(Status.UNKNOWN, journal.persist(rollback).status());
            var cancellation = claimed.demobilize(2); durable(journal, cancellation);
            var revoke = new WarMobilization(initial.request(), cancellation.stage(), 11, 3, false, cancellation.receipts(), "");
            assertEquals(Status.UNKNOWN, journal.persist(revoke).status());
            assertEquals(List.of(cancellation), codec.read(Files.readAllBytes(file)));
        }
    }

    @Test void unusedJournalCreatesNothingAndWrongThreadsCannotWriteIt() throws Exception {
        Path absent = directory.resolve("no-files/militia.dat");
        try (var journal = new WarMobilizationJournal(absent, codec)) {
            assertTrue(journal.recover().isEmpty()); assertFalse(Files.exists(absent.getParent()));
            CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, () -> journal.persist(WarMobilization.start(request())))).join();
        }
        Files.write(directory.resolve("orphan.dat.old"), new byte[]{1});
        assertThrows(IOException.class, () -> new WarMobilizationJournal(directory.resolve("orphan.dat"), codec));
    }
}
