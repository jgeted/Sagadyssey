package com.jgeted.sagadyssey.social.war.adapter.minecraft;
import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarStaffRelayCodec;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarDispatchService;
import com.jgeted.sagadyssey.social.war.application.WarDutyClock;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.work.WorkCommitment;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** A durable single-use delegation. Owner permission remains unchanged; no assistant-wide command authority. */
@EventBusSubscriber(modid="sagadyssey")
public final class WarStaffRelayRuntime implements AutoCloseable{
    public static final String FILE="sagadyssey_war_staff_relay.sgw";
    public static final int DUTY_TICKS=40;
    private static final Map<MinecraftServer,WarStaffRelayRuntime> STATES=new WeakHashMap<>();private static final Map<MinecraftServer,String> FAILURES=new WeakHashMap<>();
    private final MinecraftServer server;private final DurableSnapshotStore<UUID,WarStaffRelay> store;private final LinkedHashSet<UUID> ready=new LinkedHashSet<>();private final Set<UUID> recoveredIssuing=new HashSet<>();private String problem="";
    private final WarDutyClock duty=new WarDutyClock(DUTY_TICKS,256);
    private WarStaffRelayRuntime(MinecraftServer s)throws Exception{server=s;store=new DurableSnapshotStore<>(path(s),new WarStaffRelayCodec(),WarStaffRelay::id,WarStaffRelay::follows,"sagadyssey-war-staff-relay");for(var r:store.recover())if(!r.terminal()){ready.add(r.id());if(r.phase()==WarStaffRelay.Phase.ISSUING)recoveredIssuing.add(r.id());}}
    public static WarStaffRelayRuntime open(MinecraftServer s)throws Exception{if(!s.isSameThread())throw new IllegalStateException("server thread required");if(FAILURES.containsKey(s))throw new IllegalStateException(FAILURES.get(s));var old=STATES.get(s);if(old!=null)return old;try{var r=new WarStaffRelayRuntime(s);STATES.put(s,r);return r;}catch(Exception e){FAILURES.put(s,"RELAY_REVIEW:"+e.getClass().getSimpleName());throw e;}}
    public Optional<WarStaffRelay> find(UUID id){return store.desired(id);}
    public static Optional<WarStaffRelayRuntime> existing(MinecraftServer server){return Optional.ofNullable(STATES.get(server));}
    public int dutyProgress(UUID task){return duty.progress(task,server.overworld().getGameTime());}
    public boolean occupies(UUID person){return problem.isEmpty()&&duty.personReserved(person);}
    public boolean pendingPerson(UUID person){return store.desiredValues().stream().filter(r->!r.terminal()).anyMatch(r->WarStaffRuntime.existing(server).flatMap(s->s.book().appointment(r.appointment())).filter(a->a.terms().holder().equals(person)).isPresent());}
    public int confirmedDuties(UUID person){return (int)store.durableValues().stream().filter(r->r.phase()==WarStaffRelay.Phase.SENT).filter(r->WarStaffRuntime.existing(server).flatMap(s->s.book().appointment(r.appointment())).filter(a->a.terms().holder().equals(person)).isPresent()).count();}
    /** Called only by the original military primary-work executor, never a world-time timer. */
    public boolean work(NpcBase npc,WorkCommitment work,long now){
        if(!server.isSameThread())throw new IllegalStateException("server thread required");
        try{return workStep(npc,work,now);}catch(RuntimeException invalid){problem="RELAY_REQUIRES_REVIEW:"+invalid.getClass().getSimpleName();duty.clear();return false;}
    }
    private boolean workStep(NpcBase npc,WorkCommitment work,long now){
        if(!problem.isEmpty())return false;var job=duty.service(work.intent().id());if(job.isEmpty())return false;var j=job.get();
        var r=find(j.task());if(r.isEmpty()||r.get().terminal()){duty.release(j.task());return false;}
        // Invalid facts earn no time. The bounded task loop will cancel and release the pending task.
        if(work.phase()!=WorkCommitment.Phase.ACTIVE||!j.person().equals(npc.getCharacterId())||!j.body().equals(npc.getUUID())
                ||!work.intent().character().equals(j.person())||!work.intent().body().equals(j.body())
                ||now!=server.overworld().getGameTime()||npc.isInWater()||npc.isPassenger()||!valid(r.get(),now))return true;
        duty.observe(j,now);return true;
    }
    public String status(ServerPlayer p,UUID id){
        var r=find(id).orElseThrow(()->new IllegalArgumentException("委托不存在"));var person=CharacterRegistryData.get(server).current(p.getUUID());
        if(person==null||!person.id().equals(r.post().binder()))throw new IllegalArgumentException("委托不属于当前人物");
        String state=switch(r.phase()){case QUEUED->"待处理";case ISSUING->"正在保存发令围栏";case SENT->"原军令已受理";case CANCELLED->"已取消";case REVIEW->"需要核对原记录";};
        return state+(r.equals(store.durable(id).orElse(null))?"":"（保存中）")+" · "+r.reason()+(!r.terminal()?" · 原身体职责时间 "+dutyProgress(id)+"/"+DUTY_TICKS+"刻":"")+(problem.isEmpty()?"":" · "+problem);
    }
    public UUID prepare(ServerPlayer p,UUID appointment){
        if(!problem.isEmpty())throw new IllegalStateException(problem);
        var staff=WarStaffRuntime.existing(server).orElseThrow();var a=staff.book().appointment(appointment).orElseThrow();
        var core=WarServerEvents.existing(server).orElseThrow();var media=core.commandMedia().orElseThrow();var post=media.post(p).orElseThrow(()->new IllegalArgumentException("请先选择兵团并登记实际集结旗"));
        if(!staff.mayRelay(p,a,post.unit())||!actual(p,post))throw new IllegalArgumentException("需要当前领主手持地图、原旗帜和可用助手");
        if(store.desiredValues().stream().anyMatch(r->!r.terminal()&&r.post().unit().equals(post.unit())))throw new IllegalArgumentException("此兵团已有待办指令");
        if(store.desiredValues().stream().filter(r->!r.terminal()).anyMatch(r->staff.book().appointment(r.appointment()).filter(old->old.terms().holder().equals(a.terms().holder())).isPresent()))throw new IllegalArgumentException("此助手已有待办军务，不能同时兼办多个兵团");
        var unit=core.session().orElseThrow().unit(post.unit()).orElseThrow();long now=server.overworld().getGameTime();var id=UUID.randomUUID();
        var task=new WarStaffRelay(id,appointment,post,core.session().orElseThrow().generation(),unit.revision(),now,Math.addExact(now,200),WarStaffRelay.Phase.QUEUED,1,now,"领主授权一次向原旗点集结；需原身体实际办理40刻军务");
        var reply=store.persist(task);if(reply.status()!=DurableSnapshotStore.Status.APPLIED&&reply.status()!=DurableSnapshotStore.Status.PENDING)throw new IllegalStateException(reply.reason());ready.add(id);return id;
    }
    private static boolean actual(ServerPlayer p,WarCommandPost post){
        var data=net.minecraft.world.item.MapItem.getSavedData(p.getMainHandItem(),p.level());
        if(p.isSpectator()||!p.getMainHandItem().is(net.minecraft.world.item.Items.FILLED_MAP)||data==null||!data.dimension.equals(p.level().dimension())||!post.position().dimension().toString().equals(p.level().dimension().identifier().toString()))return false;
        var at=new net.minecraft.core.BlockPos(post.position().x(),post.position().y(),post.position().z());var l=(net.minecraft.server.level.ServerLevel)p.level();
        return l.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4)!=null&&l.getBlockState(at).getBlock() instanceof net.minecraft.world.level.block.AbstractBannerBlock&&net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(l.getBlockState(at).getBlock()).toString().equals(post.block().toString());
    }
    private boolean valid(WarStaffRelay r,long now){
        var core=WarServerEvents.existing(server);if(core.isEmpty())return false;
        var account=CharacterRegistryData.get(server).accountForCharacter(r.post().binder());var player=account==null?null:server.getPlayerList().getPlayer(account);var staff=WarStaffRuntime.existing(server);var a=staff.flatMap(s->s.book().appointment(r.appointment()));var unit=core.get().session().flatMap(s->s.unit(r.post().unit()));
        boolean authority=player!=null&&a.isPresent()&&staff.get().mayRelay(player,a.get(),r.post().unit())&&actual(player,r.post())&&core.get().commandMedia().flatMap(m->m.post(player)).filter(r.post()::equals).isPresent();
        return unit.isPresent()&&core.get().session().orElseThrow().generation()==r.generation()&&r.mayIssue(now,unit.get().revision(),authority);
    }
    private void tick(){
        if(!problem.isEmpty())return;
        var core=WarServerEvents.existing(server);if(core.isEmpty()||core.get().nativeMobilization().isEmpty())return;
        for(int n=Math.min(2,ready.size());n>0;n--){var it=ready.iterator();UUID id=it.next();it.remove();var r=store.desired(id).orElseThrow();
            if(!r.equals(store.durable(id).orElse(null))){store.persist(r);ready.add(id);continue;}
            if(r.terminal()){duty.release(id);continue;}long now=server.overworld().getGameTime();var field=core.get().field().orElseThrow();
            var command=new WarCommand.IssueOrder(r.id(),r.generation(),r.post().binder(),r.post().army(),r.post().unit(),r.unitRevision(),new WarIntent(InitialWarDefinitions.RALLY,1,r.post().position()));
            var prior=field.transmission(r.post().binder(),r.post().army(),r.id());
            if(prior.isPresent()){put(r.advance(prior.get().command().equals(command)?WarStaffRelay.Phase.SENT:WarStaffRelay.Phase.REVIEW,now,"核对原军令回执，不重复发令"));ready.add(id);continue;}
            if(recoveredIssuing.remove(id)){put(r.advance(WarStaffRelay.Phase.REVIEW,now,"重启后的发令围栏缺原回执，不自动重发"));ready.add(id);continue;}
            if(!valid(r,now)){put(r.advance(WarStaffRelay.Phase.CANCELLED,now,"原授权、身体、旗点、期限或领主军令已改变"));ready.add(id);continue;}
            var a=WarStaffRuntime.existing(server).orElseThrow().book().appointment(r.appointment()).orElseThrow();var offer=WarMusterRuntime.existing(server).orElseThrow().book().offer(a.terms().service()).orElseThrow();
            if(!duty.reserve(new WarDutyClock.Job(id,offer.id(),a.terms().holder(),offer.body()))){put(r.advance(WarStaffRelay.Phase.CANCELLED,now,"原人物、军役或身体已有其他职责占用"));ready.add(id);continue;}
            if(!duty.ready(id,now)){ready.add(id);continue;}
            if(r.phase()==WarStaffRelay.Phase.QUEUED){put(r.advance(WarStaffRelay.Phase.ISSUING,now,"发令围栏正在保存"));ready.add(id);continue;}
            var result=field.dispatch(command);put(r.advance(result.outcome()==WarDispatchService.Outcome.ACCEPTED||result.outcome()==WarDispatchService.Outcome.REPLAYED?WarStaffRelay.Phase.SENT:WarStaffRelay.Phase.CANCELLED,now,"原身体已实际办理40刻军务；"+result.reason()));ready.add(id);
        }
    }
    private void put(WarStaffRelay r){var reply=store.persist(r);if(reply.status()!=DurableSnapshotStore.Status.APPLIED&&reply.status()!=DurableSnapshotStore.Status.PENDING)throw new IllegalStateException(reply.reason());if(r.terminal())duty.release(r.id());}
    private static Path path(MinecraftServer s){return s.getWorldPath(LevelResource.ROOT).resolve("data/"+FILE);}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void started(ServerStartedEvent e){if(Files.exists(path(e.getServer())))try{open(e.getServer());}catch(Exception ignored){}}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){var r=STATES.get(e.getServer());if(r!=null)try{r.tick();}catch(RuntimeException invalid){r.problem="RELAY_REQUIRES_REVIEW:"+invalid.getClass().getSimpleName();r.duty.clear();}}
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){var r=STATES.remove(e.getServer());FAILURES.remove(e.getServer());if(r!=null)r.close();}
    public void close(){duty.clear();store.close();}
}
