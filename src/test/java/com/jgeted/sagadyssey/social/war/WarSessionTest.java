package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarCommandService;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.runtime.WarSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.jgeted.sagadyssey.social.war.WarCommandServiceTest.*;
import static com.jgeted.sagadyssey.social.war.api.WarResult.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class WarSessionTest {
    @TempDir Path directory;
    private final WarStateCodec codec = new WarStateCodec(WarStateCodec.Limits.defaults());

    private WarSession session(WarState state, Function<WarState, CompletableFuture<WarArchiveRef>> writer, Runnable changed) {
        return new WarSession(InitialWarDefinitions.create(), (actor, army) -> WarCommandService.Permission.ALLOWED,
                WarCommandService.Limits.defaults(), state, true, writer, changed);
    }

    @Test void completedBackgroundIoDoesNotPublishUntilTheOwnerThreadTicks() throws Exception {
        WarState original = WarArchiveStoreTest.historicalState();
        CompletableFuture<WarArchiveRef> future = new CompletableFuture<>(); AtomicInteger notifications = new AtomicInteger();
        try (var store = new WarArchiveStore(directory, codec);
             var session = session(original, state -> future, notifications::incrementAndGet)) {
            assertTrue(session.archive());
            assertEquals(ARCHIVING, session.submit(create(), 20).status());
            var reference = store.writeNow(original);
            CompletableFuture.runAsync(() -> future.complete(reference)).join();
            assertEquals(0, session.generation()); assertEquals(original, session.checkpoint());
            assertEquals(0, notifications.get());
            session.tick();
            assertEquals(1, session.generation()); assertEquals(1, notifications.get());
            store.verify(session.checkpoint());
        }
    }

    @Test void failedArchiveKeepsTheFullStateAndRequiresAnExplicitRetry() {
        WarState original = WarArchiveStoreTest.historicalState(); AtomicInteger attempts = new AtomicInteger();
        try (var session = session(original, state -> {
            attempts.incrementAndGet(); return CompletableFuture.failedFuture(new IOException("disk unavailable"));
        }, () -> {})) {
            assertTrue(session.archive()); session.tick();
            for (int i = 0; i < 20; i++) session.tick();
            assertEquals(1, attempts.get()); assertFalse(session.problem().isEmpty());
            assertEquals(original, session.checkpoint()); assertEquals(WarSession.Phase.ACTIVE, session.phase());
            assertTrue(session.archive()); session.tick(); assertEquals(2, attempts.get());
            assertEquals(original, session.checkpoint());
        }
    }

    @Test void pauseAndCloseCannotPublishACompletedButUncommittedArchive() throws Exception {
        WarState original = WarArchiveStoreTest.historicalState(); CompletableFuture<WarArchiveRef> future = new CompletableFuture<>();
        try (var store = new WarArchiveStore(directory, codec)) {
            var session = session(original, state -> future, () -> {});
            assertTrue(session.archive()); session.pause();
            future.complete(store.writeNow(original)); session.tick();
            assertEquals(original, session.checkpoint());
            assertEquals(UNAVAILABLE, session.submit(create(), 10).status());
            session.resume(); assertEquals(WarSession.Phase.ACTIVE, session.phase());
            session.close(); session.close(); session.tick();
            assertEquals(WarSession.Phase.CLOSED, session.phase()); assertEquals(original, session.checkpoint());
        }
    }

    @Test void repeatedArchivesAllowLongPlayWithoutGrowingTheHotJournalOrRegressingOrders() throws Exception {
        var limits = new WarCommandService.Limits(1, 4, 2);
        try (var store = new WarArchiveStore(directory, codec);
             var session = new WarSession(InitialWarDefinitions.create(), (actor, army) -> WarCommandService.Permission.ALLOWED,
                     limits, WarState.empty(), true, state -> {
                         try { return CompletableFuture.completedFuture(store.writeNow(state)); }
                         catch (IOException exception) { return CompletableFuture.failedFuture(exception); }
                     }, () -> {})) {
            session.submit(create(), 0);
            for (int i = 1; i <= 40; i++) {
                var intent = new WarIntent(InitialWarDefinitions.MOVE, 1,
                        new WarIntent.Position(WarKey.parse("minecraft:overworld"), i, 64, 0));
                var command = new WarCommand.IssueOrder(UUID.randomUUID(), session.generation(), ACTOR, ARMY, UNIT,
                        session.unit(UNIT).orElseThrow().revision(), intent);
                assertEquals(APPLIED, session.submit(command, i * 2L).status());
                assertEquals(APPLIED, session.deliver(UNIT, command.generation(), command.operationId(), i * 2L + 1).status());
                session.tick();
                assertTrue(session.checkpoint().operations().size() <= limits.maxOperations());
                assertEquals(i, session.unit(UNIT).orElseThrow().currentOrder().orElseThrow().sequence());
                store.verify(session.checkpoint());
            }
            assertTrue(session.generation() > 10);
            WarState state = assertInstanceOf(WarStateCodec.Restored.class, codec.read(codec.write(session.checkpoint()))).state();
            assertEquals(40, state.units().get(UNIT).lastIssuedSequence());
        }
    }

    @Test void aPinnedOnlyFullJournalDoesNotContinuouslyAttemptUselessArchives() {
        AtomicInteger attempts = new AtomicInteger();
        var limits = new WarCommandService.Limits(1, 2, 1);
        try (var session = new WarSession(InitialWarDefinitions.create(), (actor, army) -> WarCommandService.Permission.ALLOWED,
                limits, WarState.empty(), true, state -> { attempts.incrementAndGet(); return new CompletableFuture<>(); }, () -> {})) {
            session.submit(create(), 0);
            var intent = new WarIntent(InitialWarDefinitions.MOVE, 1, new WarIntent.Position(WarKey.parse("minecraft:overworld"), 0, 64, 0));
            session.submit(new WarCommand.IssueOrder(UUID.randomUUID(), ACTOR, ARMY, UNIT, 1, intent), 1);
            for (int i = 0; i < 30; i++) session.tick();
            assertEquals(0, attempts.get()); assertEquals(2, session.checkpoint().operations().size());
        }
    }

    @Test void sessionsRemainIsolatedAndCannotBeMutatedFromAWorkerThread() {
        try (var first = session(WarState.empty(), state -> new CompletableFuture<>(), () -> {});
             var second = session(WarState.empty(), state -> new CompletableFuture<>(), () -> {})) {
            first.submit(create(), 0);
            assertTrue(second.checkpoint().units().isEmpty());
            CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, first::tick)).join();
            assertEquals(1, first.checkpoint().units().size());
        }
    }

    @Test void loweringLimitsBelowPersistedHistoryCannotSilentlyTruncateIt() {
        WarState original = WarArchiveStoreTest.historicalState();
        assertThrows(IllegalArgumentException.class, () -> new WarSession(InitialWarDefinitions.create(),
                (actor, army) -> WarCommandService.Permission.ALLOWED, new WarCommandService.Limits(1, 2, 1),
                original, true, state -> new CompletableFuture<>(), () -> {}));
        assertEquals(4, original.operations().size()); assertEquals(3, original.units().get(UNIT).lastIssuedSequence());
    }
}
