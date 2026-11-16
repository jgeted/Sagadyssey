package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.spi.WarCavalryPorts.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.jgeted.sagadyssey.social.war.WarCavalryTestSupport.*;

class WarCavalryServiceTest{
    @Test void everyOriginalMemberMustPhysicallyReformBeforeTheFirstWave(){
        var s=service(new Memory());s.advance(frame(0,true,true,false));s.advance(frame(20,false,true,false));s.advance(frame(40,true,true,false));s.advance(frame(60,true,true,false));assertEquals(WarCavalry.Phase.REFORM,s.view(id(1)).state().orElseThrow().phase());s.advance(frame(80,true,true,false));assertEquals(WarCavalry.Phase.RUN,s.view(id(1)).state().orElseThrow().phase());
    }
    @Test void lowOrganizationCannotLaunchEvenWithAllBodiesAvailableAndAssembled(){
        var s=service(new Memory());for(int i=0;i<=120;i+=20)s.advance(organization(frame(i,true,true,false),0));assertEquals(0,s.view(id(1)).state().orElseThrow().wave());
    }
    @Test void aContactRequiresActualRunRecentForwardMotionAndMatchingOriginalMember(){
        var s=service(new Memory());var v=run(s);var m=v.members().getFirst();
        for(var c:List.of(new Contact(id(80),id(81),60,60,0,.3,1),new Contact(id(80),id(81),60,50,10,.3,1),new Contact(id(80),id(81),60,60,10,0,1),new Contact(id(80),id(81),60,60,10,.3,-1)))assertEquals(Status.UNAVAILABLE,s.finish(v.unit(),v.order(),v.wave(),m,Optional.of(c),60).status());
        assertEquals(Status.UNAVAILABLE,s.finish(v.unit(),v.order(),v.wave(),new WarTransmission.Member(m.contract(),m.character(),id(99)),Optional.of(contact(60)),60).status());assertTrue(s.view(v.unit()).state().orElseThrow().spent().isEmpty());
    }
    @Test void durableContactIsTakenOnceAndCannotBeRepeatedInTheSameWave(){
        var s=service(new Memory());var v=run(s);var m=v.members().getFirst();assertEquals(Status.APPLIED,s.finish(v.unit(),v.order(),v.wave(),m,Optional.of(contact(60)),60).status());assertTrue(s.take(v.unit(),v.order(),v.wave(),m,60).isPresent());assertTrue(s.take(v.unit(),v.order(),v.wave(),m,61).isEmpty());assertEquals(Status.UNAVAILABLE,s.finish(v.unit(),v.order(),v.wave(),m,Optional.of(contact(61)),61).status());
    }
    @Test void pendingIoCannotAuthorizeContactBeforeTheExactFenceIsAcknowledged(){
        var io=new Memory();var s=service(io);var v=run(s);var m=v.members().getFirst();io.pending=true;assertEquals(Status.PENDING,s.finish(v.unit(),v.order(),v.wave(),m,Optional.of(contact(60)),60).status());assertTrue(s.take(v.unit(),v.order(),v.wave(),m,60).isEmpty());io.pending=false;s.poll(v.unit());assertTrue(s.take(v.unit(),v.order(),v.wave(),m,61).isPresent());
    }
    @Test void aPauseWhileContactIoIsPendingCannotBeUndoneByItsLateAcknowledgement(){
        var io=new Memory();var s=service(io);var v=run(s);var m=v.members().getFirst();io.pending=true;s.finish(v.unit(),v.order(),v.wave(),m,Optional.of(contact(60)),60);s.unobserved(v.unit());io.pending=false;assertEquals(Status.UNAVAILABLE,s.poll(v.unit()).status());assertTrue(s.take(v.unit(),v.order(),v.wave(),m,61).isEmpty());s.advance(frame(70,true,false,false));assertEquals(WarCavalry.Phase.REFORM,s.view(v.unit()).state().orElseThrow().phase());
    }
    @Test void restartDiscardsTransientContactAndRequiresPhysicalReformation(){
        var io=new Memory();var first=service(io);var v=run(first);var m=v.members().getFirst();first.finish(v.unit(),v.order(),v.wave(),m,Optional.of(contact(60)),60);first.close();var s=service(io);assertEquals(Status.UNAVAILABLE,s.view(v.unit()).status());assertTrue(s.take(v.unit(),v.order(),v.wave(),m,61).isEmpty());s.advance(frame(70,true,false,false));var recovered=s.view(v.unit()).state().orElseThrow();assertEquals(WarCavalry.Phase.REFORM,recovered.phase());assertEquals(3,recovered.spent().size());assertTrue(recovered.nextLaunchAt()>=270);
    }
    @Test void unavailableOrOverdueRunEndsWithoutAContactBonus(){
        var s=service(new Memory());run(s);s.advance(frame(60,false,false,false));assertEquals(3,s.view(id(1)).state().orElseThrow().spent().size());
        var t=service(new Memory());run(t);for(int now=60;now<=440;now+=20)t.advance(frame(now,true,false,false));assertEquals(3,t.view(id(1)).state().orElseThrow().spent().size());
    }
    @Test void newOrdersCannotResetWaveNumberOrTheUnitRecoveryDeadline(){
        var s=service(new Memory());var v=run(s);s.advance(order(frame(60,true,true,false),2));var changed=s.view(v.unit()).state().orElseThrow();assertEquals(v.wave(),changed.wave());assertTrue(changed.nextLaunchAt()>=v.nextLaunchAt());for(int now=80;now<260;now+=20)s.advance(order(frame(now,true,true,false),2));assertEquals(v.wave(),s.view(v.unit()).state().orElseThrow().wave());
    }
    @Test void aWholeWaveMustExitAndRegroupBeforeAnotherCanStart(){
        var s=service(new Memory());var v=run(s);for(var m:v.members())s.finish(v.unit(),v.order(),v.wave(),m,Optional.empty(),60);
        for(int now=60;now<=260;now+=20)s.advance(frame(now,true,false,true));assertEquals(1,s.view(v.unit()).state().orElseThrow().wave());for(int now=280;now<=320;now+=20)s.advance(frame(now,true,true,true));assertEquals(2,s.view(v.unit()).state().orElseThrow().wave());
    }
    @Test void staleObservationDoesNotGrantOfflineReformationOrContinueAnOldRun(){
        var s=service(new Memory());run(s);s.advance(frame(140,true,false,false));var state=s.view(id(1)).state().orElseThrow();assertEquals(WarCavalry.Phase.REFORM,state.phase());assertEquals(3,state.spent().size());
    }
    @Test void anObstructedRunReturnsDirectlyInsteadOfAdvancingToTheFrontExitWaypoint(){
        var s=service(new Memory());var v=run(s);var m=v.members().getFirst();s.abort(v.unit(),v.order(),v.wave(),m,60);var aborted=s.view(v.unit()).state().orElseThrow();assertTrue(aborted.spent().contains(m.contract())&&aborted.returning().contains(m.contract()));assertTrue(s.take(v.unit(),v.order(),v.wave(),m,60).isEmpty());
    }
    @Test void failureMalformedReceiptOrThrowingJournalStaysReviewWithoutContact(){
        for(int mode=0;mode<3;mode++){var io=new Memory();var s=service(io);var v=run(s);io.failed=mode==0;io.bad=mode==1;io.thrown=mode==2;var m=v.members().getFirst();assertEquals(Status.UNKNOWN,s.finish(v.unit(),v.order(),v.wave(),m,Optional.of(contact(60)),60).status());assertTrue(s.take(v.unit(),v.order(),v.wave(),m,60).isEmpty());}
    }
}
