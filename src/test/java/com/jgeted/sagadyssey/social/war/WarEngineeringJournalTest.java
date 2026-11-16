package com.jgeted.sagadyssey.social.war;
import com.jgeted.sagadyssey.social.war.domain.WarEngineeringJob;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarEngineeringCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WarEngineeringJournalTest{
    @TempDir Path folder;
    @Test void originalPeopleAndOpaqueBlockIdentitiesSurviveRealReopen()throws Exception{var j=WarEngineeringRulesTest.job().change(WarEngineeringJob.Phase.ISSUING,101,"saved before effect");var codec=new WarEngineeringCodec();var file=folder.resolve("work.sgw");Files.write(file,codec.write(List.of(j)));assertEquals(List.of(j),List.copyOf(codec.read(Files.readAllBytes(file))));}
    @Test void corruptionDoesNotBecomeAnEmptySuccessfulWorkList()throws Exception{var codec=new WarEngineeringCodec();var bytes=codec.write(List.of(WarEngineeringRulesTest.job()));bytes[bytes.length-1]^=1;assertThrows(Exception.class,()->codec.read(bytes));}
    @Test void theSameOriginalBodyCannotReserveTwoMilitaryJobs()throws Exception{var a=WarEngineeringRulesTest.job();var b=new WarEngineeringJob(new UUID(WarEngineeringJob.NAMESPACE,2),a.army(),a.owner(),UUID.randomUUID(),a.person(),a.body(),a.mode(),a.material(),a.cells(),a.phase(),a.next(),a.adopted(),a.createdAt(),a.revision(),a.changedAt(),false,a.reason());assertThrows(Exception.class,()->new WarEngineeringCodec().write(List.of(a,b)));}
    @Test void closedHistoryAndNewWorkAreDistinctAndDuplicateCellsAreRejected()throws Exception{var a=WarEngineeringRulesTest.job().change(WarEngineeringJob.Phase.CANCELLED,101,"closed without effects");assertEquals(List.of(a),List.copyOf(new WarEngineeringCodec().read(new WarEngineeringCodec().write(List.of(a)))));assertThrows(IllegalArgumentException.class,()->new WarEngineeringJob(a.id(),a.army(),a.owner(),a.service(),a.person(),a.body(),a.mode(),a.material(),List.of(a.cells().getFirst(),a.cells().getFirst()),WarEngineeringJob.Phase.ACTIVE,0,Set.of(),100,1,100,false,"duplicate actual target"));}
}
