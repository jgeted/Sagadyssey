package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.spi.WarCommandPostPorts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.io.IOException;
import java.util.*;

/** Player selection is transient; banners and orders have separate durable records. Never creates an army or consent. */
public final class WarCommandMediaRuntime implements AutoCloseable {
    public record Selection(UUID character,UUID unit,WarKey mode){}
    private final MinecraftServer server;private final WarServerRuntime core;private final WarCommandPostService posts;
    private final Map<UUID,Selection> selections=new HashMap<>();private final Map<UUID,Long> nextUse=new HashMap<>(),nextSignal=new HashMap<>();
    public WarCommandMediaRuntime(MinecraftServer server,WarServerRuntime core,WarCommandPostPorts.Journal journal)throws IOException{
        this.server=server;this.core=core;posts=new WarCommandPostService(journal);
        for(var p:posts.records())if(core.session().flatMap(s->s.unit(p.unit())).filter(u->u.armyId().equals(p.army())).isEmpty()){posts.close();throw new IOException("orphan command post requires review");}
    }
    public List<WarUnit> units(ServerPlayer player){
        check();var actor=CharacterRegistry.currentId(player);if(actor==null)return List.of();
        return core.session().map(s->s.units().stream().filter(u->core.permission(actor,u.armyId())==WarCommandService.Permission.ALLOWED).sorted(Comparator.comparing(WarUnit::id)).toList()).orElse(List.of());
    }
    public boolean select(ServerPlayer player,UUID unit){
        check();var actor=CharacterRegistry.currentId(player);var value=core.session().flatMap(s->s.unit(unit));
        if(actor==null || value.isEmpty() || core.permission(actor,value.get().armyId())!=WarCommandService.Permission.ALLOWED || selections.size()>=256 && !selections.containsKey(player.getUUID()))return false;
        selections.put(player.getUUID(),new Selection(actor,unit,InitialWarDefinitions.MOVE));return true;
    }
    public Optional<Selection> selection(ServerPlayer player){
        check();var value=selections.get(player.getUUID());
        if(value==null || !Objects.equals(value.character(),CharacterRegistry.currentId(player)) || core.session().flatMap(s->s.unit(value.unit())).filter(u->core.permission(value.character(),u.armyId())==WarCommandService.Permission.ALLOWED).isEmpty())return Optional.empty();
        return Optional.of(value);
    }
    public boolean mode(ServerPlayer player,WarKey mode){
        check();if(!Set.of(InitialWarDefinitions.MOVE,InitialWarDefinitions.HOLD,InitialWarDefinitions.RALLY,InitialWarDefinitions.RETREAT,InitialWarDefinitions.CHARGE).contains(mode))return false;
        var s=selection(player);if(s.isEmpty())return false;selections.put(player.getUUID(),new Selection(s.get().character(),s.get().unit(),mode));return true;
    }
    public void clear(ServerPlayer player){check();selections.remove(player.getUUID());nextUse.remove(player.getUUID());nextSignal.remove(player.getUUID());}
    public WarCommandPostPorts.Reply bindPost(ServerPlayer player,WarIntent.Position point,WarKey block){
        check();var s=selection(player);if(s.isEmpty() || !ready() || !rate(player,false))return new WarCommandPostPorts.Reply(WarCommandPostPorts.Status.UNAVAILABLE,Optional.empty(),"AUTHORIZED_SELECTION_REQUIRED_OR_RATE_LIMIT");
        var unit=core.session().orElseThrow().unit(s.get().unit()).orElseThrow();var old=posts.desired(unit.id());
        if(old.isPresent() && old.get().position().equals(point) && old.get().block().equals(block) && old.get().binder().equals(s.get().character()))return posts.view(unit.id());
        if(old.filter(p->p.revision()==Long.MAX_VALUE).isPresent())return new WarCommandPostPorts.Reply(WarCommandPostPorts.Status.UNAVAILABLE,Optional.empty(),"POST_REVISION_EXHAUSTED");
        return posts.bind(new WarCommandPost(unit.id(),unit.armyId(),s.get().character(),point,block,old.map(p->p.revision()+1).orElse(1L)));
    }
    public Optional<WarCommandPost> post(ServerPlayer player){check();return selection(player).flatMap(s->posts.view(s.unit()).durable());}
    /** Internal original-unit lookup; player-facing operations still enforce their live owner selection. */
    public Optional<WarCommandPost> post(UUID unit){check();return posts.view(unit).durable();}
    public boolean maySignal(ServerPlayer player){
        check();var s=selection(player);if(s.isEmpty() || !ready() || post(player).isEmpty())return false;
        var u=core.session().orElseThrow().unit(s.get().unit()).orElseThrow();return core.field().orElseThrow().localRoute(s.get().character(),u.armyId(),u.id()).isPresent();
    }
    /** Native adapter validates the real map click/banner or sounded horn before invoking this entry. */
    public WarDispatchService.Result issue(ServerPlayer player,WarIntent.Position point,boolean horn){
        check();var s=selection(player);if(s.isEmpty() || !ready() || !rate(player,horn) || horn && !maySignal(player))return rejected("AUTHORIZED_SELECTION_OR_LOCAL_SIGNAL_REQUIRED");
        var u=core.session().orElseThrow().unit(s.get().unit()).orElseThrow();
        if(horn && post(player).filter(p->p.position().equals(point)).isEmpty())return rejected("COMMITTED_COMMAND_POST_REQUIRED");
        var intent=new WarIntent(horn?InitialWarDefinitions.RALLY:s.get().mode(),1,point);
        var command=new WarCommand.IssueOrder(UUID.randomUUID(),core.session().orElseThrow().generation(),s.get().character(),u.armyId(),u.id(),u.revision(),intent);
        return horn?core.field().orElseThrow().dispatchLocalSignal(command):core.field().orElseThrow().dispatch(command);
    }
    public List<WarFieldReport> reports(ServerPlayer p){check();return selection(p).map(s->core.session().flatMap(v->v.unit(s.unit())).map(u->core.field().map(f->f.reports(s.character(),u.armyId()).stream().filter(r->r.unit().equals(u.id())).toList()).orElse(List.of())).orElse(List.<WarFieldReport>of())).orElse(List.of());}
    /** A map preview supplies a stable operation and expected revision; authority remains live. */
    public WarDispatchService.Result issuePrepared(ServerPlayer p,WarCommand.IssueOrder command){
        check();if(!Objects.equals(CharacterRegistry.currentId(p),command.actorId()) || !ready() || !rate(p,false)
                || !Set.of(InitialWarDefinitions.MOVE,InitialWarDefinitions.HOLD,InitialWarDefinitions.RETREAT,InitialWarDefinitions.RALLY,InitialWarDefinitions.CHARGE).contains(command.intent().kind()))return rejected("CURRENT_MAP_COMMAND_REQUIRED");
        return core.field().orElseThrow().dispatch(command);
    }
    public void tick(){check();posts.tick();}
    private boolean ready(){return core.session().filter(s->s.phase()==com.jgeted.sagadyssey.social.war.runtime.WarSession.Phase.ACTIVE).isPresent() && core.field().isPresent();}
    private boolean rate(ServerPlayer player,boolean signal){var rates=signal?nextSignal:nextUse;long now=server.overworld().getGameTime();if(now>Long.MAX_VALUE-5 || now<rates.getOrDefault(player.getUUID(),0L))return false;rates.put(player.getUUID(),now+5);return true;}
    private static WarDispatchService.Result rejected(String reason){return new WarDispatchService.Result(WarDispatchService.Outcome.UNAVAILABLE,Optional.empty(),reason);}
    private void check(){if(!server.isSameThread())throw new IllegalStateException("command media requires server thread");}
    public void close(){check();posts.close();selections.clear();nextUse.clear();nextSignal.clear();}
}
