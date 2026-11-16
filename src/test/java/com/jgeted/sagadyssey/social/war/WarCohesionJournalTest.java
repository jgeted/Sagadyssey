package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.WarCohesion;
import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarServerEvents;
import com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import static com.jgeted.sagadyssey.social.war.WarCohesionTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class WarCohesionJournalTest {
    @TempDir Path directory;
    static Written persist(WarCohesionJournal journal,WarCohesion value) {
        long end=System.nanoTime()+5_000_000_000L;
        do {var reply=journal.persist(value);if(reply.status()!=Status.PENDING)return reply;LockSupport.parkNanos(1_000_000);}while(System.nanoTime()<end);
        throw new AssertionError("cohesion journal stalled");
    }
    @Test void exactShockCursorAndLowOrganizationSurviveFileReopenAndRejectRollback() throws Exception {
        var policy=new WarCohesionPolicy(WarCohesionPolicy.Rules.prototype());
        var old=state(3000,WarCohesion.Phase.WAVERING);
        var value=policy.plan(Optional.of(old),frame(1,members(3,25,false),List.of(wound(1000,0,1))),false);
        var file=directory.resolve("cohesion.sgw");
        try(var journal=new WarCohesionJournal(file)) { assertEquals(Status.APPLIED,persist(journal,old).status()); assertEquals(Status.APPLIED,persist(journal,value).status()); }
        try(var journal=new WarCohesionJournal(file)) {
            assertEquals(List.of(value),journal.recover()); assertEquals(Status.UNKNOWN,journal.persist(old).status());
            assertSame(value,policy.plan(Optional.of(value),frame(2,members(3,25,false),List.of(wound(1000,0,1))),false));
        }
    }
    @Test void futureOrCorruptTacticalDataIsPreservedRatherThanBecomingAnEmptyUnit() throws Exception {
        var file=directory.resolve("cohesion.sgw"); try(var journal=new WarCohesionJournal(file)) {persist(journal,state(0,WarCohesion.Phase.ROUTING));}
        var bytes=Files.readAllBytes(file); ByteBuffer.wrap(bytes).putInt(4,99); Files.write(file,bytes);
        assertThrows(java.io.IOException.class,()->new WarCohesionJournal(file)); assertArrayEquals(bytes,Files.readAllBytes(file));
    }
    @Test void orphanTacticalHistoryBlocksCreationOfAnEmptyCore() throws Exception {
        Files.write(directory.resolve(WarCohesionJournal.FILE_NAME),new byte[]{1,2,3});
        var data=WarServerEvents.readExisting(directory,new WarStateCodec(WarStateCodec.Limits.defaults())).orElseThrow();
        assertFalse(data.writable()); assertEquals("TACTICS_WITHOUT_CORE_STATE_REQUIRES_REVIEW",data.blockedReason());
    }
    @Test void codecLimitsDuplicateIdsAndForeignKindsAreEnforced() throws Exception {
        var codec=new WarCohesionCodec(); var value=state(2000,WarCohesion.Phase.WAVERING);
        assertEquals(List.of(value),codec.read(codec.write(List.of(value))));
        assertThrows(java.io.IOException.class,()->codec.write(List.of(value,value)));
        assertThrows(java.io.IOException.class,()->new WarDispatchCodec().read(codec.write(List.of(value))));
        assertThrows(IllegalArgumentException.class,()->new WarCohesion(value.unit(),value.army(),1,0,10001,value.phase(),value.order(),1,POINT,Set.of(),Map.of(),Map.of()));
    }
}
