package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.spi.WarDispatchPorts.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import static org.junit.jupiter.api.Assertions.*;

class WarDispatchJournalTest {
    @TempDir Path directory;
    static WarTransmission value(){var f=new WarDispatchServiceTest.Fixture();return new WarTransmission(f.order(),1,f.route(0),f.members,Optional.empty());}
    static Written persist(WarDispatchJournal journal,WarTransmission value){long end=System.nanoTime()+5_000_000_000L;do{var reply=journal.persist(value);if(reply.status()!=Status.PENDING)return reply;LockSupport.parkNanos(1_000_000);}while(System.nanoTime()<end);throw new AssertionError("dispatch journal stalled");}
    @Test void exactTransitAndReceptionSurviveARealFileReopen()throws Exception{
        var file=directory.resolve("dispatch.sgw");var first=value();var c=first.command();var received=first.received(new WarUnit.Order(c.operationId(),c.generation(),c.actorId(),1,c.intent(),0,5,3));
        try(var journal=new WarDispatchJournal(file)){assertEquals(Status.APPLIED,persist(journal,first).status());assertEquals(Status.APPLIED,persist(journal,received).status());}
        try(var journal=new WarDispatchJournal(file)){assertEquals(List.of(received),journal.recover());assertEquals(Status.UNKNOWN,journal.persist(first).status());}
    }
    @Test void futureOrCorruptLogsRefuseWithoutReplacingTheOriginal()throws Exception{
        var file=directory.resolve("dispatch.sgw");try(var journal=new WarDispatchJournal(file)){persist(journal,value());}
        var bytes=Files.readAllBytes(file);ByteBuffer.wrap(bytes).putInt(4,99);Files.write(file,bytes);
        assertThrows(java.io.IOException.class,()->new WarDispatchJournal(file));assertArrayEquals(bytes,Files.readAllBytes(file));
    }
    @Test void duplicateUnitSequencesAndForeignEnvelopeKindsAreRefused()throws Exception{
        var first=value();var f=first.command();var second=new WarTransmission(new com.jgeted.sagadyssey.social.war.api.WarCommand.IssueOrder(UUID.randomUUID(),f.generation(),f.actorId(),f.armyId(),f.unitId(),f.expectedRevision(),f.intent()),1,first.route(),first.members(),Optional.empty());
        assertThrows(java.io.IOException.class,()->new WarDispatchCodec().write(List.of(first,second)));
        var bytes=new WarDispatchCodec().write(List.of(first));assertThrows(java.io.IOException.class,()->new com.jgeted.sagadyssey.social.work.WorkCommitmentCodec().read(bytes));
    }
}
