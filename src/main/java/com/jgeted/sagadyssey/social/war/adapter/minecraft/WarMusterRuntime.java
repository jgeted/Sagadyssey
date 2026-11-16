package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.execution.PlanCancellationReason;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.life.NpcAgencyDecision;
import com.jgeted.sagadyssey.social.service.*;
import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.spi.WarMusterPorts.*;
import com.jgeted.sagadyssey.social.work.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ItemLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Ordinary-world small-squad assembly. Explicit activation, exact agreement references and original shared authorities. */
@EventBusSubscriber(modid="sagadyssey")
public final class WarMusterRuntime implements AutoCloseable {
    public record Proposal(UUID ticket,WarMilitiaRequest request,UUID body,UUID parent,long parentRevision,String dimension,UUID workplace,long endsAt,long expiresAt){}
    public record Result(boolean accepted,String message,Optional<UUID> identity){
        static Result ok(String text,UUID id){return new Result(true,text,Optional.of(id));}
        static Result no(String text){return new Result(false,text,Optional.empty());}
    }
    private static final Map<MinecraftServer,WarMusterRuntime> STATES=new WeakHashMap<>();
    /** A readable terms window, not a reservation or grant; confirm still revalidates every source. */
    private static final long PROPOSAL_LIFETIME_TICKS=2_400;
    private static final Map<MinecraftServer,String> FAILURES=new WeakHashMap<>();
    private final MinecraftServer server;
    private final WarMusterBook book;
    private final WarCandidateInspector.Sources sources;
    private final WarNativeBindings.ServiceCheckpoint checkpoint;
    private final LinkedHashSet<UUID> ready=new LinkedHashSet<>();
    private final LinkedHashSet<UUID> activeChecks=new LinkedHashSet<>();
    private final Set<UUID> visibleActive=new HashSet<>();
    private final Set<UUID> recoveredGranting=new HashSet<>();
    private final Map<UUID,Proposal> proposals=new LinkedHashMap<>();
    private final Map<UUID,String> waiting=new HashMap<>();
    private WarServerRuntime core;
    private WarNativeMobilization nativePorts;
    private String problem="";
    private boolean closed;
    private boolean activationRequested;

