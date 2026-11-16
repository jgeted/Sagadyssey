package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.runtime.WarWorkQueue;
import com.jgeted.sagadyssey.social.war.spi.WarCavalryPorts.*;
import com.jgeted.sagadyssey.social.war.spi.WarMobilityPorts;
import com.jgeted.sagadyssey.worldsim.transfer.ItemLocation;
import net.minecraft.server.MinecraftServer;
import java.io.IOException;
import java.util.*;
import java.util.function.*;

/** Bounded unit observations, stable geometry and actual motion evidence. Field execution retains native actions. */
public final class WarCavalryRuntime implements AutoCloseable{
    public record Control(Status status,Optional<WarCavalry> state,Optional<WarIntent.Position> destination,boolean run,String reason){}
    private record Layout(WarTransmission instruction,WarIntent.Position assembly,List<WarIntent.Position> rear,List<WarIntent.Position> front,List<WarIntent.Position> exit,Map<UUID,Integer> slots){}
    private static final class Motion{
        UUID order;long wave,last,movingAt;WarMobilityPorts.Pose start,previous;double speed,alignment;
    }
    private final MinecraftServer server;private final WarServerRuntime core;private final WarMobilizationRuntime militia;
    private final WarFieldRuntime.Sources sources;private final WarNativeBindings nativeBindings;private final WarCohesionRuntime cohesion;
    private final BiFunction<UUID,Long,Optional<WarTransmission>> effective;private final BooleanSupplier active;private final WarCavalryService service;
    private final WarWorkQueue work=new WarWorkQueue(new WarWorkQueue.Limits(1024,2,4,2_000_000),System::nanoTime);
    private final Map<UUID,Layout> layouts=new HashMap<>();private final Map<UUID,Long> observed=new HashMap<>(),next=new HashMap<>();
    private final Map<UUID,Motion> movement=new HashMap<>();private final Set<UUID> queued=new HashSet<>();private final LinkedHashSet<UUID> waiting=new LinkedHashSet<>();
    private boolean wasActive;
    public WarCavalryRuntime(MinecraftServer server,WarServerRuntime core,WarMobilizationRuntime militia,WarFieldRuntime.Sources sources,WarNativeBindings bindings,
                             WarCohesionRuntime cohesion,BiFunction<UUID,Long,Optional<WarTransmission>> effective,BooleanSupplier active,JournalFactory journals,Policy policy,Collection<WarTransmission> history)throws IOException{
        this.server=server;this.core=core;this.militia=militia;this.sources=sources;this.nativeBindings=bindings;this.cohesion=cohesion;this.effective=effective;this.active=active;
        var journal=journals.open();try{service=new WarCavalryService(journal,policy);WarCavalryRecovery.verify(service.records(),history,core.session().orElseThrow().checkpoint().units(),com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions.CHARGE);}catch(RuntimeException e){journal.close();throw e;}
    }
    public void touch(WarTransmission instruction,long now){
        UUID unit=instruction.command().unitId();if(queued.contains(unit)||now<next.getOrDefault(unit,0L)||service.view(unit).status()==Status.UNKNOWN)return;
        if(observed.containsKey(unit)&&now-observed.get(unit)>40)service.unobserved(unit);
        try{var audit=new Audit(layout(instruction),now);var result=work.submit(unit,audit::step,now);if(result==WarWorkQueue.Submission.ACCEPTED){queued.add(unit);next.put(unit,now+10);}else if(result!=WarWorkQueue.Submission.DUPLICATE)service.block(unit,"CAVALRY_OBSERVATION_CAPACITY");}
        catch(RuntimeException e){service.block(unit,"CAVALRY_LAYOUT_REQUIRES_REVIEW:"+e.getClass().getSimpleName());}
    }
    public Control control(WarTransmission instruction,WarTransmission.Member member,long now){
        var unit=instruction.command().unitId();var view=service.view(unit);
        if(view.status()!=Status.APPLIED)return new Control(view.status(),view.state(),Optional.empty(),false,view.reason());
        var state=view.state().orElseThrow();var layout=layouts.get(unit);
        if(layout==null||!state.order().equals(instruction.command().operationId())||!state.members().contains(member)||now<observed.getOrDefault(unit,Long.MAX_VALUE)||now-observed.getOrDefault(unit,0L)>40)return new Control(Status.UNAVAILABLE,view.state(),Optional.empty(),false,"FRESH_CAVALRY_OBSERVATION_REQUIRED");
        int slot=layout.slots().get(member.contract());boolean run=state.phase()==WarCavalry.Phase.RUN&&!state.spent().contains(member.contract());
        var destination=state.phase()==WarCavalry.Phase.REFORM||state.returning().contains(member.contract())?layout.rear().get(slot):run?layout.front().get(slot):layout.exit().get(slot);
        return new Control(Status.APPLIED,view.state(),Optional.of(destination),run,run?"CAVALRY_APPROACH":state.phase()==WarCavalry.Phase.REFORM?"CAVALRY_REFORM":"CAVALRY_DISENGAGE");
    }
    public Optional<Contact> take(WarTransmission instruction,WarTransmission.Member member,long now){var state=service.view(instruction.command().unitId()).state();return state.flatMap(s->service.take(s.unit(),instruction.command().operationId(),s.wave(),member,now));}
    public WarCavalryService.View finish(WarTransmission instruction,WarTransmission.Member member,Optional<Contact> contact,long now){
        var unit=instruction.command().unitId();var state=service.view(unit).state();if(state.isEmpty())return service.view(unit);
        var result=service.finish(unit,instruction.command().operationId(),state.get().wave(),member,contact,now);if(result.status()==Status.PENDING)waiting.add(unit);return result;
    }
    public void abort(WarTransmission instruction,WarTransmission.Member member,long now){var unit=instruction.command().unitId();service.view(unit).state().ifPresent(s->{if(service.abort(unit,instruction.command().operationId(),s.wave(),member,now).status()==Status.PENDING)waiting.add(unit);});}
    public Optional<Contact> contact(NpcBase npc,WarTransmission instruction,WarTransmission.Member member,NpcBase target,long now){
        var state=service.view(instruction.command().unitId()).state();if(state.isEmpty())return Optional.empty();var s=state.get();var pose=nativeBindings.mobility().pose(npc);var m=movement.computeIfAbsent(member.body(),id->new Motion());
        sample(m,pose,instruction,s,now);
        int hx=WarCavalryFormation.headingX(s.assembly(),s.objective()),hz=WarCavalryFormation.headingZ(s.assembly(),s.objective());
        return Optional.of(new Contact(target.getUUID(),target.getCharacterId(),now,m.movingAt,Math.max(0,(pose.x()-m.start.x())*hx+(pose.z()-m.start.z())*hz),m.speed,m.alignment));
    }
    public void track(NpcBase npc,WarTransmission instruction,WarTransmission.Member member,long now){
        var state=service.view(instruction.command().unitId()).state();if(state.filter(s->s.phase()==WarCavalry.Phase.RUN).isPresent())sample(movement.computeIfAbsent(member.body(),id->new Motion()),nativeBindings.mobility().pose(npc),instruction,state.get(),now);
    }
    public void dismounted(UUID body){movement.remove(body);}
    private void sample(Motion m,WarMobilityPorts.Pose pose,WarTransmission instruction,WarCavalry s,long now){
        long delta=now-m.last;double step=m.previous==null?0:Math.hypot(pose.x()-m.previous.x(),pose.z()-m.previous.z());
        if(!Objects.equals(m.order,instruction.command().operationId())||m.wave!=s.wave()||m.previous==null||delta<0||delta>2||step>1.5*Math.max(1,delta)){
            m.order=instruction.command().operationId();m.wave=s.wave();m.start=pose;m.speed=0;m.alignment=0;m.movingAt=now;
        }else if(delta>0&&step/delta>=.01){
            int hx=WarCavalryFormation.headingX(s.assembly(),s.objective()),hz=WarCavalryFormation.headingZ(s.assembly(),s.objective());
            m.speed=step/delta;m.alignment=Math.clamp(((pose.x()-m.previous.x())*hx+(pose.z()-m.previous.z())*hz)/step,-1,1);m.movingAt=now;
        }
        m.previous=pose;m.last=now;
    }
    public Optional<WarCavalry> state(UUID unit){return service.view(unit).state();}
    public WarIntent.Position front(WarTransmission instruction,WarTransmission.Member member){var l=layout(instruction);return l.front().get(l.slots().get(member.contract()));}
    public WarWorkQueue.Snapshot budget(){return work.snapshot();}
    public void suspend(UUID unit){service.unobserved(unit);}
    public void tick(long now){
        boolean running=active.getAsBoolean();if(!running&&wasActive){for(var unit:layouts.keySet())service.unobserved(unit);movement.clear();}wasActive=running;
        for(int n=Math.min(4,waiting.size());n>0;n--){var it=waiting.iterator();var unit=it.next();it.remove();if(service.poll(unit).status()==Status.PENDING)waiting.add(unit);}work.tick(now);
    }
    private Layout layout(WarTransmission instruction){
        var key=instruction.command().unitId();var old=layouts.get(key);if(old!=null&&old.instruction().command().equals(instruction.command()))return old;
        if(old==null&&layouts.size()>=1024)throw new IllegalStateException("cavalry unit capacity reached");
        if(old!=null)for(var member:old.instruction().members())movement.remove(member.body());
        var saved=service.view(key).state().filter(v->v.order().equals(instruction.command().operationId()));var assembly=saved.map(WarCavalry::assembly).orElseGet(()->cohesion.state(key).map(WarCohesion::rally).orElse(instruction.route().origin()));
        var rear=new ArrayList<WarIntent.Position>();var front=new ArrayList<WarIntent.Position>();var exits=new ArrayList<WarIntent.Position>();var slots=new HashMap<UUID,Integer>();int size=instruction.members().size();
        for(int i=0;i<size;i++){slots.put(instruction.members().get(i).contract(),i);rear.add(WarCavalryFormation.assembly(assembly,instruction.command().intent().target(),i,size));front.add(WarCavalryFormation.objective(assembly,instruction.command().intent().target(),i,size));exits.add(WarCavalryFormation.exit(assembly,instruction.command().intent().target(),i,size));}
        var result=new Layout(instruction,assembly,List.copyOf(rear),List.copyOf(front),List.copyOf(exits),Map.copyOf(slots));layouts.put(key,result);return result;
    }
    private final class Audit{
        final Layout layout;final long start;final List<WarCavalryFrame.Member> members=new ArrayList<>();int index;
        Audit(Layout layout,long now){this.layout=layout;start=now;}
        WarWorkQueue.Step step(WarWorkQueue.Quota quota){
            var instruction=layout.instruction();var unit=instruction.command().unitId();long now=server.overworld().getGameTime();
            if(!active.getAsBoolean()||now<start||now-start>40||effective.apply(unit,now).filter(t->t.command().equals(instruction.command())).isEmpty()){service.unobserved(unit);queued.remove(unit);return WarWorkQueue.Step.complete();}
            while(index<instruction.members().size()&&quota.tryConsume()){
                int slot=index;var m=instruction.members().get(index);var record=militia.activePerson(m.character()).filter(r->r.request().id().equals(m.contract())&&r.request().unitId().equals(unit));var body=ItemLocation.personal(m.character(),m.body()).body(server,sources.characters());
                if(record.isEmpty()||body.isEmpty()){members.add(new WarCavalryFrame.Member(m.contract(),m.character(),m.body(),false,false,false));index++;continue;}
                var tactical=cohesion.control(instruction,m,now);if(tactical.status()!=com.jgeted.sagadyssey.social.war.spi.WarCohesionPorts.Status.APPLIED)return WarWorkQueue.Step.next();
                var npc=body.get();var h=sources.injuries().observeTriage(m.character());var needs=sources.life().needs(m.character());
                boolean eligible=!tactical.withdraw()&&nativeBindings.mobility().observe(npc,record.get().request()).mode()==WarMobilityPorts.Mode.RIDING&&!h.incapacitated()&&!h.mortalDanger()&&npc.getHealth()>npc.getMaxHealth()*.25F&&needs!=null&&needs.hunger()<900;
                members.add(new WarCavalryFrame.Member(m.contract(),m.character(),m.body(),eligible,nativeBindings.mobility().arrived(npc,layout.rear().get(slot)),nativeBindings.mobility().arrived(npc,layout.exit().get(slot))));index++;
            }
            if(index<instruction.members().size())return WarWorkQueue.Step.next();
            var frame=new WarCavalryFrame(unit,instruction.command().armyId(),instruction.command().operationId(),instruction.sequence(),now,layout.assembly(),instruction.command().intent().target(),cohesion.state(unit).map(WarCohesion::organization).orElse(0),members);
            if(service.advance(frame).status()==Status.PENDING)waiting.add(unit);observed.put(unit,now);queued.remove(unit);return WarWorkQueue.Step.complete();
        }
    }
    public void close(){work.close();service.close();layouts.clear();movement.clear();waiting.clear();}
}
