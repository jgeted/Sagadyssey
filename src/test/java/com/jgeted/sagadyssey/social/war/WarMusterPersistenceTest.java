package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.application.WarMusterBook;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.*;
import com.jgeted.sagadyssey.social.war.spi.WarMusterPorts.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WarMusterPersistenceTest {
    @TempDir Path directory;
    private static void durable(WarMusterBook b,WarMusterEntry e){assertNotEquals(Status.UNKNOWN,b.put(e,10).status());long end=System.nanoTime()+5_000_000_000L;
        while(!b.durable(e)&&System.nanoTime()<end){b.poll(4);java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);}assertTrue(b.durable(e));}
    @Test void aSingleRecordedDecisionSurvivesReopeningWithoutASamplingCallback()throws Exception{
        var path=directory.resolve("muster.sgw");var a=WarMusterBookTest.army();var u=WarMusterBookTest.unit();var o=WarMusterBookTest.offer();
        try(var b=new WarMusterBook(new WarMusterJournal(path))){durable(b,a);durable(b,u);durable(b,u.acknowledged());durable(b,o);durable(b,o.advance(Stage.GRANTING,11,"yes"));}
        try(var b=new WarMusterBook(new WarMusterJournal(path))){var restored=b.offer(o.id()).orElseThrow();assertEquals(Stage.GRANTING,restored.stage());assertEquals(o.draw(),restored.draw());assertEquals(o.probability(),restored.probability());assertTrue(b.owns(a.owner(),a.id()));}
    }
    @Test void corruptedFutureAndWrongTypedFilesArePreserved()throws Exception{
        var codec=new WarMusterCodec();var good=codec.write(List.of(WarMusterBookTest.army()));var path=directory.resolve("muster.sgw");
        var corrupt=good.clone();corrupt[corrupt.length-1]^=1;Files.write(path,corrupt);assertThrows(IOException.class,()->new WarMusterJournal(path));assertArrayEquals(corrupt,Files.readAllBytes(path));
        var future=good.clone();ByteBuffer.wrap(future).putInt(4,99);Files.write(path,future);assertThrows(IOException.class,()->new WarMusterJournal(path));assertArrayEquals(future,Files.readAllBytes(path));
        assertThrows(IOException.class,()->new WarCommandPostCodec().read(good));
    }
    @Test void missingPrimaryWithBackupIsNotAnEmptyArmy()throws Exception{var path=directory.resolve("muster.sgw");Files.write(path.resolveSibling("muster.sgw.old"),new byte[]{1});assertThrows(IOException.class,()->new WarMusterJournal(path));assertFalse(Files.exists(path));}
    @Test void exactRoundtripRetainsAllTermsAndDuplicateIdentitiesAreRejected()throws Exception{var c=new WarMusterCodec();var o=WarMusterBookTest.offer();assertEquals(List.of(o),c.read(c.write(List.of(o))));assertThrows(IOException.class,()->c.write(List.of(o,o)));}
}
