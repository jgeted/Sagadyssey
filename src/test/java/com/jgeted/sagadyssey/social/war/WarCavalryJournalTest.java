package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarServerEvents;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.spi.WarCavalryPorts.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.jgeted.sagadyssey.social.war.WarCavalryTestSupport.*;

class WarCavalryJournalTest{
    @TempDir Path directory;
    private static Written write(WarCavalryJournal j,WarCavalry v){long end=System.nanoTime()+5_000_000_000L;do{var r=j.persist(v);if(r.status()!=Status.PENDING)return r;java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);}while(System.nanoTime()<end);throw new AssertionError("cavalry IO stalled");}
    @Test void spentContactsOriginalRosterAndRecoveryDeadlineSurviveReopen()throws Exception{
        var v=spent();var file=directory.resolve("cycle.sgw");try(var j=new WarCavalryJournal(file)){assertEquals(Status.APPLIED,write(j,v).status());}try(var j=new WarCavalryJournal(file)){assertEquals(List.of(v),j.recover());assertEquals(3,j.recover().iterator().next().spent().size());}
    }
    @Test void rollbackOrCounterSkippingCannotEraseTheSpentFence()throws Exception{
        var io=new Memory();var s=service(io);var initial=run(s);var m=initial.members().getFirst();s.finish(initial.unit(),initial.order(),1,m,Optional.of(contact(60)),60);var end=s.view(initial.unit()).state().orElseThrow();var file=directory.resolve("cycle.sgw");try(var j=new WarCavalryJournal(file)){write(j,initial);write(j,end);assertEquals(Status.UNKNOWN,j.persist(initial).status());}
    }
    @Test void corruptionOrFutureEnvelopeIsPreservedInsteadOfCreatingAFreeWave()throws Exception{
        var file=directory.resolve("cycle.sgw");try(var j=new WarCavalryJournal(file)){write(j,spent());}var bytes=Files.readAllBytes(file);ByteBuffer.wrap(bytes).putInt(4,99);Files.write(file,bytes);assertThrows(java.io.IOException.class,()->new WarCavalryJournal(file));assertArrayEquals(bytes,Files.readAllBytes(file));
    }
    @Test void orphanCycleHistoryBlocksAnEmptyArmyReconstruction()throws Exception{
        Files.write(directory.resolve(WarCavalryJournal.FILE_NAME),new byte[]{1});var data=WarServerEvents.readExisting(directory,new WarStateCodec(WarStateCodec.Limits.defaults())).orElseThrow();assertFalse(data.writable());assertEquals("CAVALRY_WITHOUT_CORE_STATE_REQUIRES_REVIEW",data.blockedReason());
    }
    @Test void codecRejectsDuplicateIdsAndForeignTypedSnapshots()throws Exception{
        var codec=new WarCavalryCodec();var v=spent();assertEquals(List.of(v),codec.read(codec.write(List.of(v))));assertThrows(java.io.IOException.class,()->codec.write(List.of(v,v)));assertThrows(java.io.IOException.class,()->new WarCohesionCodec().read(codec.write(List.of(v))));
    }
}
