package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarCommandService;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.jgeted.sagadyssey.social.war.WarCommandServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.jgeted.sagadyssey.social.war.api.WarResult.Status.*;

class WarArchiveStoreTest {
    @TempDir Path directory;
    private final WarStateCodec codec = new WarStateCodec(WarStateCodec.Limits.defaults());

    static WarState historicalState() {
        var service = service(); service.submit(create(), 0);
        for (int i = 1; i <= 3; i++) {
            var command = order(service, i); service.submit(command, i * 2L);
            if (i < 3) service.deliver(UNIT, command.operationId(), i * 2L + 1);
        }
        return service.checkpoint();
    }

    @Test void onlySupersededHistoryLeavesMemoryAndTheOriginalReceiptsStayOnDisk() throws Exception {
        WarState full = historicalState();
        try (var store = new WarArchiveStore(directory, codec)) {
            WarArchiveRef archive = store.write(full).get();
            assertEquals(full, store.read(archive));
            assertEquals(archive, store.writeNow(full));
            WarState compact = full.compact(archive);
            assertEquals(1, compact.generation()); assertEquals(full.units(), compact.units());
            assertEquals(3, compact.operations().size()); assertEquals(4, full.operations().size());
            store.verify(compact);
            var decoded = assertInstanceOf(WarStateCodec.Restored.class, codec.read(codec.write(compact))).state();
            assertEquals(compact, decoded);
            var restored = service(decoded, WarCommandService.Limits.defaults(), InitialWarDefinitions.create());
            WarState.Operation old = full.operations().values().stream()
                    .filter(op -> !compact.operations().containsKey(op.command().operationId())).findFirst().orElseThrow();
            assertEquals(RETIRED_GENERATION, restored.submit(old.command(), 10).status());
            var pending = decoded.units().get(UNIT).pendingOrders().getFirst();
            assertEquals(REPLAYED, restored.submit(decoded.operations().get(pending.id()).command(), 10).status());
            assertEquals(APPLIED, restored.deliver(UNIT, pending.generation(), pending.id(), 11).status());
        }
    }

    @Test void anOldTransportTokenCannotDeliverANewGenerationThatReusesTheSameUuid() throws Exception {
        WarState full = historicalState();
        try (var store = new WarArchiveStore(directory, codec)) {
            WarState compact = full.compact(store.writeNow(full));
            WarCommand.IssueOrder archived = (WarCommand.IssueOrder) full.operations().values().stream()
                    .filter(op -> !compact.operations().containsKey(op.command().operationId())).findFirst().orElseThrow().command();
            var service = service(compact, WarCommandService.Limits.defaults(), InitialWarDefinitions.create());
            var next = new WarCommand.IssueOrder(archived.operationId(), 1, ACTOR, ARMY, UNIT,
                    service.unit(UNIT).orElseThrow().revision(), archived.intent());
            assertEquals(APPLIED, service.submit(next, 10).status());
            WarState before = service.checkpoint();
            assertEquals(NOT_FOUND, service.deliver(UNIT, 0, archived.operationId(), 11).status());
            assertEquals(before, service.checkpoint());
            assertEquals(APPLIED, service.deliver(UNIT, 1, next.operationId(), 12).status());
            assertEquals(1, service.unit(UNIT).orElseThrow().currentOrder().orElseThrow().generation());
        }
    }

    @Test void aFutureGenerationCannotBeAcceptedAndCannotChangeArchivedState() throws Exception {
        WarState full = historicalState();
        try (var store = new WarArchiveStore(directory, codec)) {
            WarState compact = full.compact(store.writeNow(full));
            var service = service(compact, WarCommandService.Limits.defaults(), InitialWarDefinitions.create());
            var next = order(service, 10);
            var future = new WarCommand.IssueOrder(next.operationId(), 2, ACTOR, ARMY, UNIT, next.expectedRevision(), next.intent());
            assertEquals(FUTURE_GENERATION, service.submit(future, 20).status());
            assertEquals(compact, service.checkpoint());
        }
    }