    private WarMusterRuntime(MinecraftServer server)throws IOException{
        this.server=server;WarMusterJournal journal=new WarMusterJournal(path(server));
        try{book=new WarMusterBook(journal);}catch(RuntimeException invalid){journal.close();throw invalid;}
        boolean recorded=book.entries().stream().anyMatch(e->e instanceof Unit u&&u.recorded());
        if(recorded&&WarServerEvents.existing(server).isEmpty()&&WarSavedData.readDisk(server.getWorldPath(LevelResource.ROOT).resolve("data"),new WarStateCodec(WarStateCodec.Limits.defaults())).isEmpty()){
            book.close();throw new IOException("recorded muster history without core; review required");
        }
        sources=WarMusterSources.open(server);checkpoint=WarNativeBindings.currentBuild().serviceCheckpoints().apply(server);
        for(var e:book.entries()){
            schedule(e);
            if(e instanceof Offer o&&o.stage()==Stage.GRANTING)recoveredGranting.add(o.id());
        }
    }
    public static Optional<WarMusterRuntime> existing(MinecraftServer server){return Optional.ofNullable(STATES.get(server));}
    public static WarMusterRuntime open(MinecraftServer server)throws IOException{
        if(!server.isSameThread())throw new IllegalStateException("muster requires server thread");
        if(FAILURES.containsKey(server))throw new IOException(FAILURES.get(server));
        var old=STATES.get(server);if(old!=null)return old;
        try{var value=new WarMusterRuntime(server);STATES.put(server,value);return value;}
        catch(IOException|RuntimeException failure){FAILURES.put(server,"MUSTER_REQUIRES_REVIEW:"+failure.getClass().getSimpleName());throw failure;}
    }
    public static String failure(MinecraftServer server){return FAILURES.getOrDefault(server,"");}
    public WarMusterBook book(){return book;}
    Optional<WarState> savedCore() { check(); return checkpoint.core(); }
    void requestCoreCheckpoint() { check(); checkpoint.request(); }
    public String problem(){return problem;}
    public String status(UUID actor){
        if(!problem.isEmpty())return problem;
        var rows=new ArrayList<String>();for(var e:book.entries()){
            if(e instanceof Army a&&a.owner().equals(actor))rows.add("军队 "+a.name()+" · "+a.id()+(book.durable(a)?"":" · 保存中"));
            if(e instanceof Unit u&&book.owns(actor,u.army()))rows.add("兵团 "+u.name()+" · "+u.profile()+" · "+(u.recorded()?"已登记":"登记中")+suffix(u.id()));
            if(e instanceof Offer o&&o.request().actorId().equals(actor))rows.add("军役 "+o.id()+" · "+o.stage()+" · "+o.reason()+suffix(o.id()));
        }return rows.isEmpty()?"尚未建立军队。":"小队由本人指挥；自备装具，领主背包付饷。\n"+String.join("\n",rows);
    }
    private String suffix(UUID id){var text=waiting.get(id);return text==null?"":" · "+text;}
    public UUID actor(ServerPlayer player){
        if(player.level().getServer()!=server||!player.isAlive())throw new IllegalArgumentException("原玩家身体不可用");
        var actor=sources.characters().current(player.getUUID());if(actor==null)throw new IllegalArgumentException("当前人物身份不可用");return actor.id();
    }
    public Result createArmy(ServerPlayer player,String name){
        check();var actor=actor(player);activationRequested=true;var id=WarMusterEntry.armyId(actor,name);var old=book.army(id);
        if(old.isPresent())return Result.ok("军队已登记："+name,id);
        var r=book.put(new Army(id,actor,name,now()),now());return result(r,"军队归属正在保存",id);
    }
    public Result createUnit(ServerPlayer player,String armyName,String name,WarKey profile){
        check();var actor=actor(player);activationRequested=true;var army=WarMusterEntry.armyId(actor,armyName);
        if(!book.owns(actor,army))return Result.no("请先创建并保存自己的军队");
        var loadout=InitialWarDefinitions.LOADOUTS.find(profile);if(loadout.isEmpty())return Result.no("未知配装");
        var id=WarMusterEntry.unitId(army,name);var old=book.unit(id);
        if(old.isPresent())return old.get().profile().equals(profile)?Result.ok("兵团已登记或正在登记",id):Result.no("同名兵团已使用另一配装");
        var r=book.put(new Unit(id,army,name,profile,1,false,1),now());if(r.status()==Status.APPLIED||r.status()==Status.PENDING)ready.add(id);
        return result(r,"兵团登记正在保存",id);
    }
    /** Read-only terms preview. No random draw, authority, labor claim or material side effect. */
    public Proposal propose(ServerPlayer player,NpcBase npc,String armyName,String unitName,long wage,int days){
        return propose(player,npc,armyName,unitName,wage,days,WarMilitiaRequest.Role.COMBATANT);
    }
    public Proposal propose(ServerPlayer player,NpcBase npc,String armyName,String unitName,long wage,int days,WarMilitiaRequest.Role role){
        check();var actor=actor(player);var army=WarMusterEntry.armyId(actor,armyName);var unit=book.unit(WarMusterEntry.unitId(army,unitName)).orElseThrow(()->new IllegalArgumentException("兵团尚未登记"));
        if(!book.owns(actor,army)||!unit.recorded()||!book.durable(unit)||nativePorts==null)throw new IllegalArgumentException("兵团或共享接点仍在保存，请稍候");
        if(wage<1||wage>64||days<1||days>30)throw new IllegalArgumentException("军饷每日1至64绿宝石，军役1至30日");
        requireNear(player,npc);var person=npc.getCharacterId();var previous=book.latest(person);
        if(WorkCommitmentRuntime.blocksCivil(npc))throw new IllegalArgumentException("原人物已有主劳动或其他整备占用");
        if(previous.filter(o->o.reserves(now())).isPresent())throw new IllegalArgumentException("此人已有军役提议、未结责任或短暂的再议等待期");
        var parent=sources.services().activeForAgent(person,now()).stream().filter(r->r.principalId().equals(actor)&&r.basis()!=ServiceBasis.TEMPORARY_DELEGATION
                &&!r.limits().dangerousOrdersAllowed()&&r.scopes().containsAll(WarCandidateInspector.REQUIRED_SCOPES)).findFirst().orElseThrow(()->new IllegalArgumentException("需要本人已有的普通服务关系；军役须另行征得同意"));
        var life=sources.life().record(person);if(life==null)throw new IllegalArgumentException("缺少原生活记录");
        requireCivilAuthority(npc,actor);
        var dimension=npc.level().dimension().identifier().toString();
        if(!ServiceRelationshipData.permits(parent,actor,person,WarCandidateInspector.REQUIRED_SCOPES,false,dimension,life.activeWorkplaceSettlementId(),now()))throw new IllegalArgumentException("普通服务的地域权限不符");
        requireCandidate(npc,unit.profile(),army);
        proposals.entrySet().removeIf(e->e.getValue().expiresAt()<=now());if(proposals.size()>=256)throw new IllegalArgumentException("提议数量已达上限");
        var ticket=UUID.randomUUID();var request=new WarMilitiaRequest(ticket,actor,army,unit.id(),person,unit.profile(),unit.definitionVersion(),WarMilitiaRequest.Kind.MILITIA,role,
                new WarMilitiaRequest.Wage(new WarKey("minecraft","emerald"),wage,24000),now());
        if(!com.jgeted.sagadyssey.social.war.domain.WarServiceRoles.supported(request))throw new IllegalArgumentException("目前支持战斗军役、步行军务勤务和单独同意的工程军役");
        long endsAt=Math.addExact(now(),days*24000L);if(parent.endsAt()!=null)endsAt=Math.min(endsAt,parent.endsAt());
        if(endsAt<=now())throw new IllegalArgumentException("普通服务已到期");
        var p=new Proposal(ticket,request,npc.getUUID(),parent.id(),parent.revision(),dimension,parent.limits().workplaceSettlementId(),endsAt,now()+PROPOSAL_LIFETIME_TICKS);proposals.put(ticket,p);return p;
    }
    public Result confirm(ServerPlayer player,UUID ticket){
        check();var actor=actor(player);var old=book.offer(ticket);
        if(old.isPresent())return old.get().request().actorId().equals(actor)?Result.ok("提议已记录，不会重复抽签或动员："+old.get().stage(),ticket):Result.no("提议不属于当前人物");
        var p=proposals.get(ticket);if(p==null||p.expiresAt()<=now()||!p.request().actorId().equals(actor))return Result.no("提议已失效，请重新查看条款");
        var npc=ItemLocation.personal(p.request().characterId(),p.body()).body(server,sources.characters()).orElseThrow(()->new IllegalArgumentException("原NPC身体不可用"));requireNear(player,npc);requireCandidate(npc,p.request().loadout(),p.request().armyId());
        requireCivilAuthority(npc,actor);if(WorkCommitmentRuntime.blocksCivil(npc))return Result.no("原人物已被其他主劳动或整备占用");
        var parent=sources.services().relationship(p.parent());if(parent==null||parent.revision()!=p.parentRevision()||!parent.activeAt(now())||!book.owns(actor,p.request().armyId()))return Result.no("原服务或军队归属已改变，请重新议定");
        var previous=book.latest(p.request().characterId());if(previous.filter(o->o.reserves(now())).isPresent())return Result.no("此人已有提议或尚未结清的责任");
        var admission=book.offerAdmission(p.request(),now());
        if(!admission.isEmpty())return Result.no(admission);
        double draw=npc.getRandom().nextDouble();var profile=InitialWarDefinitions.LOADOUTS.find(p.request().loadout()).orElseThrow();
        var decision=NpcAgencyDecision.evaluateServiceOffer(npc,actor,80+10*Math.log1p(p.request().wage().amountPerPeriod()),profile.mount()==WarLoadout.Mount.FOOT?35:45,draw);
        var offer=new Offer(p.request(),p.body(),p.parent(),p.parentRevision(),p.dimension(),p.workplace(),p.endsAt(),decision.probability(),draw,Stage.PROPOSED,1,now(),"等待军役决定落盘");
        var r=book.put(offer,now());if(r.status()==Status.APPLIED||r.status()==Status.PENDING){ready.add(ticket);proposals.remove(ticket);}
        return result(r,"军役决定正在保存；不会因重复确认而改变",ticket);
    }
    public Result retire(ServerPlayer player,UUID offer){
        check();var actor=actor(player);var old=book.offer(offer);if(old.isEmpty()||!old.get().request().actorId().equals(actor))return Result.no("没有本人可管理的军役");
        if(old.get().terminal())return Result.ok("当前状态："+old.get().stage(),offer);
        if(WarEngineeringRuntime.existing(server).filter(r->r.retire(player,offer)).isPresent())return Result.ok("已停止新施工；待原提交责任确认后退役，保留已建方块",offer);
        var campaign=WarCampaignRuntime.existing(server);if(campaign.filter(c->c.holds(offer)).isPresent()){
            var task=campaign.get().entries().stream().filter(WarCampaignEntry.Shipment.class::isInstance).map(WarCampaignEntry.Shipment.class::cast).filter(s->s.run().service().equals(offer)&&!s.run().terminal()).findFirst();
            if(task.isPresent())return Result.ok(campaign.get().cancel(player,task.get().id(),true),offer);
        }
        if(old.get().stage()!=Stage.RELEASING)put(old.get().advance(Stage.RELEASING,now(),"领主请求退役"));
        activeChecks.remove(offer);ready.add(offer);return Result.ok("退役已受理；结清借用装备和劳动责任后释放",offer);
    }
    /** Fresh exact-id gate used by claim, activation, queued actions and in-flight projectiles. */
    public boolean permits(WarMilitiaRequest request,long at){
        if(closed||!problem.isEmpty()||!book.owns(request.actorId(),request.armyId())||sources.characters().accountForCharacter(request.actorId())==null)return false;var found=book.offer(request.id());if(found.isEmpty())return false;var o=found.get();
        if(!o.request().equals(request)||!book.durable(o)||!Set.of(Stage.PREPARING,Stage.ACTIVE).contains(o.stage())||at>=o.endsAt()||!parentCurrent(o,at))return false;
        var binding=sources.characters().bodyOf(request.characterId());var life=sources.life().record(request.characterId());
        var person=sources.characters().byId(request.characterId());var child=sources.services().relationship(o.id());
        return person!=null&&person.alive()&&binding!=null&&binding.entityId().equals(o.body())&&binding.dimensionId().equals(o.dimension())&&life!=null&&childMatches(o,child)
                &&ServiceRelationshipData.permits(child,request.actorId(),request.characterId(),WarCandidateInspector.REQUIRED_SCOPES,true,o.dimension(),life.activeWorkplaceSettlementId(),at);
    }
    private static boolean childMatches(Offer o,ServiceRelationship child){return child!=null&&child.basis()==ServiceBasis.TEMPORARY_DELEGATION&&child.scopes().equals(WarCandidateInspector.REQUIRED_SCOPES)
            &&child.limits().equals(new ServiceLimits(o.workplace(),o.dimension(),true,false))&&Objects.equals(child.endsAt(),o.endsAt());}
    private boolean parentCurrent(Offer o,long at){var p=sources.services().relationship(o.parent());return p!=null&&p.revision()==o.parentRevision()
            &&ServiceRelationshipData.permits(p,o.request().actorId(),o.request().characterId(),WarCandidateInspector.REQUIRED_SCOPES,false,o.dimension(),o.workplace(),at);}
    private void tick(){
        if(closed||!problem.isEmpty())return;book.poll(4);if(!book.problem().isEmpty()){problem=book.problem();return;}checkpoint.tick(now());if(!checkpoint.problem().isEmpty()){problem=checkpoint.problem();return;}
        if(book.isEmpty())return;
        try{
            if(!assemble())return;
            long start=System.nanoTime();
            int admissions=Math.min(4,ready.size()),checks=Math.min(4,activeChecks.size());
            for(int i=0;i<Math.max(admissions,checks)&&(i==0||System.nanoTime()-start<2_000_000);i++){
                if(i<admissions&&!ready.isEmpty())advanceOne(ready);
                if(i<checks&&!activeChecks.isEmpty())advanceOne(activeChecks);
            }
        }catch(IOException|RuntimeException failure){problem="MUSTER_REQUIRES_REVIEW:"+failure.getClass().getSimpleName();org.slf4j.LoggerFactory.getLogger(WarMusterRuntime.class).error("军役装配暂停，保留军档和原责任",failure);}
    }
    private void schedule(WarMusterEntry entry){
        if(entry instanceof Offer o&&o.stage()==Stage.ACTIVE)activeChecks.add(o.id());
        else if(entry instanceof Unit u&&!u.recorded()||entry instanceof Offer o&&!o.terminal())ready.add(entry.id());
    }
    private void advanceOne(LinkedHashSet<UUID> queue){
        var it=queue.iterator();var id=it.next();it.remove();var entry=book.find(id).orElseThrow();
        if(!book.durable(entry)){schedule(entry);return;}
        if(entry instanceof Offer o&&o.stage()==Stage.ACTIVE&&visibleActive.add(o.id()))WarDutyChangedEvent.publish(server,o.request().characterId());
        if(entry instanceof Unit u&&!unitStep(u)||entry instanceof Offer o&&!offerStep(o))schedule(book.find(id).orElseThrow());
    }
    private boolean assemble()throws IOException{
        if(core==null){
            core=WarServerEvents.existing(server).orElseGet(()->WarServerEvents.activate(server));
            if(!core.data().writable()||core.session().isEmpty())throw new IOException("core requires review");
            if(core.nativeMobilization().isPresent())throw new IOException("another trusted host already owns military bindings");
            for(var e:book.entries())if(e instanceof Unit u&&u.recorded()&&core.session().orElseThrow().unit(u.id()).filter(x->matches(u,x)).isEmpty())throw new IOException("recorded unit missing from core");
            core.bindAuthority((actor,army)->{
                var person=sources.characters().byId(actor);return !closed&&problem.isEmpty()&&person!=null&&person.alive()&&sources.characters().accountForCharacter(actor)!=null&&book.owns(actor,army)
                        ?WarCommandService.Permission.ALLOWED:WarCommandService.Permission.DENIED;
            });checkpoint.request();return false;
        }
        if(activationRequested){if(core.session().orElseThrow().units().isEmpty())core.activate();activationRequested=false;}
        if(checkpoint.core().isEmpty()){checkpoint.request();return false;}
        if(nativePorts==null){
            nativePorts=core.bindNativeMobilization(sources);nativePorts.bindConsent(this::permits);
            var field=core.bindFieldExecution(new WarFieldRuntime.Sources(sources.characters(),sources.injuries(),com.jgeted.sagadyssey.social.combat.CombatEncounterData.get(server),sources.life()));
            if(!field.blockedReason().isEmpty())throw new IOException(field.blockedReason());
            field.bindOrdinaryBattleHost();
            core.openCommandMedia();
            for(var record:core.mobilization().orElseThrow().service().orElseThrow().records())if(book.offer(record.request().id()).filter(o->o.request().equals(record.request())).isEmpty())throw new IOException("orphan mobilization outside muster book");
        }return true;
    }
    private boolean unitStep(Unit u){
        var live=core.session().orElseThrow().unit(u.id());
        if(live.isEmpty()){
            if(u.recorded())throw new IllegalStateException("recorded unit missing");var army=book.army(u.army()).orElseThrow();
            var r=core.submit(new WarCommand.CreateUnit(WarMusterEntry.named("create/"+u.id()),core.session().orElseThrow().generation(),army.owner(),u.army(),u.id(),u.profile(),u.definitionVersion()));
            if(r.status()!=WarResult.Status.APPLIED&&r.status()!=WarResult.Status.REPLAYED){waiting.put(u.id(),"兵团创建："+r.status());return false;}
            checkpoint.request();return false;
        }
        if(!matches(u,live.get()))throw new IllegalStateException("muster unit identity conflict");
        var saved=checkpoint.core().map(s->s.units().get(u.id()));if(saved.isEmpty()||!matches(u,saved.get())){checkpoint.request();return false;}
        if(!u.recorded())put(u.acknowledged());waiting.remove(u.id());return true;
    }
    private boolean offerStep(Offer o){
        if(o.terminal())return true;var request=o.request();
        var npcPerson=sources.characters().byId(request.characterId());var bodyRef=sources.characters().bodyOf(request.characterId());
        if(o.stage()!=Stage.RELEASING&&(now()>=o.endsAt()||!parentCurrent(o,now())||npcPerson==null||!npcPerson.alive()||bodyRef==null||!bodyRef.entityId().equals(o.body())||sources.characters().byId(request.actorId())==null||!sources.characters().byId(request.actorId()).alive()||sources.characters().accountForCharacter(request.actorId())==null)){
            put(o.advance(Stage.RELEASING,now(),"原同意、期限或领主人物已失效"));return false;
        }
        switch(o.stage()){
            case PROPOSED->{put(o.advance(o.accepted()?Stage.GRANTING:Stage.DECLINED,now(),o.accepted()?"愿意按约承担危险职责":"NPC拒绝本次军役条款"));return !o.accepted();}
            case GRANTING->{
                var child=sources.services().relationship(o.id());
                if(child==null&&recoveredGranting.contains(o.id())){put(o.advance(Stage.REVIEW,now(),"重启后缺少已围栏授权证据，须核对"));return true;}
                if(child==null){child=sources.services().acceptRiskDelegation(o.id(),request.actorId(),request.characterId(),o.parent(),o.parentRevision(),WarCandidateInspector.REQUIRED_SCOPES,
                        new ServiceLimits(o.workplace(),o.dimension(),true,false),now(),o.endsAt(),new NpcAgencyDecision.ServiceOfferDecision(true,o.probability(),"已落盘的原军役决定"));checkpoint.request();}
                if(!childMatches(o,child)||!ServiceRelationshipData.permits(child,request.actorId(),request.characterId(),WarCandidateInspector.REQUIRED_SCOPES,true,o.dimension(),o.workplace(),now())){put(o.advance(Stage.RELEASING,now(),"本次军役同意已撤销或条款改变"));return false;}
                if(!checkpoint.contains(child)){checkpoint.request();return false;}recoveredGranting.remove(o.id());put(o.advance(Stage.PREPARING,now(),"准备交接原劳动"));return false;
            }
            case PREPARING->{
                if(!permits(request,now())){put(o.advance(Stage.RELEASING,now(),"本次军役同意或原身体已改变"));return false;}
                var military=core.mobilization().orElseThrow();var record=military.service().orElseThrow().find(o.id());
                if(record.isPresent()){
                    var held=WorkCommitmentRuntime.existing(server).flatMap(w->w.holder(request.characterId()));
                    if(held.filter(w->w.intent().id().equals(o.id())).isPresent())WorkCommitmentRuntime.releaseIntake(server,request.characterId(),o.id());
                    else WorkCommitmentRuntime.reserveIntake(server,request.characterId(),o.id(),now()+100);
                    if(record.get().stage()==WarMobilization.Stage.ACTIVE&&military.service().orElseThrow().checkpointed(o.id())){put(o.advance(Stage.ACTIVE,now(),"已经动员，原NPC身体服役"));return false;}
                    if(!record.get().reviewReason().isEmpty()){String reason="动员记录要求核对："+record.get().reviewReason();put(o.advance(Stage.REVIEW,now(),reason.substring(0,Math.min(256,reason.length()))));return true;}
                    waiting.put(o.id(),"动员："+record.get().stage()+" · "+military.progress(o.id()));return false;
                }
                if(core.session().orElseThrow().phase()!=com.jgeted.sagadyssey.social.war.runtime.WarSession.Phase.ACTIVE){WorkCommitmentRuntime.releaseIntake(server,request.characterId(),o.id());waiting.put(o.id(),"军事受理已暂停，原民用职责继续");return false;}
                if(!military.service().orElseThrow().hasRoomFor(o.id())){WorkCommitmentRuntime.releaseIntake(server,request.characterId(),o.id());waiting.put(o.id(),"动员档已达容量，保留提议和原责任");return false;}
                var account=sources.characters().accountForCharacter(request.actorId());var player=account==null?null:server.getPlayerList().getPlayer(account);
                var npc=ItemLocation.personal(request.characterId(),o.body()).body(server,sources.characters());
                if(player==null||npc.isEmpty()||!near(player,npc.get())){WorkCommitmentRuntime.releaseIntake(server,request.characterId(),o.id());waiting.put(o.id(),"请领主与原NPC在六格内办理整备");return false;}
                if(!nativePorts.responsibilitiesPermitClaim(request)){WorkCommitmentRuntime.releaseIntake(server,request.characterId(),o.id());waiting.put(o.id(),"原迁移、远方控制或实物责任尚未交接");return false;}
                var problems=sources.responsibilities().inspect(Map.of(request.characterId(),o.body())).get(request.characterId());
                if(problems.stream().anyMatch(p->!p.code().equals("ACTIVE_CIVIL_PLAN"))){waiting.put(o.id(),"原职责需要交接："+problems.getFirst().code());return false;}
                try{requireCandidate(npc.get(),request.loadout(),request.armyId());}catch(IllegalArgumentException unavailable){WorkCommitmentRuntime.releaseIntake(server,request.characterId(),o.id());waiting.put(o.id(),unavailable.getMessage());return false;}
                if(!problems.isEmpty())NpcBrainRuntime.cancel(npc.get(),PlanCancellationReason.AUTHORIZED_COMMAND,"本人接受的军役整备；通过原取消流程释放预留");
                if(!sources.responsibilities().inspect(Map.of(request.characterId(),o.body())).get(request.characterId()).isEmpty()){waiting.put(o.id(),"等待原计划在安全边界完成交接");return false;}
                if(!WorkCommitmentRuntime.reserveIntake(server,request.characterId(),o.id(),now()+100)){waiting.put(o.id(),"原劳动已有其他持有人");return false;}
                var treasury=ItemLocation.player(request.actorId(),account);registerOwnContainer(treasury,request.actorId());registerOwnContainer(ItemLocation.personal(request.characterId(),o.body()),request.characterId());registerOwnContainer(ItemLocation.equipment(request.characterId(),o.body()),request.characterId());
                var campaign=WarCampaignRuntime.existing(server);var oldKit=nativePorts.kits().find(o.id());
                var armory=oldKit.map(WarKitRecord::armory).orElseGet(()->campaign.flatMap(c->c.depot(request.armyId())).map(WarCampaignEntry.Depot::location).orElse(treasury));
                var wageSource=campaign.map(c->c.treasury(request.armyId(),request.actorId(),account)).orElse(treasury);
                var kit=oldKit.map(WarKitRecord::lines).orElseGet(()->campaign.map(c->c.kit(request.armyId(),npc.get(),InitialWarDefinitions.LOADOUTS.find(request.loadout()).orElseThrow())).orElse(List.of()));
                var configured=nativePorts.configure(request,wageSource,armory,kit);
                if(configured==com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.Status.UNKNOWN){put(o.advance(Stage.REVIEW,now(),"粮饷或自备装备计划要求核对"));return true;}
                if(configured!=com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.Status.APPLIED){waiting.put(o.id(),"粮饷与装具计划："+configured);return false;}
                var result=military.begin(request);waiting.put(o.id(),"动员受理："+result.outcome()+" · "+result.reason());return false;
            }
            case ACTIVE->{
                if(!permits(request,now())){put(o.advance(Stage.RELEASING,now(),"本次军役同意已撤回或失效"));return false;}
                var record=core.mobilization().orElseThrow().service().orElseThrow().find(o.id());
                if(record.isEmpty())throw new IllegalStateException("active muster has no mobilization evidence");
                if(record.get().demobilizeRequested())put(o.advance(Stage.RELEASING,now(),"原动员已进入退役"));return false;
            }
            case RELEASING->{
                if(WarEngineeringRuntime.unresolved(server,o)){waiting.put(o.id(),"原工程提交或恢复证据尚未交接；保留原劳动待核对");return false;}
                if(WarCampaignRuntime.unresolved(server,o)){waiting.put(o.id(),"原军用货舱或运输提交尚未交接；保留劳动与货物，不伪造释放");return false;}
                WorkCommitmentRuntime.releaseIntake(server,request.characterId(),o.id());
                sources.services().endDelegation(o.id(),request.actorId(),request.characterId(),"本次军役结束",now());
                var military=core.mobilization().orElseThrow();var record=military.service().orElseThrow().find(o.id());
                if(record.isEmpty()||record.get().stage()==WarMobilization.Stage.RELEASED&&military.service().orElseThrow().checkpointed(o.id())){
                    put(o.advance(Stage.ENDED,now(),"本次劳动与借用责任已释放；历史欠饷仍归共享账本"));waiting.remove(o.id());
                    visibleActive.remove(o.id());WarDutyChangedEvent.publish(server,request.characterId());return true;
                }
                military.withdraw(o.id());waiting.put(o.id(),"退役："+record.get().stage());return false;
            }
            default->{return true;}
        }
    }
    private void registerOwnContainer(ItemLocation location,UUID owner){
        if(location.resolve(server,sources.characters()).isEmpty())throw new IllegalStateException("original personal container unavailable");
        var rights=RightsStateData.get(server);if(rights.context(location.id())==null)rights.ensureContext(location.id(),"person:"+owner+":"+location.kind(),SubjectRef.character(owner),SubjectRef.character(owner),AccessPolicy.PRIVATE);
    }
    private void requireCandidate(NpcBase npc,WarKey profile,UUID army){
        var person=sources.characters().byId(npc.getCharacterId());var binding=sources.characters().bodyOf(npc.getCharacterId());var life=sources.life().record(npc.getCharacterId());var needs=sources.life().needs(npc.getCharacterId());
        if(person==null||!person.alive()||binding==null||!binding.entityId().equals(npc.getUUID())||life==null||needs==null||npc.isBaby()||npc.isNoAi()||npc.isSleeping()||npc.getTarget()!=null||!npc.getTransportInventory().isEmpty())throw new IllegalArgumentException("原人物正在其他活动或身体不可整备");
        var contract=sources.life().contract(npc.getCharacterId());if(contract!=null&&contract.status().ongoing())throw new IllegalArgumentException("原民用工资契约尚未交接");
        if(Set.of(com.jgeted.sagadyssey.npc.profession.NpcProfession.WARRIOR,com.jgeted.sagadyssey.npc.profession.NpcProfession.ARCHER,com.jgeted.sagadyssey.npc.profession.NpcProfession.HEAVY).contains(npc.getProfession()))throw new IllegalArgumentException("当前入口只动员民用职业；常备军另行接入");
        var health=sources.injuries().observeTriage(npc.getCharacterId());if(health.incapacitated()||health.mortalDanger()||needs.hunger()>=800)throw new IllegalArgumentException("请先处理伤势或进食");
        var loadout=InitialWarDefinitions.LOADOUTS.find(profile).orElseThrow();
        var gaps=new HashSet<>(WarEquipmentInspector.inspect(npc,loadout.primaryWeapon()).mismatches(loadout));
        var principal=book.army(army).map(WarMusterEntry.Army::owner).orElseThrow();
        if(WarCampaignRuntime.existing(server).filter(c->c.mayBorrow(army,principal)).isPresent()){
            gaps.remove(WarEquipmentSnapshot.Mismatch.PRIMARY_WEAPON);if(loadout.shield())gaps.remove(WarEquipmentSnapshot.Mismatch.SHIELD);if(loadout.riderArmor())gaps.remove(WarEquipmentSnapshot.Mismatch.RIDER_ARMOR);
        }
        if(!gaps.isEmpty())throw new IllegalArgumentException("请通过原递交入口或实际军库补齐装具："+gaps);
    }
    private void requireCivilAuthority(NpcBase npc,UUID actor){var life=sources.life().record(npc.getCharacterId());if(life==null||life.authority()!=com.jgeted.sagadyssey.social.life.NpcWorkAuthority.NONE
            &&(life.authority()!=com.jgeted.sagadyssey.social.life.NpcWorkAuthority.SERVICE||!actor.equals(life.authorityId())))throw new IllegalArgumentException("原民用工作权威尚未交接");}
    private void put(WarMusterEntry e){var r=book.put(e,now());if(r.status()!=Status.APPLIED&&r.status()!=Status.PENDING)throw new IllegalStateException(r.reason());}
    private static boolean matches(Unit u,WarUnit v){return u.id().equals(v.id())&&u.army().equals(v.armyId())&&u.profile().equals(v.troopType())&&u.definitionVersion()==v.definitionVersion();}
    private static boolean near(ServerPlayer p,NpcBase n){return p.isAlive()&&n.isAlive()&&p.level()==n.level()&&p.distanceToSqr(n)<=36&&p.hasLineOfSight(n);}
    private static void requireNear(ServerPlayer p,NpcBase n){if(!near(p,n))throw new IllegalArgumentException("请在六格内面向可见的原NPC办理");}
    private static Result result(Reply r,String text,UUID id){return r.status()==Status.APPLIED||r.status()==Status.PENDING?Result.ok(text+"："+id,id):Result.no(r.reason());}
    private long now(){return server.overworld().getGameTime();}
    private void check(){if(!server.isSameThread()||closed)throw new IllegalStateException("muster server thread required");if(!problem.isEmpty()||!book.problem().isEmpty())throw new IllegalStateException(problem.isEmpty()?book.problem():problem);}
    private static Path path(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT).resolve("data/"+WarMusterJournal.FILE_NAME);}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void started(ServerStartedEvent event){
        var server=event.getServer();try{boolean present=false;var file=path(server);for(String suffix:List.of("",".old","_old"))try{Files.readAttributes(file.resolveSibling(file.getFileName()+suffix),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);present=true;}catch(NoSuchFileException missing){}
            if(present)open(server);
        }catch(IOException|RuntimeException e){FAILURES.put(server,"MUSTER_REQUIRES_REVIEW:"+e.getClass().getSimpleName());org.slf4j.LoggerFactory.getLogger(WarMusterRuntime.class).error("军役名册需要核对，保留原档",e);}
    }
    @SubscribeEvent(priority=EventPriority.LOW) public static void tick(ServerTickEvent.Post event){var value=STATES.get(event.getServer());if(value!=null)value.tick();}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){var value=STATES.remove(event.getServer());FAILURES.remove(event.getServer());if(value!=null)value.close();}
    @Override public void close(){if(closed)return;closed=true;for(var e:book.entries())if(e instanceof Offer o)WorkCommitmentRuntime.releaseIntake(server,o.request().characterId(),o.id());book.close();proposals.clear();ready.clear();activeChecks.clear();}
}
