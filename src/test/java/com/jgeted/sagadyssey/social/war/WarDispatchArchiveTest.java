package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class WarDispatchArchiveTest {
    @TempDir Path directory;

    private List<WarTransmission> orders() {
        var positions = new WarIntent.Position(WarKey.parse("minecraft:overworld"),4,60,8);
        var intent = new WarIntent(InitialWarDefinitions.HOLD,1,positions);
        var unit = UUID.randomUUID();
        var army = UUID.randomUUID();
        var actor = UUID.randomUUID();
        var member = new WarTransmission.Member(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        var result = new ArrayList<WarTransmission>();
        for (int i=1;i<=40;i++) {
            var command = new WarCommand.IssueOrder(UUID.randomUUID(),0,actor,army,unit,2L*i-1,intent);
            var transit = new WarTransmission(command,i,WarTransmission.route(positions,positions,i*20L,i*20L),List.of(member),Optional.empty());
            result.add(i == 40 ? transit : transit.received(new WarUnit.Order(command.operationId(),0,actor,i,intent,i*20L,i*20L+5,2L*i+1)));
        }
        return result;
    }

    @Test void legacyHotFileCompactsOnlyDurableConfirmedRecordsAndColdPageIsRequiredOnReopen() throws Exception {
        var rows = orders();
        var path = directory.resolve(WarDispatchJournal.FILE_NAME);
        Files.write(path,new WarDispatchCodec().write(rows));
        var removed = new HashSet<UUID>();
        try (var journal = new WarDispatchJournal(path)) {
            assertEquals(new HashSet<>(rows),new HashSet<>(journal.recover()));
            var retire = new ArrayList<>(rows.subList(0,32));
            retire.add(rows.getLast());
            journal.maintain(retire);
            waitFor(() -> {
                removed.addAll(journal.maintain(List.of()));
                return !removed.isEmpty() || !journal.archiveProblem().isEmpty();
            });
            assertEquals(32,removed.size());
            assertEquals(32,journal.archivedCount());
            assertFalse(removed.contains(rows.getLast().command().operationId()));
        }
        try (var reopened = new WarDispatchJournal(path)) {
            assertEquals(32,reopened.archivedCount());
            assertEquals(new HashSet<>(rows.subList(32,40)),new HashSet<>(reopened.recover()));
        }
        var bytes = Files.readAllBytes(path);
        try (var pages = Files.list(directory.resolve(WarDispatchJournal.ARCHIVE_DIRECTORY))) {
            var page = pages.findFirst().orElseThrow();
            Files.move(page,page.resolveSibling(page.getFileName()+".retained"));
        }
        assertThrows(IOException.class,() -> new WarDispatchJournal(path));
        assertArrayEquals(bytes,Files.readAllBytes(path));
    }

    @Test void failedArchivePublicationLeavesTheOriginalHotJournalByteForByte() throws Exception {
        var rows = orders();
        var path = directory.resolve(WarDispatchJournal.FILE_NAME);
        var original = new WarDispatchCodec().write(rows);
        Files.write(path,original);
        // Make page publication fail after a valid legacy journal has been opened.
        try(var journal = new WarDispatchJournal(path)) {
            Files.writeString(directory.resolve(WarDispatchJournal.ARCHIVE_DIRECTORY),"not a directory");
            journal.maintain(rows.subList(0,32));
            waitFor(() -> {
                assertTrue(journal.maintain(List.of()).isEmpty());
                return !journal.archiveProblem().isEmpty();
            });
            assertEquals(0,journal.archivedCount());
            assertArrayEquals(original,Files.readAllBytes(path));
        }
    }

    private void waitFor(BooleanSupplier condition) throws InterruptedException {
        long stop = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < stop,"archive publication timed out");
            Thread.sleep(2);
        }
    }
}
