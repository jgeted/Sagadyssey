package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarStaffJournalTest {
    @TempDir Path root;
    @Test void realFileReopeningKeepsExactTermsPhaseAndReviewAndDetectsCorruption()throws Exception{
        var e=WarStaffBookTest.establishment(24);var a=WarStaffBookTest.appointment(UUID.randomUUID(),WarStaffBookTest.PERSON,Role.DEPUTY);var active=a.advance(Phase.ACTIVE,11,"exact shared office checkpoint");var path=root.resolve("staff.sgw");
        try(var j=new WarStaffJournal(path)){j.persist(e);j.persist(a);j.persist(active);}
        try(var j=new WarStaffJournal(path)){assertEquals(Set.of(e,active),Set.copyOf(j.recover()));}
        byte[] bytes=Files.readAllBytes(path);bytes[bytes.length-1]^=1;Files.write(path,bytes);byte[] evidence=Files.readAllBytes(path);
        assertThrows(Exception.class,()->new WarStaffJournal(path));assertArrayEquals(evidence,Files.readAllBytes(path));
    }
    @Test void aMissingPrimaryWithBackupIsNotRebuiltAsAnEmptyStaffBook()throws Exception{
        var path=root.resolve("staff.sgw");try(var j=new WarStaffJournal(path)){j.persist(WarStaffBookTest.establishment(24));}
        Files.move(path,path.resolveSibling("staff.sgw.old"));assertThrows(Exception.class,()->new WarStaffJournal(path));assertFalse(Files.exists(path));
    }
    @Test void codecRetainsScopesAndRejectsDuplicateLogicalIdentities()throws Exception{
        var codec=new WarStaffCodec();var e=WarStaffBookTest.establishment(24);var captain=WarStaffBookTest.appointment(UUID.randomUUID(),WarStaffBookTest.PERSON,Role.CAPTAIN);
        assertEquals(Set.of(e,captain),Set.copyOf(codec.read(codec.write(List.of(e,captain)))));assertThrows(Exception.class,()->codec.write(List.of(e,e)));
        assertThrows(IllegalArgumentException.class,()->new Establishment(UUID.randomUUID(),UUID.randomUUID(),1,1,Map.of(UUID.randomUUID(),1024,UUID.randomUUID(),1)));
    }
}
