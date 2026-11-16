package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkActionReceipt;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarHazardCodec;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.work.WorkCommitment;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Replaceable army intelligence, never a world-effect ledger. Surveys use the engineer's original duty. */
@EventBusSubscriber(modid="sagadyssey")
public final class WarHazardRuntime implements AutoCloseable{
    public static final String FILE="sagadyssey_war_hazards.sgw";
    private static final Map<MinecraftServer,WarHazardRuntime> STATES=new WeakHashMap<>();
    private static final Map<MinecraftServer,String> FAILURES=new WeakHashMap<>();
    private static final class Survey{
        UUID service,army,person,body;WarIntent.Position stand;List<WarIntent.Position> cells;
        int next,ticks;long last=-1,pathAt;String reason="前往已批准侦察站位";
        final List<WarHazardObservation> seen=new ArrayList<>();final Map<WarIntent.Position,Long> clear=new HashMap<>();
    }
    private record Column(WarKey dimension,int x,int z){}
    private record Index(long revision,Map<Column,List<Integer>> heights){}
    private final MinecraftServer server;private final WarHazardAdapter nativeHazards;
    private final DurableSnapshotStore<UUID,WarHazardMemory> store;
    private final Map<UUID,Survey> surveys=new HashMap<>();private final Map<UUID,Index> indexes=new HashMap<>();
    private final LinkedHashSet<UUID> surveyLifecycle=new LinkedHashSet<>();
    private final LinkedHashSet<UUID> publishing=new LinkedHashSet<>();
    private long budgetAt=-1;private int checks,paths;private String problem="",notice="";
    private WarHazardRuntime(MinecraftServer s)throws Exception{
        server=s;nativeHazards=WarNativeBindings.currentBuild().hazards();
        store=new DurableSnapshotStore<>(path(s),new WarHazardCodec(),WarHazardMemory::army,WarHazardMemory::follows,"sagadyssey-war-hazards");
        try{for(var m:store.recover())if(m.changedAt()>now())throw new IllegalArgumentException("future intelligence");}catch(RuntimeException e){store.close();throw e;}
    }
    public static Optional<WarHazardRuntime> existing(MinecraftServer s){return Optional.ofNullable(STATES.get(s));}
    public static WarHazardRuntime open(MinecraftServer s)throws Exception{
        if(!s.isSameThread())throw new IllegalStateException("server thread required");
        if(FAILURES.containsKey(s))throw new IllegalStateException(FAILURES.get(s));var r=STATES.get(s);if(r!=null)return r;
        try{r=new WarHazardRuntime(s);STATES.put(s,r);return r;}catch(Exception e){FAILURES.put(s,"HAZARD_MEMORY_REVIEW:"+e.getClass().getSimpleName());throw e;}
    }
    public Optional<UUID> armyOf(NpcBase npc){
        return WarMusterRuntime.existing(server).flatMap(m->m.book().latest(npc.getCharacterId()))
                .filter(o->o.stage()==WarMusterEntry.Stage.ACTIVE&&o.body().equals(npc.getUUID()))
                .filter(o->WarServerEvents.existing(server).filter(r->r.servicePermits(o.request(),now())).isPresent()).map(o->o.request().armyId());
    }
    public Optional<WarHazardMemory> memory(UUID army){thread();return store.desired(army);}
    public String problem(){return problem;}
    public boolean durable(WarHazardMemory m){return store.durable(m.army()).filter(m::equals).isPresent();}
    public boolean blocked(UUID army,WarIntent.Position feet){
        thread();var m=store.desired(army);if(m.isEmpty()||!m.get().avoid())return false;
        var index=indexes.get(army);if(index==null||index.revision()!=m.get().revision()){
            var values=new HashMap<Column,List<Integer>>();for(var o:m.get().observations())values.computeIfAbsent(new Column(o.position().dimension(),o.position().x(),o.position().z()),k->new ArrayList<>()).add(o.position().y());
            index=new Index(m.get().revision(),values);indexes.put(army,index);
        }
        var heights=index.heights().get(new Column(feet.dimension(),feet.x(),feet.z()));
        return heights!=null&&heights.stream().anyMatch(y->feet.y()>=y-1&&feet.y()<=y+3);
    }
    private WarHazardMemory admitted(UUID army){var old=store.desired(army);if(old.isPresent())return old.get();if(store.desiredValues().size()>=64)throw new IllegalArgumentException("本存档军队侦察记录达到上限");return WarHazardMemory.empty(army,now());}
    public String policy(ServerPlayer p,UUID army,boolean enabled){WarEngineeringRuntime.openUnchecked(server).authorize(p,army);var m=admitted(army);put(m.policy(enabled,now()));return "已知陷阱避让 "+enabled+"；选择承担风险不会给予任何伤害免疫";}
    public List<String> status(ServerPlayer p,UUID army){
        WarEngineeringRuntime.openUnchecked(server).authorize(p,army);var rows=new ArrayList<String>();var m=memory(army);
        rows.add("本军已知危险："+m.map(x->x.observations().size()).orElse(0)+"；避让 "+m.map(WarHazardMemory::avoid).orElse(true)+"；观察为有时间的情报，地形变化后需重查");
        m.ifPresent(x->x.observations().forEach(o->rows.add(o.id()+" · "+o.kind()+" · "+o.position()+" · 观察于 "+o.observedAt()+" · "+o.source())));
        for(var s:surveys.values())if(s.army.equals(army))rows.add("侦察 "+s.service+" · "+s.next+"/"+s.cells.size()+" · "+s.reason);
        if(!problem.isEmpty())rows.add(problem);if(!notice.isEmpty())rows.add(notice);return List.copyOf(rows);
    }
    public boolean surveying(UUID service){return surveys.containsKey(service);}
    public String cancel(ServerPlayer p,UUID service){var e=WarEngineeringRuntime.openUnchecked(server);e.authorizedEngineer(p,service,false);cancel(service);return "侦察已取消；已经报告的情报保留";}
    void cancel(UUID service){surveys.remove(service);surveyLifecycle.remove(service);}
    public String survey(ServerPlayer p,UUID service,WarIntent.Position stand,WarIntent.Position from,WarIntent.Position to){
        thread();if(!problem.isEmpty())throw new IllegalStateException(problem);if(surveys.size()>=128)throw new IllegalArgumentException("同时侦察任务达到上限");var engineering=WarEngineeringRuntime.openUnchecked(server);var npc=engineering.authorizedEngineer(p,service,true);
        var army=armyOf(npc).orElseThrow();admitted(army);var cells=WarEngineeringGeometry.box(from,to,WarEngineeringJob.Mode.DIG);
        for(var pos:cells){if(!pos.dimension().equals(stand.dimension())||squared(pos,stand)>64)throw new IllegalArgumentException("侦察区域须在选定站位8格内，最多64格");engineering.inScope(p,npc,pos,false);}
        engineering.inScope(p,npc,stand,true);if(!nativeHazards.mayStand(npc,stand))throw new IllegalArgumentException("侦察站位属于本军已知危险，请另选站位或调整避让策略");
        put(admitted(army));var s=new Survey();s.service=service;s.army=army;s.person=npc.getCharacterId();s.body=npc.getUUID();s.stand=stand;s.cells=cells;surveys.put(service,s);surveyLifecycle.add(service);
        return "已批准原工程人员实际前往侦察；至少40刻观察，仅报告真实可见方块；重启需重新派遣，已报告情报保留";
    }
    public UUID disarm(ServerPlayer p,UUID service,UUID observation)throws Exception{
        var e=WarEngineeringRuntime.open(server);var npc=e.authorizedEngineer(p,service,true);var army=armyOf(npc).orElseThrow();
        var seen=memory(army).stream().flatMap(m->m.observations().stream()).filter(o->o.id().equals(observation)).findFirst().orElseThrow(()->new IllegalArgumentException("须使用本军实际观察记录"));
        if(now()<seen.observedAt()||now()-seen.observedAt()>2400)throw new IllegalArgumentException("观察已过期，请重新侦察");
        var stand=nativeHazards.safeStand(npc,seen.position()).orElseThrow(()->new IllegalArgumentException("目标周边没有安全的排障站位"));
        return e.previewProgram(p,service,WarEngineeringLayouts.disarm(seen,stand));
    }
    public boolean work(NpcBase npc,WorkCommitment commitment){
        var s=surveys.get(commitment.intent().id());if(s==null)return false;var e=WarEngineeringRuntime.existing(server).orElseThrow();
        if(!s.body.equals(npc.getUUID())||!s.person.equals(npc.getCharacterId())||commitment.phase()!=WorkCommitment.Phase.ACTIVE||!armyOf(npc).filter(s.army::equals).isPresent()){
            cancel(s.service);npc.getNavigation().stop();return true;
        }
        npc.setTarget(null);npc.stopUsingItem();npc.reportWorkActivity("工程侦察",new BlockPos(s.stand.x(),s.stand.y(),s.stand.z()),s.reason);
        if(!problem.isEmpty()||!e.fit(npc)){npc.getNavigation().stop();s.ticks=0;s.last=-1;s.reason="原身体、需求或侦察记录暂不可用";return true;}
        if(!nativeHazards.mayStand(npc,s.stand)){npc.getNavigation().stop();s.ticks=0;s.last=-1;s.reason="站位现在属于已知危险，可取消侦察后重新选址";return true;}
        if(npc.distanceToSqr(s.stand.x()+.5,s.stand.y(),s.stand.z()+.5)>.09){
            s.ticks=0;s.last=-1;budget();if(now()>=s.pathAt&&paths<2){paths++;s.pathAt=now()+40;
                if(!WarNativeBindings.currentBuild().engineering().centerOnStand(npc,new BlockPos(s.stand.x(),s.stand.y(),s.stand.z())))WarNativeBindings.currentBuild().actors().navigate(npc,s.stand,.8);}
            return true;
        }
        npc.getNavigation().stop();if(s.last==now())return true;if(s.last+1!=now())s.ticks=0;s.last=now();s.ticks++;
        if(s.ticks<40){s.reason="到场观察 "+s.ticks+"/40";return true;}
        budget();while(s.next<s.cells.size()&&checks<8){checks++;var at=s.cells.get(s.next++);var visible=nativeHazards.see(npc,at);if(visible.isEmpty())continue;var sight=visible.get();
            if(sight.kind().isPresent())s.seen.add(new WarHazardObservation(UUID.randomUUID(),s.army,s.person,s.body,at,sight.kind().get(),sight.state(),now(),WarHazardObservation.Source.SURVEY));else s.clear.put(at,now());
        }
        s.reason="正在核实实际可见方块";if(s.next==s.cells.size()){put(admitted(s.army).survey(s.seen,s.clear,now()));cancel(s.service);npc.reportWorkActivity("侦察完成",null,"实际发现 "+s.seen.size()+"处危险；遮挡范围保持未知");}
        return true;
    }
    public void receipt(NpcBase npc,WarEngineeringJob j,FieldworkActionReceipt receipt){
        if(j.program().isEmpty())return;var c=j.cells().get(j.next());var kind=nativeHazards.kind(c.after(),npc);
        try{var m=admitted(j.army());if(kind.isPresent()){
            var id=UUID.nameUUIDFromBytes((j.id()+":"+j.next()).getBytes(StandardCharsets.UTF_8));
            put(m.observe(new WarHazardObservation(id,j.army(),j.person(),j.body(),c.position(),kind.get(),c.after(),receipt.committedAt(),WarHazardObservation.Source.BUILT),now()));
        }else if(j.program().get().kind()==WarEngineeringProgram.Kind.DISARM)visibleClear(j.army(),c.position());
        }catch(IllegalArgumentException full){notice="本次危险情报未写入，请实际重查："+full.getMessage();}
    }
    void visibleClear(UUID army,WarIntent.Position position){
        try{memory(army).ifPresent(m->put(m.clear(position,now())));}catch(IllegalStateException failure){notice="现场已清除，请重查情报记录";}
    }
    private void put(WarHazardMemory m){
        if(store.desired(m.army()).filter(m::equals).isPresent())return;var r=store.persist(m);
        if(r.status()==DurableSnapshotStore.Status.PENDING)publishing.add(m.army());else if(r.status()!=DurableSnapshotStore.Status.APPLIED){problem="HAZARD_PUBLICATION_REVIEW:"+r.reason();throw new IllegalStateException(problem);}
    }
    private void tick(){thread();for(int n=Math.min(4,publishing.size());n>0;n--){var it=publishing.iterator();var id=it.next();it.remove();var r=store.persist(store.desired(id).orElseThrow());if(r.status()==DurableSnapshotStore.Status.PENDING)publishing.add(id);else if(r.status()!=DurableSnapshotStore.Status.APPLIED)problem="HAZARD_SAVE_REVIEW:"+r.reason();}
        for(int n=Math.min(4,surveyLifecycle.size());n>0;n--){var it=surveyLifecycle.iterator();var id=it.next();it.remove();var o=WarMusterRuntime.existing(server).flatMap(m->m.book().offer(id));if(o.isEmpty()||o.get().stage()!=WarMusterEntry.Stage.ACTIVE||now()>=o.get().endsAt())surveys.remove(id);else surveyLifecycle.add(id);}
    }
    private void budget(){if(budgetAt!=now()){budgetAt=now();checks=0;paths=0;}}
    private static long squared(WarIntent.Position a,WarIntent.Position b){long x=(long)a.x()-b.x(),y=(long)a.y()-b.y(),z=(long)a.z()-b.z();return x*x+y*y+z*z;}
    private void thread(){if(!server.isSameThread())throw new IllegalStateException("server thread required");}
    private long now(){return server.overworld().getGameTime();}
    private static Path path(MinecraftServer s){return s.getWorldPath(LevelResource.ROOT).resolve("data/"+FILE);}
    @SubscribeEvent public static void started(ServerStartedEvent e){var p=path(e.getServer());if(Files.exists(p)||Files.exists(p.resolveSibling(p.getFileName()+".old")))try{open(e.getServer());}catch(Exception ignored){}}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){existing(e.getServer()).ifPresent(r->{try{r.tick();}catch(RuntimeException failure){r.problem="HAZARD_REVIEW:"+failure.getClass().getSimpleName();}});}
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){var r=STATES.remove(e.getServer());FAILURES.remove(e.getServer());if(r!=null)r.close();}
    public void close(){store.close();surveys.clear();surveyLifecycle.clear();indexes.clear();publishing.clear();}
}
