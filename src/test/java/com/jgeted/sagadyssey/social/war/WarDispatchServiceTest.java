package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.spi.WarDispatchPorts.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离日志ACK故障；组织操作用真实纯核心，替身不注册到世界。 */
class WarDispatchServiceTest {
    @Test void withdrawalAndRallyUseTheSameAuthorityTransitAndDurableReceptionAsMove() {
        var fixture=new Fixture();long tick=0;
        for(var kind:List.of(InitialWarDefinitions.RETREAT,InitialWarDefinitions.RALLY)) {
            var base=fixture.order();var command=new WarCommand.IssueOrder(base.operationId(),base.actorId(),base.armyId(),base.unitId(),base.expectedRevision(),new WarIntent(kind,1,base.intent().target()));
            assertEquals(WarDispatchService.Outcome.ACCEPTED,fixture.service.begin(command,fixture.route(tick),fixture.members,tick).outcome());
            fixture.service.advance(command.operationId(),tick+4);assertTrue(fixture.service.effective(fixture.unit,tick+4).map(v->!v.command().equals(command)).orElse(true));
            fixture.service.advance(command.operationId(),tick+5);fixture.service.advance(command.operationId(),tick+6);
            assertEquals(command,fixture.service.effective(fixture.unit,tick+6).orElseThrow().command());tick+=10;
        }
        fixture.allowed=false;var command=fixture.order();assertEquals(WarDispatchService.Outcome.DENIED,fixture.service.begin(command,fixture.route(tick),fixture.members,tick).outcome());
    }
    static final class MemoryJournal implements Journal {
        final Map<UUID,WarTransmission> values = new LinkedHashMap<>(); Predicate<WarTransmission> delay = ignored -> false;
        boolean wrong, throwing; int capacity = 64;
        public int capacity() { return capacity; }
        public Collection<WarTransmission> recover() { return List.copyOf(values.values()); }
        public Written persist(WarTransmission value) {
            if (throwing) throw new IllegalStateException("injected journal outage");
            if (delay.test(value)) return new Written(Status.PENDING, Optional.empty(), "injected pending checkpoint");
            values.put(value.command().operationId(), value); return new Written(Status.APPLIED, wrong ? Optional.empty() : Optional.of(value), "");
        }
        public void close() {}
    }
    static final class Fixture {
        final UUID actor=UUID.randomUUID(),army=UUID.randomUUID(),unit=UUID.randomUUID();
        final List<WarTransmission.Member> members=List.of(new WarTransmission.Member(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));
        final MemoryJournal journal=new MemoryJournal(); boolean allowed=true;
        WarCommandService core=new WarCommandService(InitialWarDefinitions.create(),(a,b)->allowed?WarCommandService.Permission.ALLOWED:WarCommandService.Permission.DENIED,WarCommandService.Limits.defaults(),WarState.empty());
        final WarDispatchService.Organization organization=new WarDispatchService.Organization() {
            public WarCommandService.Permission authorize(UUID a,UUID b){return allowed&&a.equals(actor)&&b.equals(army)?WarCommandService.Permission.ALLOWED:WarCommandService.Permission.DENIED;}
            public WarResult submit(WarCommand.IssueOrder c,long now){return core.submit(c,now);}
            public WarResult deliver(UUID u,long generation,UUID order,long now){return core.deliver(u,generation,order,now);}
            public Optional<WarUnit> unit(UUID u){return core.unit(u);}
        };
        final WarDispatchService service;
        Fixture(){core.submit(new WarCommand.CreateUnit(UUID.randomUUID(),actor,army,unit,InitialWarDefinitions.TROOPS.getFirst(),1),0);service=new WarDispatchService(organization,journal);}
        WarCommand.IssueOrder order(){return new WarCommand.IssueOrder(UUID.randomUUID(),actor,army,unit,core.unit(unit).orElseThrow().revision(),new WarIntent(InitialWarDefinitions.MOVE,1,point(10)));}
        WarTransmission.Route route(long now){return WarTransmission.route(point(0),point(0),now,now);}
        void receive(WarCommand.IssueOrder command,long start){assertEquals(WarDispatchService.Outcome.ACCEPTED,service.begin(command,route(start),members,start).outcome());service.advance(command.operationId(),start+5);assertEquals(WarDispatchService.Outcome.RECEIVED,service.advance(command.operationId(),start+6).outcome());}
    }
    static WarIntent.Position point(int x){return new WarIntent.Position(WarKey.parse("minecraft:overworld"),x,64,0);}
    @Test void transitAndDurableReceptionAreBothRequiredBeforeExecution(){
        var f=new Fixture();var c=f.order();assertEquals(WarDispatchService.Outcome.ACCEPTED,f.service.begin(c,f.route(0),f.members,0).outcome());
        assertTrue(f.service.effective(f.unit,0).isEmpty());assertEquals(WarDispatchService.Outcome.WAITING,f.service.advance(c.operationId(),4).outcome());assertTrue(f.core.unit(f.unit).orElseThrow().currentOrder().isEmpty());
        assertEquals(WarDispatchService.Outcome.WAITING,f.service.advance(c.operationId(),5).outcome());assertTrue(f.service.effective(f.unit,5).isEmpty());
        assertEquals(WarDispatchService.Outcome.RECEIVED,f.service.advance(c.operationId(),6).outcome());assertEquals(c.operationId(),f.service.effective(f.unit,6).orElseThrow().command().operationId());
    }
    @Test void pendingOrWrongJournalAcknowledgementsNeverAuthorizeWorldExecution(){
        var f=new Fixture();var c=f.order();f.service.begin(c,f.route(0),f.members,0);f.journal.delay=v->v.received().isEmpty();
        assertEquals(WarDispatchService.Outcome.WAITING,f.service.advance(c.operationId(),10).outcome());assertTrue(f.core.unit(f.unit).orElseThrow().currentOrder().isEmpty());
        f.journal.delay=v->v.received().isPresent();f.service.advance(c.operationId(),11);assertTrue(f.service.effective(f.unit,11).isEmpty());
        assertEquals(WarDispatchService.Outcome.WAITING,f.service.advance(c.operationId(),12).outcome());f.journal.delay=v->false;f.journal.wrong=true;
        assertEquals(WarDispatchService.Outcome.REVIEW,f.service.advance(c.operationId(),13).outcome());assertTrue(f.service.effective(f.unit,13).isEmpty());
        f.journal.wrong=false;assertEquals(WarDispatchService.Outcome.RECEIVED,f.service.advance(c.operationId(),14).outcome());
    }
    @Test void aLateCourierCannotUndoANewerReceivedOrder(){
        var f=new Fixture();var old=f.order();var far=WarTransmission.route(point(-80),point(0),0,0);f.service.begin(old,far,f.members,0);
        var newer=f.order();f.receive(newer,2);assertEquals(WarDispatchService.Outcome.SUPERSEDED,f.service.advance(old.operationId(),200).outcome());
        assertEquals(newer.operationId(),f.service.effective(f.unit,200).orElseThrow().command().operationId());
    }
    @Test void retryKeepsTheOriginalRouteAndRosterButRequiresCurrentAuthority(){
        var f=new Fixture();var c=f.order();f.service.begin(c,f.route(0),f.members,0);int operations=f.core.operationCount();
        var other=List.of(new WarTransmission.Member(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));
        var replay=f.service.begin(c,f.route(500),other,500);assertEquals(WarDispatchService.Outcome.REPLAYED,replay.outcome());assertEquals(f.members,replay.record().orElseThrow().members());assertEquals(0,replay.record().orElseThrow().route().sentAt());assertEquals(operations,f.core.operationCount());
        f.allowed=false;assertEquals(WarDispatchService.Outcome.DENIED,f.service.begin(c,f.route(500),other,500).outcome());
    }
    @Test void fullHistoryAndInvalidRosterRefuseBeforeMutatingTheCore(){
        var f=new Fixture();int operations=f.core.operationCount();var c=f.order();
        assertEquals(WarDispatchService.Outcome.UNAVAILABLE,f.service.begin(c,f.route(0),List.of(f.members.getFirst(),f.members.getFirst()),0).outcome());assertEquals(operations,f.core.operationCount());
        f.journal.capacity=1;f.service.begin(c,f.route(0),f.members,0);operations=f.core.operationCount();
        assertEquals(WarDispatchService.Outcome.FULL,f.service.begin(f.order(),f.route(1),f.members,1).outcome());assertEquals(operations,f.core.operationCount());
    }
    @Test void recoveryWithANewerReceiptAndAnOlderCoreNeverFallsBackToTheOlderOrder(){
        var f=new Fixture();var first=f.order();f.receive(first,0);var oldCore=f.core.checkpoint();var second=f.order();f.receive(second,10);
        f.core=new WarCommandService(InitialWarDefinitions.create(),(a,b)->WarCommandService.Permission.ALLOWED,WarCommandService.Limits.defaults(),oldCore);
        var recovered=new WarDispatchService(f.organization,f.journal);assertTrue(recovered.effective(f.unit,20).isEmpty());assertEquals(WarDispatchService.Outcome.REVIEW,recovered.advance(second.operationId(),20).outcome());
        assertEquals(first.operationId(),f.core.unit(f.unit).orElseThrow().currentOrder().orElseThrow().id());
    }
    @Test void operationIdConflictsCannotAlterAcceptedTransport(){
        var f=new Fixture();var c=f.order();f.service.begin(c,f.route(0),f.members,0);
        var changed=new WarCommand.IssueOrder(c.operationId(),c.generation(),c.actorId(),c.armyId(),c.unitId(),c.expectedRevision(),new WarIntent(InitialWarDefinitions.HOLD,1,point(20)));
        assertEquals(WarDispatchService.Outcome.CONFLICT,f.service.begin(changed,f.route(1),f.members,1).outcome());assertEquals(c,f.service.find(c.operationId()).orElseThrow().command());
    }
    @Test void recoveredFutureReceptionCannotActInARolledBackWorldClock(){
        var f=new Fixture();var c=f.order();f.receive(c,100);var recovered=new WarDispatchService(f.organization,f.journal);
        assertTrue(recovered.effective(f.unit,104).isEmpty());assertEquals(c.operationId(),recovered.effective(f.unit,106).orElseThrow().command().operationId());
    }
    @Test void journalExceptionsPreserveIntentAndDoNotDeliverOrPublishAnOrder(){
        var f=new Fixture();var c=f.order();f.service.begin(c,f.route(0),f.members,0);f.journal.throwing=true;
        assertEquals(WarDispatchService.Outcome.REVIEW,f.service.advance(c.operationId(),10).outcome());assertTrue(f.core.unit(f.unit).orElseThrow().currentOrder().isEmpty());
        assertEquals(c,f.service.find(c.operationId()).orElseThrow().command());assertTrue(f.service.effective(f.unit,10).isEmpty());
    }
    @Test void receivedOrdersAlsoRequireTheirIssuersCurrentAuthority(){
        var f=new Fixture();var c=f.order();f.receive(c,0);f.allowed=false;
        assertTrue(f.service.effective(f.unit,10).isEmpty());assertEquals(c,f.service.find(c.operationId()).orElseThrow().command());
        f.allowed=true;assertEquals(c.operationId(),f.service.effective(f.unit,11).orElseThrow().command().operationId());
    }
}