    @Test void missingCorruptOrSubstitutedArchiveEvidenceBlocksRecovery() throws Exception {
        WarState full = historicalState();
        try (var store = new WarArchiveStore(directory, codec)) {
            WarArchiveRef ref = store.writeNow(full); WarState compact = full.compact(ref);
            byte[] original = Files.readAllBytes(store.path(ref));
            Files.write(store.path(ref), new byte[]{1, 2});
            assertThrows(IOException.class, () -> store.verify(compact));
            assertThrows(IOException.class, () -> store.writeNow(full));
            Files.write(store.path(ref), original);
            var badAnchors = new HashMap<>(compact.anchors());
            badAnchors.put(UNIT, new WarState.Anchor(1, 0));
            assertThrows(IllegalArgumentException.class, () -> new WarState(compact.lastChangedTick(), compact.units(),
                    compact.operations(), compact.generation(), compact.previousArchive(), badAnchors));
            Files.delete(store.path(ref));
            assertThrows(IOException.class, () -> store.verify(compact));
        }
    }

    @Test void interruptionBeforePublicationDoesNotPublishOrDestroyAnyCheckpoint() throws Exception {
        WarState full = historicalState();
        try (var store = new WarArchiveStore(directory, codec, (temporary, destination) -> {
            assertTrue(Files.size(temporary) > 44); throw new IOException("injected publication failure");
        })) {
            assertThrows(IOException.class, () -> store.writeNow(full));
            try (var paths = Files.list(directory)) { assertEquals(0, paths.count()); }
            assertEquals(full, assertInstanceOf(WarStateCodec.Restored.class, codec.read(codec.write(full))).state());
        }
    }

    @Test void aStructurallyValidCheckpointCannotRegressBeforeTheArchivedOrderOrClock() throws Exception {
        WarState full = historicalState();
        try (var store = new WarArchiveStore(directory, codec)) {
            WarState compact = full.compact(store.writeNow(full));
            var old = full.operations().values().stream().filter(op -> op.receipt().orderSequence() == 1).findFirst().orElseThrow();
            var issue = (WarCommand.IssueOrder) old.command();
            var regressed = new WarUnit.Order(issue.operationId(), issue.actorId(), 1, issue.intent(), old.receipt().appliedAtTick(), 3, 3);
            var unit = compact.units().get(UNIT); var current = unit.currentOrder().orElseThrow();
            var undelivered = new WarUnit.Order(current.id(), current.issuerId(), current.sequence(), current.intent(), current.issuedAtTick(), -1, 0);
            var operations = new HashMap<>(compact.operations()); operations.put(old.command().operationId(), old);
            var rollback = new WarUnit(UNIT, ARMY, unit.troopType(), unit.definitionVersion(), unit.revision(), unit.lastIssuedSequence(),
                    Optional.of(regressed), List.of(undelivered, unit.pendingOrders().getFirst()));
            var state = new WarState(compact.lastChangedTick(), Map.of(UNIT, rollback), operations,
                    compact.generation(), compact.previousArchive(), compact.anchors());
            assertThrows(IOException.class, () -> store.verify(state));

            var service = service(full, WarCommandService.Limits.defaults(), InitialWarDefinitions.create());
            UUID another = UUID.randomUUID();
            service.submit(new WarCommand.CreateUnit(UUID.randomUUID(), ACTOR, ARMY, another, unit.troopType(), 1), 20);
            WarState later = service.checkpoint(); compact = later.compact(store.writeNow(later));
            var pending = compact.units().get(UNIT).pendingOrders().getFirst();
            var tooEarly = new WarUnit.Order(pending.id(), pending.issuerId(), pending.sequence(), pending.intent(),
                    pending.issuedAtTick(), 10, unit.revision() + 1);
            var changedUnits = new HashMap<>(compact.units());
            changedUnits.put(UNIT, new WarUnit(UNIT, ARMY, unit.troopType(), unit.definitionVersion(), unit.revision() + 1,
                    unit.lastIssuedSequence(), Optional.of(tooEarly), List.of()));
            var earlyState = new WarState(20, changedUnits, compact.operations(), compact.generation(), compact.previousArchive(), compact.anchors());
            assertThrows(IOException.class, () -> store.verify(earlyState));
        }
    }

    @Test void constructingOrReadingAnEmptyStoreCreatesNeitherDirectoryNorWorkerFile() throws Exception {
        Path absent = directory.resolve("not-created");
        try (var store = new WarArchiveStore(absent, codec)) {
            store.verify(WarState.empty()); assertFalse(Files.exists(absent));
        }
    }

    @Test void archiveIoBacklogIsBoundedAndExcessRequestsFailWithoutBlockingTheOwner() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var store = new WarArchiveStore(directory, codec, (temporary, destination) -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test release timed out"); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IOException(exception); }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        })) {
            var first = store.write(historicalState());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = store.write(historicalState());
            var excess = store.write(historicalState());
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(CompletionException.class, excess::join).getCause());
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }
}
