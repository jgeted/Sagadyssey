package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarServerEvents;
import com.jgeted.sagadyssey.social.war.domain.WarCommandPost;
import com.jgeted.sagadyssey.social.war.spi.WarCommandPostPorts.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarCommandPostJournalTest {
    @TempDir Path directory;
    private static Reply write(WarCommandPostJournal j,WarCommandPost p){long end=System.nanoTime()+5_000_000_000L;do{var r=j.persist(p);if(r.status()!=Status.PENDING)return r;java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);}while(System.nanoTime()<end);throw new AssertionError("post journal stalled");}
    @Test void aCommittedBannerSurvivesFileReopenAndItsOldRevisionCannotReappear()throws Exception{
        var p=WarCommandPostTest.post();var file=directory.resolve("posts.sgw");var next=new WarCommandPost(p.unit(),p.army(),p.binder(),p.position(),p.block(),2);
        try(var j=new WarCommandPostJournal(file)){assertEquals(Status.APPLIED,write(j,p).status());assertEquals(Status.APPLIED,write(j,next).status());}
        try(var j=new WarCommandPostJournal(file)){assertEquals(List.of(next),j.recover());assertEquals(Status.UNKNOWN,j.persist(p).status());}
    }
    @Test void corruptOrFuturePostHistoryIsPreservedAndNeverResetToEmpty()throws Exception{
        var file=directory.resolve("posts.sgw");try(var j=new WarCommandPostJournal(file)){write(j,WarCommandPostTest.post());}
        var bytes=Files.readAllBytes(file);ByteBuffer.wrap(bytes).putInt(4,99);Files.write(file,bytes);
        assertThrows(java.io.IOException.class,()->new WarCommandPostJournal(file));assertArrayEquals(bytes,Files.readAllBytes(file));
    }
    @Test void orphanCommandPostsBlockCreatingAnEmptyArmyCore()throws Exception{
        Files.write(directory.resolve(WarCommandPostJournal.FILE_NAME),new byte[]{1});var data=WarServerEvents.readExisting(directory,new WarStateCodec(WarStateCodec.Limits.defaults())).orElseThrow();assertFalse(data.writable());assertEquals("COMMAND_POSTS_WITHOUT_CORE_STATE_REQUIRES_REVIEW",data.blockedReason());
    }
    @Test void typeAndDuplicatePostLimitsAreChecked()throws Exception{
        var p=WarCommandPostTest.post();var codec=new WarCommandPostCodec();assertEquals(List.of(p),codec.read(codec.write(List.of(p))));
        assertThrows(java.io.IOException.class,()->codec.write(List.of(p,p)));assertThrows(java.io.IOException.class,()->new WarDispatchCodec().read(codec.write(List.of(p))));
    }
}
