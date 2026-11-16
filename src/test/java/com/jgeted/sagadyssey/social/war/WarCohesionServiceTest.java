package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.WarCohesion;
import com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.jgeted.sagadyssey.social.war.WarCohesionTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class WarCohesionServiceTest {
    static final class Memory implements Journal {
        final Map<UUID,WarCohesion> durable=new HashMap<>(); boolean pending,fail,badReceipt; int writes;
        public Collection<WarCohesion> recover(){return List.copyOf(durable.values());}
        public Written persist(WarCohesion value){
            writes++; if(fail)return new Written(Status.UNKNOWN,Optional.empty(),"test IO failure");
            if(pending)return new Written(Status.PENDING,Optional.empty(),"");
            var old=durable.get(value.unit()); if(old!=null&&!WarCohesion.follows(old,value))return new Written(Status.UNKNOWN,Optional.empty(),"revision conflict");
            if(badReceipt)return new Written(Status.APPLIED,Optional.empty(),"");
            durable.put(value.unit(),value);return new Written(Status.APPLIED,Optional.of(value),"");
        }
        public void close(){}
    }
    static WarCohesionService service(Memory memory){return new WarCohesionService(memory,new WarCohesionPolicy(WarCohesionPolicy.Rules.prototype()));}
    @Test void pendingAndFailedWritesNeverPublishANewTacticalDecision() {
        var journal=new Memory(); var service=service(journal); assertEquals(0,journal.writes);
        journal.pending=true; assertEquals(Status.PENDING,service.advance(frame(1,members(3,5,false),List.of())).status());
        assertTrue(service.view(id(1)).state().isEmpty()); assertTrue(journal.durable.isEmpty());
        journal.pending=false; var applied=service.advance(frame(2,members(3,90,true),List.of()));
        assertEquals(Status.APPLIED,applied.status()); assertEquals(WarCohesion.Phase.ROUTING,applied.state().orElseThrow().phase(),"acknowledge the original candidate before planning anything newer");
        journal.fail=true; assertEquals(Status.UNKNOWN,service.advance(command(frame(3,members(3,90,true),List.of()),2,WarCohesionFrame.OrderKind.RALLY)).status());
        assertEquals(applied.state(),service.view(id(1)).state());
    }
    @Test void recoveryNeedsContinuousObservedSafetyAndDoesNotCatchUpAcrossUnloading() {
        var journal=new Memory(); journal.durable.put(id(1),state(5800,WarCohesion.Phase.REGROUPING)); var service=service(journal);
        for(long now:new long[]{0,20})assertEquals(5800,service.advance(frame(now,members(3,80,true),List.of())).state().orElseThrow().organization());
        service.unobserved(id(1));
        for(long now:new long[]{1000,1020})assertEquals(5800,service.advance(frame(now,members(3,80,true),List.of())).state().orElseThrow().organization());
        assertEquals(6000,service.advance(frame(1040,members(3,80,true),List.of())).state().orElseThrow().organization());
    }
    @Test void pendingObservationKeepsOnlyTheMemberControlThatAlreadyMatchesDurableState() {
        var journal = new Memory();
        var service = service(journal);
        var roster = members(4, 90, false);
        UUID contract = roster.getFirst().contract();
        var original = service.advance(frame(1, roster, List.of())).state().orElseThrow();

        journal.pending = true;
        var fresh = frame(2, roster, List.of(wound(1000, 0, 2)));
        assertEquals(Status.PENDING, service.advance(fresh).status());
        assertEquals(original, service.controlView(id(1), contract, 3).state().orElseThrow());
        assertEquals(Status.APPLIED, service.controlView(id(1), contract, 3).status());
        assertEquals(Status.PENDING, service.controlView(id(1), contract, 43).status());
        assertEquals(original, journal.durable.get(id(1)));

        journal.pending = false;
        service.advance(fresh);
        journal.pending = true;
        var individual = new ArrayList<>(roster);
        var member = roster.getFirst();
        individual.set(0, new WarCohesionFrame.Member(member.contract(), member.character(), member.body(), true, 5, false, true));
        service.advance(frame(4, individual, fresh.encounters().getFirst().events()));
        assertEquals(Status.PENDING, service.controlView(id(1), contract, 4).status());
        assertEquals(Status.APPLIED, service.controlView(id(1), roster.get(1).contract(), 4).status());

        journal.pending = false;
        service.advance(frame(4, individual, fresh.encounters().getFirst().events()));
        journal.pending = true;
        var regroup = command(frame(5, members(4, 5, false), fresh.encounters().getFirst().events()), 2, WarCohesionFrame.OrderKind.RALLY);
        service.advance(regroup);
        assertEquals(Status.PENDING, service.controlView(id(1), roster.get(1).contract(), 5).status());
    }
    @Test void restartDiscardsUnverifiedSafeTimeButRetainsOrganizationAndShockReceipts() {
        var journal=new Memory(); journal.durable.put(id(1),state(5800,WarCohesion.Phase.REGROUPING)); var first=service(journal);
        first.advance(frame(0,members(3,80,true),List.of())); first.advance(frame(20,members(3,80,true),List.of())); first.close();
        var restarted=service(journal);
        assertEquals(5800,restarted.advance(frame(40,members(3,80,true),List.of())).state().orElseThrow().organization());
        restarted.advance(frame(60,members(3,80,true),List.of()));
        assertEquals(6000,restarted.advance(frame(80,members(3,80,true),List.of())).state().orElseThrow().organization());
    }
    @Test void pressureAndRepeatedRallyCommandsCannotFarmRecovery() {
        var journal=new Memory(); journal.durable.put(id(1),state(5800,WarCohesion.Phase.REGROUPING)); var service=service(journal);
        for(int n=1;n<=6;n++) {
            var result=service.advance(command(frame(n*20,members(3,80,true),List.of()),n+1,WarCohesionFrame.OrderKind.RALLY));
            assertEquals(5800,result.state().orElseThrow().organization());
        }
        service.advance(command(frame(140,members(3,80,true),List.of()),7,WarCohesionFrame.OrderKind.RALLY));
        service.advance(command(frame(160,members(3,80,false),List.of()),7,WarCohesionFrame.OrderKind.RALLY));
        assertEquals(5800,service.advance(command(frame(180,members(3,80,true),List.of()),7,WarCohesionFrame.OrderKind.RALLY)).state().orElseThrow().organization());
    }
    @Test void missingDurableReceiptAndChangedEvidenceStayBlockedWithoutOverwrite() {
        var bad=new Memory(); bad.badReceipt=true;
        assertEquals(Status.UNKNOWN,service(bad).advance(frame(1,members(3,90,false),List.of())).status());
        var journal=new Memory(); var service=service(journal); service.advance(frame(1,members(3,90,false),List.of(wound(1000,0,1))));
        var original=journal.durable.get(id(1)); int writes=journal.writes;
        assertEquals(Status.UNKNOWN,service.advance(frame(2,members(3,90,false),List.of(wound(1001,0,1)))).status());
        assertEquals(writes,journal.writes); assertEquals(original,journal.durable.get(id(1)));
    }
    @Test void capacityExhaustionNeverEvictsOldShockReceiptsOrResetsTheUnit() {
        var journal=new Memory(); var cursors=new HashMap<UUID,WarCohesion.Cursor>();
        for(int i=0;i<32;i++)cursors.put(id(1000+i),new WarCohesion.Cursor(1,id(2000+i)));
        var previous=new WarCohesion(id(1),id(2),1,0,2500,WarCohesion.Phase.WAVERING,id(3),1,POINT,Set.of(),cursors,Map.of());
        journal.durable.put(id(1),previous);var service=service(journal);
        assertEquals(Status.UNKNOWN,service.advance(frame(1,members(3,90,false),List.of(wound(3000,0,1)))).status());
        assertEquals(previous,journal.durable.get(id(1)));assertEquals(0,journal.writes);
    }
    @Test void anInjectedPolicyOwnsItsTimingWithoutChangingStorageOrNativeCode() {
        var memory=new Memory();memory.durable.put(id(1),state(5800,WarCohesion.Phase.REGROUPING));
        var original=new WarCohesionPolicy(WarCohesionPolicy.Rules.prototype());
        var alternate=new Policy(){
            public WarCohesion plan(Optional<WarCohesion> previous,WarCohesionFrame frame,boolean earned){return original.plan(previous,frame,earned);}
            public long recoveryTicks(){return 10;}
            public long maximumObservationGap(){return 10;}
        };
        var service=new WarCohesionService(memory,alternate);
        service.advance(frame(0,members(3,80,true),List.of()));service.advance(frame(5,members(3,80,true),List.of()));
        assertEquals(6000,service.advance(frame(10,members(3,80,true),List.of())).state().orElseThrow().organization());
    }
}
