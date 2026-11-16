package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.runtime.WarWorkQueue;
import com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.*;
import com.jgeted.sagadyssey.worldsim.transfer.ItemLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.io.IOException;
import java.util.*;
import java.util.function.*;

/** Original-body observation adapter. One quota operation observes one member or one bounded event stream. */
public final class WarCohesionRuntime implements AutoCloseable {
    public record Control(Status status, boolean withdraw, boolean forced, Optional<WarIntent.Position> destination, String reason) {}
    private final MinecraftServer server;
    private final WarServerRuntime core;
    private final WarMobilizationRuntime militia;
    private final WarFieldRuntime.Sources sources;
    private final CombatMoraleData morale;
    private final com.jgeted.sagadyssey.social.war.spi.WarMobilityPorts.Mobility<NpcBase> mobility;
    private final BiFunction<UUID,Long,Optional<WarTransmission>> effective;
    private final BooleanSupplier active;
    private final WarCohesionService service;
    private final WarLeadershipRuntime leadership;
    private static final int MAXIMUM_AUDIT_TICKS = 120;
    private static final int FRESH_CONTROL_TICKS = 160;
    private final WarWorkQueue work=new WarWorkQueue(new WarWorkQueue.Limits(1024,8,12,2_000_000),System::nanoTime);
    private final Map<UUID,Long> nextSample=new HashMap<>(), observed=new HashMap<>();
    private final Set<UUID> queued=new HashSet<>();
    private boolean wasActive;
    public WarCohesionRuntime(MinecraftServer server,WarServerRuntime core,WarMobilizationRuntime militia,WarFieldRuntime.Sources sources,
                              CombatMoraleData morale,BiFunction<UUID,Long,Optional<WarTransmission>> effective,BooleanSupplier active,
                              JournalFactory journals,Policy policy,com.jgeted.sagadyssey.social.war.spi.WarMobilityPorts.Mobility<NpcBase> mobility,
                              WarLeadershipRuntime leadership) throws IOException {
        this.server=server; this.core=core; this.militia=militia; this.sources=sources; this.morale=morale; this.effective=effective; this.active=active;
        this.mobility=Objects.requireNonNull(mobility);
        this.leadership=Objects.requireNonNull(leadership);
        var journal=journals.open();
        try {
            for(var state:journal.recover()) {
                if(core.session().flatMap(s->s.unit(state.unit())).filter(u->u.armyId().equals(state.army())).isEmpty())
                    throw new IOException("orphan tactical checkpoint; explicit reconciliation required");
            }
            service=new WarCohesionService(journal,policy);
        } catch(IOException|RuntimeException failure) { journal.close(); throw failure; }
    }
    public void touch(WarTransmission instruction,long now) {
        UUID unit=instruction.command().unitId();
        if(queued.contains(unit) || now<nextSample.getOrDefault(unit,0L) || service.view(unit).status()==Status.UNKNOWN) return;
        var audit=new Audit(instruction,now);
        var result=work.submit(unit,audit::step,now);
        if(result==WarWorkQueue.Submission.ACCEPTED) { queued.add(unit); nextSample.put(unit,now+20); }
        else if(result!=WarWorkQueue.Submission.DUPLICATE) service.block(unit,"TACTICAL_OBSERVATION_CAPACITY_REACHED");
    }
    public Control control(WarTransmission instruction,WarTransmission.Member member,long now) {
        UUID unit=instruction.command().unitId(); var view=service.controlView(unit,member.contract(),now);
        var failed=work.blockedReason(unit);
        if(failed.isPresent()) return new Control(Status.UNKNOWN,false,false,Optional.empty(),failed.get());
        if(view.status()!=Status.APPLIED) return new Control(view.status(),false,false,Optional.empty(),view.reason());
        var state=view.state().orElseThrow();
        if(!state.order().equals(instruction.command().operationId()) || now<observed.getOrDefault(unit,Long.MAX_VALUE)
                || now-observed.getOrDefault(unit,0L)>FRESH_CONTROL_TICKS)
            return new Control(Status.UNAVAILABLE,false,false,Optional.empty(),"FRESH_TACTICAL_OBSERVATION_REQUIRED");
        boolean forced=state.phase()==WarCohesion.Phase.ROUTING || state.routed().contains(member.contract());
        boolean withdrawing=forced || state.phase()==WarCohesion.Phase.WITHDRAWING || state.phase()==WarCohesion.Phase.REGROUPING;
        return new Control(Status.APPLIED,withdrawing,forced,withdrawing?Optional.of(instruction.station(member,state.rally())):Optional.empty(),
                forced?"UNIT_OR_SHARED_MORALE_RETREAT":state.phase()==WarCohesion.Phase.WITHDRAWING?"ORDERED_WITHDRAWAL":"ACTUAL_REGROUPING");
    }
    public Optional<WarCohesion> state(UUID unit) { return service.view(unit).state(); }
    public WarWorkQueue.Snapshot budget() { return work.snapshot(); }
    public void suspendUnit(UUID unit) { service.unobserved(unit); }
    public void tick(long now) {
        boolean running=active.getAsBoolean();
        if(!running && wasActive) for(var unit:observed.keySet()) service.unobserved(unit);
        wasActive=running; work.tick(now);
    }
    private final class Audit {
        final WarTransmission instruction;
        final long startedAt;
        final List<WarCohesionFrame.Member> members=new ArrayList<>();
        final Map<UUID,CombatEncounter> encounters=new LinkedHashMap<>();
        final List<WarCohesionFrame.Events> streams=new ArrayList<>();
        final WarIntent.Position rally;
        final Optional<WarLeadershipRuntime.Assignment> commander;
        List<CombatEncounter> eventSources;
        String evidenceProblem;
        int memberIndex,eventIndex,atObjective;
        Audit(WarTransmission instruction,long now) {
            this.instruction=instruction; startedAt=now;
            commander=leadership.assignment(instruction);
            var old=service.view(instruction.command().unitId()).state();
            boolean newOrder=old.isEmpty() || instruction.sequence()>old.get().sequence();
            rally=newOrder && !combat(instruction)?instruction.command().intent().target():old.map(WarCohesion::rally).orElse(instruction.route().origin());
            old.ifPresent(state->state.cursors().keySet().stream().sorted().forEach(id->{
                var e=sources.encounters().encounter(id);
                if(e==null) evidenceProblem="REFERENCED_ENCOUNTER_MISSING_REQUIRES_REVIEW"; else encounters.put(id,e);
            }));
        }
        WarWorkQueue.Step step(WarWorkQueue.Quota quota) {
            long now=server.overworld().getGameTime(); UUID unit=instruction.command().unitId();
            if(evidenceProblem!=null) { service.block(unit,evidenceProblem); queued.remove(unit); return WarWorkQueue.Step.complete(); }
            if(!active.getAsBoolean() || now<startedAt || now-startedAt>MAXIMUM_AUDIT_TICKS || effective.apply(unit,now).filter(v->v.command().equals(instruction.command())).isEmpty()) {
                service.unobserved(unit); queued.remove(unit); return WarWorkQueue.Step.complete();
            }
            while(memberIndex<instruction.members().size() && quota.tryConsume()) {
                var member=instruction.members().get(memberIndex++);
                var current=militia.activePerson(member.character()).filter(v->v.request().id().equals(member.contract()) && v.request().unitId().equals(unit));
                if(current.isEmpty()) continue;
                var body=ItemLocation.personal(member.character(),member.body()).body(server,sources.characters());
                if(body.isEmpty()) { members.add(new WarCohesionFrame.Member(member.contract(),member.character(),member.body(),false,50,false,true)); continue; }
                var npc=body.get();
                morale.ensure(member.character(),50,npc.getMoral(),now); int value=morale.profile(member.character(),now).current();
                var health=sources.injuries().observeTriage(member.character()); var needs=sources.life().needs(member.character());
                boolean available=mobility.observe(npc,current.get().request()).usable() && !health.incapacitated() && !health.mortalDanger() && npc.getHealth()>npc.getMaxHealth()*.25F && needs!=null && needs.hunger()<900;
                var point=instruction.station(member,rally);
                var pose=mobility.pose(npc);
                boolean arrived=pose.distanceSquared(point)<=9;
                if(available&&commander.isPresent()&&pose.distanceSquared(instruction.station(member,instruction.command().intent().target()))<=9)atObjective++;
                var encounter=sources.encounters().activeFor(member.character(),now);
                if(encounter!=null) encounters.put(encounter.id(),encounter);
                members.add(new WarCohesionFrame.Member(member.contract(),member.character(),member.body(),available,value,arrived,pressured(npc,encounter,now)));
            }
            if(memberIndex<instruction.members().size()) return WarWorkQueue.Step.next();
            if(encounters.size()>WarCohesion.MAX_ENCOUNTERS) { service.block(unit,"TACTICAL_ENCOUNTER_CAPACITY_REQUIRES_REVIEW"); queued.remove(unit); return WarWorkQueue.Step.complete(); }
            if(eventSources==null) eventSources=List.copyOf(encounters.values());
            while(eventIndex<eventSources.size() && quota.tryConsume()) {
                var e=eventSources.get(eventIndex++); var latest=sources.encounters().encounter(e.id());
                if(latest==null) { service.block(unit,"REFERENCED_ENCOUNTER_MISSING_REQUIRES_REVIEW"); queued.remove(unit); return WarWorkQueue.Step.complete(); } e=latest;
                UUID encounterId=e.id(); var old=service.view(unit).state().map(s->s.cursors().get(encounterId)).orElse(null);
                if(old!=null && old.count()==e.events().size() && old.last().equals(e.events().getLast().id())) continue;
                var events=new ArrayList<WarCohesionFrame.Event>();
                for(var event:e.events()) events.add(new WarCohesionFrame.Event(event.id(),event.subjectCharacterId(),switch(event.type()) {
                    case WOUNDED -> WarCohesionFrame.Shock.WOUNDED; case KILLED -> WarCohesionFrame.Shock.KILLED; default -> WarCohesionFrame.Shock.NONE;
                },event.occurredAt()));
                streams.add(new WarCohesionFrame.Events(e.id(),events));
            }
            if(eventIndex<eventSources.size()) return WarWorkQueue.Step.next();
            if(members.isEmpty()) service.unobserved(unit);
            else {
                var kind=instruction.command().intent().kind();
                var currentLeader=commander.flatMap(started->leadership.assignment(instruction).filter(current->started.appointment().equals(current.appointment())));
                long received=instruction.received().orElseThrow().deliveredAtTick();
                var influence=currentLeader.map(a->switch(a.specialty()){
                    case ASSAULT,HOLD -> new WarCohesionFrame.Leadership(WarLeadershipRecord.formationLossDiscount(a.points()),0,received);
                    case WITHDRAW -> new WarCohesionFrame.Leadership(0,WarLeadershipRecord.rallyRecoveryBonus(a.points()),received);
                    case MANEUVER -> WarCohesionFrame.Leadership.NONE;
                }).orElse(WarCohesionFrame.Leadership.NONE);
                var frame=new WarCohesionFrame(unit,instruction.command().armyId(),instruction.command().operationId(),instruction.sequence(),
                        kind.equals(com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions.RETREAT)?WarCohesionFrame.OrderKind.WITHDRAW:
                        combat(instruction)?WarCohesionFrame.OrderKind.COMBAT:WarCohesionFrame.OrderKind.RALLY,
                        instruction.command().intent().target(),instruction.route().origin(),now,members,streams,influence);
                var view=service.advance(frame); observed.put(unit,now);
                if(view.status()==Status.APPLIED&&view.state().filter(s->s.order().equals(frame.order())).isPresent())
                    currentLeader.ifPresent(a->leadership.observe(instruction,a,now,atObjective,frame.safeAtRally(),encounters.values()));
            }
            queued.remove(unit); return WarWorkQueue.Step.complete();
        }
    }
    private boolean pressured(NpcBase npc,CombatEncounter encounter,long now) {
        if(npc.getLastDamageGameTime()>0 && now>=npc.getLastDamageGameTime() && now-npc.getLastDamageGameTime()<100) return true;
        if(encounter==null) return false; var side=encounter.sideOf(npc.getCharacterId()); if(side==null) return true;
        return !((ServerLevel)npc.level()).getEntitiesOfClass(NpcBase.class,npc.getBoundingBox().inflate(12),other->{
            var enemy=encounter.sideOf(other.getCharacterId());
            return other!=npc && other.isAlive() && enemy!=null && !enemy.id().equals(side.id()) && !npc.isCombatAlly(other) && npc.getSensing().hasLineOfSight(other);
        }).isEmpty();
    }
    private static boolean combat(WarTransmission value) {
        var key=value.command().intent().kind();
        return key.equals(com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions.MOVE) || key.equals(com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions.HOLD) || key.equals(com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions.CHARGE);
    }
    @Override public void close() {
        for(var unit:queued) work.cancel(unit); queued.clear(); work.close(); service.close();
    }
}
