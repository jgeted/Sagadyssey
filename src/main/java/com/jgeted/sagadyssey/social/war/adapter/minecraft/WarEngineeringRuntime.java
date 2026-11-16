package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.social.character.*;
import com.jgeted.sagadyssey.social.combat.InjuryData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarCommandService;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarEngineeringCodec;
import com.jgeted.sagadyssey.social.work.*;
import com.jgeted.sagadyssey.worldsim.transfer.ItemLocation;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Military scheduling only. Original work, fieldwork receipts, bodies, goods and tools remain authoritative. */
@EventBusSubscriber(modid="sagadyssey")
public final class WarEngineeringRuntime implements AutoCloseable{
    public static final String FILE="sagadyssey_war_engineering.sgw";
    private record Preview(UUID token,WarEngineeringJob job,long expires){}
    private record Stand(int cell,BlockPos feet){}
    private static final class Labor{UUID job;int cell;long last=-1,checked=-1,inventory=-1;int ticks,required;}
    private static final Map<MinecraftServer,WarEngineeringRuntime> STATES=new WeakHashMap<>();
    private static final Map<MinecraftServer,String> FAILURES=new WeakHashMap<>();
    private final WarEngineeringAdapter blocks;private final MinecraftServer server;private final DurableSnapshotStore<UUID,WarEngineeringJob> store;
    private final Map<UUID,UUID> workers=new HashMap<>();private final Map<UUID,Preview> previews=new HashMap<>();
    private final Map<UUID,Labor> labor=new HashMap<>();private final Map<UUID,Long> pathAt=new HashMap<>(),retryAt=new HashMap<>();
    private final Map<UUID,Integer> pathChoice=new HashMap<>();
    private final Map<UUID,Stand> stands=new HashMap<>();
    private final Map<UUID,String> problems=new HashMap<>();private final Set<UUID> liveIssue=new HashSet<>(),verifiedBodies=new HashSet<>();
    private final LinkedHashSet<UUID> publishing=new LinkedHashSet<>(),lifecycle=new LinkedHashSet<>();private String problem="";private long budgetTick=-1;private int mutations,paths;
    private WarEngineeringRuntime(MinecraftServer server)throws Exception{
        this.server=server;this.blocks=WarNativeBindings.currentBuild().engineering();
        if(!Files.exists(path(server))&&FieldworkData.get(server).receipts().stream().anyMatch(r->military(r.action().planId())))throw new IllegalStateException("missing engineering plan with original block effects");
        store=new DurableSnapshotStore<>(path(server),new WarEngineeringCodec(),WarEngineeringJob::id,WarEngineeringJob::follows,"sagadyssey-war-engineering");
        try{for(var j:store.recover()){
            if(j.changedAt()>now())throw new IllegalStateException("future engineering work");
            for(int i=0;i<j.next();i++)if(!j.adopted().contains(i)){var r=FieldworkData.get(server).receipt(j.id()+":"+i);if(r.isEmpty()||!blocks.receiptMatches(j,i,r.get()))throw new IllegalStateException("engineering ahead of original block/material receipts");}
            if(!j.terminal()){if(workers.putIfAbsent(j.service(),j.id())!=null)throw new IllegalStateException("duplicate engineer responsibility");lifecycle.add(j.id());}
        }}catch(RuntimeException invalid){store.close();throw invalid;}
    }
    public static Optional<WarEngineeringRuntime> existing(MinecraftServer s){return Optional.ofNullable(STATES.get(s));}
    static WarEngineeringRuntime openUnchecked(MinecraftServer s){try{return open(s);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("工程记录不可用",e);}}
    public static WarEngineeringRuntime open(MinecraftServer s)throws Exception{
        if(!s.isSameThread())throw new IllegalStateException("server thread required");if(FAILURES.containsKey(s))throw new IllegalStateException(FAILURES.get(s));var old=STATES.get(s);if(old!=null)return old;
        try{var r=new WarEngineeringRuntime(s);STATES.put(s,r);return r;}catch(Exception e){FAILURES.put(s,"ENGINEERING_REVIEW:"+e.getClass().getSimpleName());throw e;}
    }
    private static boolean military(UUID id){return id.getMostSignificantBits()==WarEngineeringJob.NAMESPACE;}
    public Collection<WarEngineeringJob> entries(){check();return store.desiredValues();}
    public Optional<WarEngineeringJob> find(UUID id){check();return store.desired(id);}
    public boolean durable(WarEngineeringJob j){return store.durable(j.id()).filter(j::equals).isPresent();}
    public String problem(){return problem;}
    private WarMusterRuntime muster(){return WarMusterRuntime.existing(server).orElseThrow();}
    private UUID owner(ServerPlayer p,UUID army){var who=muster().actor(p);if(!muster().book().owns(who,army)||WarServerEvents.existing(server).orElseThrow().permission(who,army)!=WarCommandService.Permission.ALLOWED)throw new IllegalArgumentException("仅本军当前领主人物可批准施工，OP不授予此权");return who;}
    public UUID authorize(ServerPlayer p,UUID army){check();return owner(p,army);}
    public NpcBase authorizedEngineer(ServerPlayer p,UUID service,boolean idle){
        check();if(!problem.isEmpty())throw new IllegalStateException(problem);var o=offer(service);owner(p,o.request().armyId());
        if(o.request().role()!=WarMilitiaRequest.Role.ENGINEER||o.stage()!=WarMusterEntry.Stage.ACTIVE||!muster().book().durable(o)||!WarServerEvents.existing(server).orElseThrow().servicePermits(o.request(),now()))throw new IllegalArgumentException("须先取得本人同意的工程军役并完成原劳动交接");
        if(idle&&(Optional.ofNullable(workers.get(service)).flatMap(store::desired).filter(j->!j.terminal()||!durable(j)).isPresent()
                ||WarHazardRuntime.existing(server).filter(r->r.surveying(service)).isPresent()))throw new IllegalArgumentException("原工程人员已有工程或侦察责任");
        var npc=ItemLocation.personal(o.request().characterId(),o.body()).body(server,CharacterRegistryData.get(server)).orElseThrow(()->new IllegalArgumentException("原工程身体未加载"));
        if(unrecordedBodyProof(npc))throw new IllegalStateException("原身体留有施工凭据而工程记录缺失，须核对");return npc;
    }
    public void inScope(ServerPlayer owner,NpcBase npc,WarIntent.Position p,boolean stand){
        var l=(ServerLevel)npc.level();var at=new BlockPos(p.x(),p.y(),p.z());
        if(!p.dimension().toString().equals(l.dimension().identifier().toString())||!l.getWorldBorder().isWithinBounds(at)||at.getY()<l.getMinY()||at.getY()>=l.getMaxY()
                ||npc.distanceToSqr(at.getX()+.5,at.getY()+.5,at.getZ()+.5)>128*128||owner.distanceToSqr(at.getX()+.5,at.getY()+.5,at.getZ()+.5)>128*128
                ||!blocks.permission(owner,l,at)||stand&&(!blocks.loaded(l,at)||!FieldworkRoutes.walkable(npc,at)))throw new IllegalArgumentException("路线须在同维度128格内并满足原施工权限；起始站位须实际安全可达");
    }
    public UUID previewProgram(ServerPlayer player,UUID service,WarEngineeringLayouts.Layout layout){
        var npc=authorizedEngineer(player,service,true);var o=offer(service);for(var p:layout.targets())inScope(player,npc,p,false);
        for(var step:layout.program().steps())inScope(player,npc,step.stand(),false);
        var cells=blocks.programCells(npc,layout);var first=layout.program().steps().getFirst();
        var j=new WarEngineeringJob(new UUID(WarEngineeringJob.NAMESPACE,UUID.randomUUID().getLeastSignificantBits()),o.request().armyId(),owner(player,o.request().armyId()),service,npc.getCharacterId(),npc.getUUID(),first.mode(),first.material(),cells,WarEngineeringJob.Phase.ACTIVE,0,Set.of(),now(),1,now(),false,"指定路线；只在到场后观察当前工作面",Optional.of(layout.program()));
        previews.entrySet().removeIf(e->e.getValue().expires()<now());if(previews.size()>=128||store.desiredValues().size()>=256)throw new IllegalArgumentException("有限工程记录达到上限");var token=UUID.randomUUID();previews.put(token,new Preview(token,j,now()+200));
        for(var c:cells){var p=c.position();((ServerLevel)npc.level()).sendParticles(player,net.minecraft.core.particles.ParticleTypes.END_ROD,false,false,p.x()+.5,p.y()+.5,p.z()+.5,1,0,0,0,0);}return token;
    }
    private WarMusterEntry.Offer offer(UUID service){return muster().book().offer(service).orElseThrow(()->new IllegalArgumentException("原军役不存在"));}
    private Optional<NpcBase> body(WarEngineeringJob j){return ItemLocation.personal(j.person(),j.body()).body(server,CharacterRegistryData.get(server));}
    private boolean unrecordedBodyProof(NpcBase npc){
        var raw=npc.getPersistentData().get(blocks.markerKey());if(raw==null)return false;
        if(!(raw instanceof net.minecraft.nbt.CompoundTag tag))return true;
        var id=tag.read("Job",UUIDUtil.CODEC);if(id.isEmpty()||!military(id.get()))return true;
        return find(id.get()).filter(j->j.person().equals(npc.getCharacterId())&&j.body().equals(npc.getUUID())).isEmpty();
    }
    private boolean permitted(WarEngineeringJob j){try{var o=offer(j.service());return o.request().actorId().equals(j.owner())&&o.request().armyId().equals(j.army())&&o.request().characterId().equals(j.person())&&o.body().equals(j.body())&&o.request().role()==WarMilitiaRequest.Role.ENGINEER&&o.stage()==WarMusterEntry.Stage.ACTIVE&&muster().book().durable(o)&&WarServerEvents.existing(server).orElseThrow().servicePermits(o.request(),now());}catch(RuntimeException absent){return false;}}
    private boolean ended(WarEngineeringJob j){var o=muster().book().offer(j.service());return o.filter(v->Set.of(WarMusterEntry.Stage.RELEASING,WarMusterEntry.Stage.ENDED,WarMusterEntry.Stage.DECLINED).contains(v.stage())||now()>=v.endsAt()).isPresent();}
    private ServerPlayer principal(WarEngineeringJob j){var account=CharacterRegistryData.get(server).accountForCharacter(j.owner());var p=account==null?null:server.getPlayerList().getPlayer(account);return p!=null&&Objects.equals(CharacterRegistry.currentId(p),j.owner())?p:null;}
    public UUID preview(ServerPlayer player,UUID service,WarEngineeringJob.Mode mode,BlockPos from,BlockPos to,Optional<WarKey> material){
        authorizedEngineer(player,service,true);if(store.desiredValues().size()>=256)throw new IllegalArgumentException("有限工程记录达到上限");
        check();var o=offer(service);var who=owner(player,o.request().armyId());if(o.request().role()!=WarMilitiaRequest.Role.ENGINEER||o.stage()!=WarMusterEntry.Stage.ACTIVE||!muster().book().durable(o)||!WarServerEvents.existing(server).orElseThrow().servicePermits(o.request(),now()))throw new IllegalArgumentException("须先取得本人同意的工程军役并完成原劳动交接");
        var active=Optional.ofNullable(workers.get(service)).flatMap(store::desired);if(active.filter(j->!j.terminal()||!durable(j)).isPresent())throw new IllegalArgumentException("原工程人员已有工作或待核对责任");
        var npc=ItemLocation.personal(o.request().characterId(),o.body()).body(server,CharacterRegistryData.get(server)).orElseThrow(()->new IllegalArgumentException("原工程身体未加载"));
        if(unrecordedBodyProof(npc))throw new IllegalStateException("原身体留有施工凭据而工程记录缺失，须核对，不能新建空账绕过");
        var dimension=WarKey.parse(npc.level().dimension().identifier().toString());var a=new WarIntent.Position(dimension,from.getX(),from.getY(),from.getZ());var b=new WarIntent.Position(dimension,to.getX(),to.getY(),to.getZ());
        var cells=blocks.preview(player,npc,a,b,mode,material);previews.entrySet().removeIf(e->e.getValue().expires()<now());if(previews.size()>=128)throw new IllegalArgumentException("工程预览达到上限");
        var id=new UUID(WarEngineeringJob.NAMESPACE,UUID.randomUUID().getLeastSignificantBits());if(store.desired(id).isPresent())throw new IllegalStateException("work identity collision");var token=UUID.randomUUID();var j=new WarEngineeringJob(id,o.request().armyId(),who,service,o.request().characterId(),o.body(),mode,material,cells,WarEngineeringJob.Phase.ACTIVE,0,Set.of(),now(),1,now(),false,"原领主批准的有限施工");previews.put(token,new Preview(token,j,now()+200));
        for(var c:cells){var at=blocks.position(c);((ServerLevel)npc.level()).sendParticles(player,net.minecraft.core.particles.ParticleTypes.END_ROD,false,false,at.getX()+.5,at.getY()+.5,at.getZ()+.5,1,0,0,0,0);}
        return token;
    }
    public String previewText(UUID token){var p=Objects.requireNonNull(previews.get(token));var j=p.job();if(j.program().isPresent()){
        var materials=new TreeMap<String,Integer>();j.program().get().steps().forEach(s->s.material().ifPresent(k->materials.merge(k.toString(),1,Integer::sum)));
        return "工程预览 "+j.program().get().kind()+"："+j.cells().size()+"步，最高材料投入 "+materials+"；挖掘时间与产物须到场看见工作面后确定，不探测地下。等待期间原粮饷继续。10秒内确认；遇矿物、容器、流体等停止。";
    }return "工程预览 "+j.mode()+"："+j.cells().size()+"格，材料 "+j.material().map(m->m+" × "+j.cells().size()).orElse("无投入，产物进入原背包")+"，约 "+j.cells().stream().mapToLong(WarEngineeringJob.Cell::workTicks).sum()+"刻有效劳动；仅为当前道路/工具前提估算，等待期间原粮饷继续。10秒内确认；不覆盖既有建筑。";}
    public UUID confirm(ServerPlayer player,UUID token){
        check();var p=previews.get(token);if(p==null||now()>p.expires())throw new IllegalArgumentException("工程预览已失效");var j=p.job();owner(player,j.army());if(!permitted(j))throw new IllegalArgumentException("原工程军役、人物或军队已改变");
        var active=Optional.ofNullable(workers.get(j.service())).flatMap(store::desired);if(active.filter(v->!v.terminal()||!durable(v)).isPresent())throw new IllegalArgumentException("原人物已接其他工程");
        var npc=authorizedEngineer(player,j.service(),true);for(var c:j.cells())if(j.program().isPresent())inScope(player,npc,c.position(),false);else{var at=blocks.position(c);if(!blocks.loaded((ServerLevel)npc.level(),at)||!blocks.state(npc.level() instanceof ServerLevel l?l:null,c.before()).equals(npc.level().getBlockState(at))||!blocks.permission(player,(ServerLevel)npc.level(),at))throw new IllegalArgumentException("预览期间实际方块或权限改变，请重新预览");}
        put(j);workers.put(j.service(),j.id());lifecycle.add(j.id());previews.remove(token);verifiedBodies.add(j.id());return j.id();
    }
    public String control(ServerPlayer player,UUID id,String action){
        check();var j=find(id).orElseThrow(()->new IllegalArgumentException("工程不存在"));owner(player,j.army());if(!durable(j))throw new IllegalArgumentException("原工程状态正在保存");if(j.terminal())return "工程已结束："+j.phase();
        var phase=switch(action){case "pause"->WarEngineeringJob.Phase.PAUSED;case "resume"->WarEngineeringJob.Phase.ACTIVE;case "cancel"->WarEngineeringJob.Phase.CANCELLED;default->throw new IllegalArgumentException("未知工程操作");};
        put(j.change(phase,now(),"领主"+action+"；已建部分和原物资保留"));release(j);return "工程变更正在保存；取消不拆除已建方块";
    }
    public String adopt(ServerPlayer player,UUID id){var j=find(id).orElseThrow();owner(player,j.army());if(!durable(j)||j.phase()!=WarEngineeringJob.Phase.ACTIVE)throw new IllegalArgumentException("工程不可接纳外部完成，未明提交须核对");var npc=body(j).orElseThrow();var c=j.cells().get(j.next());var l=(ServerLevel)npc.level();if(!blocks.loaded(l,blocks.position(c))||!l.getBlockState(blocks.position(c)).equals(blocks.state(l,c.after()))||!blocks.permission(player,l,blocks.position(c)))throw new IllegalArgumentException("实际方块尚未达到目标或没有权限");put(j.adoptCell(now(),"领主确认外部完成；不扣材料、不记NPC施工功绩"));release(j);return "已接纳此格实际外部完成";}
    public List<String> status(ServerPlayer player,UUID army){owner(player,army);var rows=new ArrayList<String>();for(var j:entries())if(j.army().equals(army)){var w=labor.get(j.service());rows.add(j.id()+" · "+j.mode()+" · "+j.phase()+" · "+j.next()+"/"+j.cells().size()+" · 当前有效劳动 "+(w==null?0:w.ticks)+" · "+(durable(j)?"已保存":"保存中")+" · "+problems.getOrDefault(j.id(),j.reason()));}if(!problem.isEmpty())rows.add(problem);return rows.isEmpty()?List.of("本军没有工程；小队可直接由领主批准，不要求工程负责人"):List.copyOf(rows);}
    public boolean retire(ServerPlayer player,UUID service){var id=workers.get(service);if(id==null){WarHazardRuntime.existing(server).ifPresent(r->r.cancel(service));return false;}var j=find(id).orElseThrow();owner(player,j.army());if(j.terminal()&&durable(j)){workers.remove(service);return false;}if(!j.retireAfter())put(j.retiring(now()));return true;}
    public static boolean unresolved(MinecraftServer server,WarMusterEntry.Offer offer){
        if(offer.request().role()!=WarMilitiaRequest.Role.ENGINEER)return false;if(FAILURES.containsKey(server))return true;var r=STATES.get(server);
        var npc=ItemLocation.personal(offer.request().characterId(),offer.body()).body(server,CharacterRegistryData.get(server));
        if(npc.isPresent()&&(r==null?npc.get().getPersistentData().contains(WarNativeBindings.currentBuild().engineering().markerKey()):r.unrecordedBodyProof(npc.get())))return true;
        return r!=null&&(!r.problem.isEmpty()||Optional.ofNullable(r.workers.get(offer.id())).flatMap(r::find).filter(j->!j.terminal()||!r.durable(j)).isPresent());
    }
    public boolean work(NpcBase npc,WorkCommitment work,long at){
        check();var moving=npc.getNavigation().getPath();if(moving!=null&&!moving.isDone()&&!WarNativeBindings.currentBuild().hazards().mayFollow(npc,moving))npc.getNavigation().stop();
        if(unrecordedBodyProof(npc))problem="ENGINEERING_ORIGINAL_BODY_PROOF_WITHOUT_PLAN";var id=workers.get(work.intent().id());if(id==null){if(problem.isEmpty()&&WarHazardRuntime.existing(server).filter(r->r.work(npc,work)).isPresent())return true;npc.getNavigation().stop();npc.reportWorkActivity("工程待命",null,problem.isEmpty()?"等待领主批准有限工程；沿用原粮饷":problem);return true;}var j=find(id).orElseThrow();
        if(j.terminal()){
            npc.getNavigation().stop();if(durable(j)){workers.remove(j.service());release(j);if(j.retireAfter()){var p=principal(j);if(p!=null)muster().retire(p,j.service());}}return true;
        }
        if(at!=now()||!j.body().equals(npc.getUUID())||!j.person().equals(npc.getCharacterId())||work.phase()!=WorkCommitment.Phase.ACTIVE){problem="ORIGINAL_ENGINEERING_BODY_REQUIRED";return true;}
        npc.setTarget(null);npc.stopUsingItem();npc.reportWorkActivity("工程 "+j.phase(),j.next()<j.cells().size()?blocks.position(j.cells().get(j.next())):null,problems.getOrDefault(id,j.reason()));
        if(!problem.isEmpty()||!durable(j)||j.phase()==WarEngineeringJob.Phase.REVIEW){npc.getNavigation().stop();reset(j);return true;}
        if(!verifiedBodies.contains(id)){
            int previous=-1;for(int i=0;i<j.next();i++)if(!j.adopted().contains(i))previous=i;
            if(previous>=0&&!blocks.marker(npc,j,previous)){put(j.change(WarEngineeringJob.Phase.REVIEW,now(),"原身体同档施工凭据缺失，不能猜测重放"));return true;}verifiedBodies.add(id);
        }
        if(j.phase()==WarEngineeringJob.Phase.ISSUING){finish(npc,j);return true;}
        if(j.retireAfter()||ended(j)){put(j.change(WarEngineeringJob.Phase.CANCELLED,now(),"停止新施工；保留已经发生的原方块和物资"));release(j);return true;}
        if(!permitted(j)){npc.getNavigation().stop();reset(j);problems.put(id,"原许可暂不可用，不推断新授权或抹去工程");return true;}
        if(j.phase()==WarEngineeringJob.Phase.PAUSED||!fit(npc)){npc.getNavigation().stop();reset(j);problems.put(id,"暂停或原伤势/饥饿/睡眠/威胁阻断；当前格劳动重新计算");return true;}
        var cell=j.cells().get(j.next());var target=blocks.position(cell);var l=(ServerLevel)npc.level();
        if(!cell.position().dimension().toString().equals(l.dimension().identifier().toString())||!blocks.loaded(l,target)){npc.getNavigation().stop();reset(j);problems.put(id,"原作业面未加载，不强制加载或跨维度施工");return true;}
        if(j.program().isPresent()&&!atApprovedStand(npc,j)){reset(j);navigate(npc,j,cell);return true;}
        if(j.unobserved()){
            npc.getNavigation().stop();if(now()<retryAt.getOrDefault(id,0L))return true;var p=principal(j);if(p==null){problems.put(id,"原领主人物须在线批准当前工作面");return true;}
            if(!blocks.permission(p,l,target)){problems.put(id,"当前工作面缺少原领主施工权限");return true;}
            var inspection=blocks.inspect(npc,j);
            if(inspection.alreadyDone()){
                put(j.adoptCell(now(),inspection.reason()));release(j);problems.remove(id);
                if(j.program().orElseThrow().kind()==WarEngineeringProgram.Kind.DISARM)
                    WarHazardRuntime.existing(server).ifPresent(r->r.visibleClear(j.army(),cell.position()));
                return true;
            }
            if(inspection.cell().isPresent()){put(j.observeCell(inspection.cell().get(),now(),inspection.reason()));reset(j);problems.remove(id);return true;}
            problems.put(id,inspection.reason());retryAt.put(id,now()+40);return true;
        }
        if(!l.getBlockState(target).equals(blocks.state(l,cell.before()))){npc.getNavigation().stop();reset(j);problems.put(id,"目标已变化；可暂停、重作计划或显式接纳外部完成");return true;}
        if(!blocks.reachable(npc,cell)||blocks.occupiesWorkSurface(npc,j)){reset(j);navigate(npc,j,cell);return true;}
        npc.getNavigation().stop();var registry=NpcBrainRuntime.reservations(server);var key=FieldworkWorldMutation.key(blocks.action(j));
        if(!registry.acquire(key,j.id(),j.body(),now(),now()+100).acquired()){reset(j);problems.put(id,"原作业面已被其他计划占用");return true;}registry.renew(key,j.id(),j.body(),now(),now()+100);
        if(now()<retryAt.getOrDefault(id,0L))return true;var p=principal(j);if(p==null){reset(j);problems.put(id,"原领主人物离线或生命已变，暂停新材料/方块提交");return true;}
        var clock=labor.computeIfAbsent(j.service(),k->new Labor());if(clock.last==now())return true;
        if(!Objects.equals(clock.job,j.id())||clock.cell!=j.next()||clock.last>now()||clock.last+1!=now()){clock.job=j.id();clock.cell=j.next();clock.ticks=0;clock.checked=-1;}
        if(clock.checked<0||clock.inventory!=npc.getPersonalInventory().revision()||now()-clock.checked>=20){
            var mutation=blocks.mutation(npc,p,j,()->permitted(j)&&fit(npc));var blocked=mutation.validate();if(blocked.isPresent()){reset(j);problems.put(id,blocked.get().code()+":"+blocked.get().detail());retryAt.put(id,now()+40);return true;}
            if(clock.inventory>=0&&clock.inventory!=npc.getPersonalInventory().revision())clock.ticks=0;
            clock.inventory=npc.getPersonalInventory().revision();clock.checked=now();clock.required=Math.max(cell.workTicks(),j.stepMode()==WarEngineeringJob.Mode.BUILD?20:blocks.workTicks(npc,l.getBlockState(target),target));
        }
        clock.last=now();clock.ticks++;
        if(clock.ticks>=clock.required){var next=j.change(WarEngineeringJob.Phase.ISSUING,now(),"真实劳动已完成；原单方块提交前先保存意图");put(next);liveIssue.add(next.id());reset(j);}else if(now()%10==0)npc.playAction(j.stepMode()==WarEngineeringJob.Mode.BUILD?com.jgeted.sagadyssey.npc.entity.NpcAction.PLACE:com.jgeted.sagadyssey.npc.entity.NpcAction.MINE,12);
        return true;
    }
    private void finish(NpcBase npc,WarEngineeringJob j){
        var a=blocks.action(j);var old=FieldworkData.get(server).receipt(a.key());
        if(old.isPresent()){
            if(!blocks.receiptMatches(j,j.next(),old.get())||!blocks.marker(npc,j,j.next())){put(j.change(WarEngineeringJob.Phase.REVIEW,now(),"原方块/材料回执与原身体凭据不一致"));return;}
            if(j.program().filter(p->p.kind()!=WarEngineeringProgram.Kind.TUNNEL).isPresent())try{WarHazardRuntime.open(server).receipt(npc,j,old.get());}catch(Exception ignored){problems.put(j.id(),"施工回执已存在；危险情报保存失败，请实际重查");}
            put(j.completeCell(now(),"原单方块、工具、物资和权属回执已确认"));liveIssue.remove(j.id());release(j);problems.remove(j.id());return;
        }
        if(!liveIssue.contains(j.id())){put(j.change(WarEngineeringJob.Phase.REVIEW,now(),"重启后ISSUING缺少原效果凭据；保留核对，不重复扣料或挖掘"));return;}
        if(j.retireAfter()||ended(j)){put(j.cancelUnissued(now(),"本进程确认尚未调用原事务，停止新施工并取消"));liveIssue.remove(j.id());release(j);return;}
        if(!fit(npc)||!permitted(j)||principal(j)==null)return;
        var cell=j.cells().get(j.next());
        if(j.program().isPresent()&&!atApprovedStand(npc,j)){navigateProgram(npc,j);return;}
        if(!blocks.reachable(npc,cell))return;
        var registry=NpcBrainRuntime.reservations(server);var key=FieldworkWorldMutation.key(a);if(!registry.acquire(key,j.id(),j.body(),now(),now()+100).acquired())return;registry.renew(key,j.id(),j.body(),now(),now()+100);
        budget();if(mutations>=2)return;mutations++;
        try{
            var result=FieldworkCommitService.commit(FieldworkData.get(server),a,now(),(action,at)->blocks.mutation(npc,principal(j),j,()->permitted(j)&&fit(npc)));
            if(result.status()==FieldworkCommitService.Status.BLOCKED){liveIssue.remove(j.id());put(j.notApplied(now(),"原事务明确BLOCKED并回滚："+result.blocker().orElseThrow().code()));release(j);retryAt.put(j.id(),now()+100);return;}
            blocks.notifyCommitted(npc,j);finish(npc,j);
        }catch(RuntimeException unknown){liveIssue.remove(j.id());put(j.change(WarEngineeringJob.Phase.REVIEW,now(),"原单方块事务异常，保留提交状态核对："+unknown.getClass().getSimpleName()));}
    }
    private void navigate(NpcBase npc,WarEngineeringJob j,WarEngineeringJob.Cell cell){
        if(j.program().isPresent()){navigateProgram(npc,j);return;}
        var stand=stands.get(j.id());if(stand!=null&&stand.cell()==j.next()&&WarNativeBindings.currentBuild().hazards().mayStand(npc,new WarIntent.Position(cell.position().dimension(),stand.feet().getX(),stand.feet().getY(),stand.feet().getZ()))&&blocks.workableFrom(npc,stand.feet(),cell)&&npc.distanceToSqr(stand.feet().getX()+.5,stand.feet().getY(),stand.feet().getZ()+.5)>.04&&blocks.centerOnStand(npc,stand.feet()))return;
        budget();if(now()<pathAt.getOrDefault(j.id(),0L)||paths>=2)return;paths++;pathAt.put(j.id(),now()+40);var l=(ServerLevel)npc.level();var target=blocks.position(cell);var candidates=new ArrayList<BlockPos>();
        for(int dy=0;dy<=1;dy++)for(var d:Direction.Plane.HORIZONTAL){var q=target.relative(d).above(dy);if(blocks.loaded(l,q)&&l.getBlockState(q).getCollisionShape(l,q).isEmpty()&&l.getBlockState(q.above()).getCollisionShape(l,q.above()).isEmpty()&&l.getBlockState(q.below()).isFaceSturdy(l,q.below(),Direction.UP)&&blocks.workableFrom(npc,q,cell))candidates.add(q);}
        candidates.removeIf(q->!WarNativeBindings.currentBuild().hazards().mayStand(npc,new WarIntent.Position(cell.position().dimension(),q.getX(),q.getY(),q.getZ())));
        candidates.sort(Comparator.comparingDouble(q->npc.distanceToSqr(q.getX()+.5,q.getY(),q.getZ()+.5)));if(!candidates.isEmpty()){int index=Math.floorMod(pathChoice.getOrDefault(j.id(),0),candidates.size());var goal=candidates.get(index);stands.put(j.id(),new Stand(j.next(),goal));pathChoice.put(j.id(),index+1);var route=npc.getNavigation().createPath(goal,0);if(route!=null&&route.getNodeCount()<=256&&WarNativeBindings.currentBuild().hazards().mayFollow(npc,route)&&(blocks.centerOnStand(npc,goal)||npc.getNavigation().moveTo(route,.8)))return;}
        problems.put(j.id(),"没有实际可达的作业通路；请先搭建通路或调整工程，不瞬移穿墙");
    }
    boolean fit(NpcBase n){var health=InjuryData.get(server).observeTriage(n.getCharacterId());var needs=NpcLifeData.get(server).needs(n.getCharacterId());return n.isAlive()&&!n.isNoAi()&&!n.isSleeping()&&!n.isPassenger()&&!n.isInWater()&&!health.incapacitated()&&!health.mortalDanger()&&n.getHealth()>n.getMaxHealth()*.25F&&needs!=null&&needs.hunger()<900&&needs.lastUpdatedAt()<=now()&&com.jgeted.sagadyssey.social.combat.CombatEncounterData.get(server).activeFor(n.getCharacterId(),now())==null&&(n.getLastHurtByMob()==null||n.tickCount-n.getLastHurtByMobTimestamp()>=100);}
    private boolean atApprovedStand(NpcBase npc,WarEngineeringJob j){var s=j.program().orElseThrow().steps().get(j.next()).stand();return npc.distanceToSqr(s.x()+.5,s.y(),s.z()+.5)<=.04&&FieldworkRoutes.walkable(npc,new BlockPos(s.x(),s.y(),s.z()))&&WarNativeBindings.currentBuild().hazards().mayStand(npc,s);}
    private void navigateProgram(NpcBase npc,WarEngineeringJob j){
        var s=j.program().orElseThrow().steps().get(j.next()).stand();var goal=new BlockPos(s.x(),s.y(),s.z());var hazards=WarNativeBindings.currentBuild().hazards();
        if(!blocks.loaded((ServerLevel)npc.level(),goal)||!FieldworkRoutes.walkable(npc,goal)||!hazards.mayStand(npc,s)){npc.getNavigation().stop();problems.put(j.id(),"批准站位尚不安全可达；先修通实际通路");return;}
        if(blocks.centerOnStand(npc,goal))return;budget();if(now()<pathAt.getOrDefault(j.id(),0L)||paths>=2)return;paths++;pathAt.put(j.id(),now()+40);
        var path=npc.getNavigation().createPath(goal,0);if(path==null||path.getNodeCount()>256||!hazards.mayFollow(npc,path)||!programPath(j,path)){npc.getNavigation().stop();problems.put(j.id(),"批准站位通路穿过已知或本工程危险，等待领主修路");return;}
        npc.getNavigation().moveTo(path,.8);
    }
    private boolean programPath(WarEngineeringJob j,net.minecraft.world.level.pathfinder.Path path){
        var p=j.program().orElseThrow();if(p.kind()!=WarEngineeringProgram.Kind.SPIKE_PIT&&p.kind()!=WarEngineeringProgram.Kind.WEB_SNARE)return true;
        for(int i=Math.max(1,path.getNextNodeIndex());i<path.getNodeCount();i++){var n=path.getNode(i);for(var c:j.cells())if(c.position().x()==n.x&&c.position().z()==n.z&&n.y>=c.position().y()-1&&n.y<=c.position().y()+3)return false;}return true;
    }
    private void reset(WarEngineeringJob j){labor.remove(j.service());}
    private void release(WarEngineeringJob j){reset(j);stands.remove(j.id());NpcBrainRuntime.reservations(server).releasePlan(j.id());}
    private void budget(){if(budgetTick!=now()){budgetTick=now();mutations=0;paths=0;}}
    private void put(WarEngineeringJob j){var r=store.persist(j);if(r.status()!=DurableSnapshotStore.Status.APPLIED&&r.status()!=DurableSnapshotStore.Status.PENDING){problem="ENGINEERING_PUBLICATION_REVIEW:"+r.reason();throw new IllegalStateException(problem);}if(r.status()==DurableSnapshotStore.Status.PENDING)publishing.add(j.id());}
    private void tick(){check();if(!problem.isEmpty())return;for(int n=Math.min(4,publishing.size());n>0;n--){var it=publishing.iterator();var id=it.next();it.remove();var r=store.persist(find(id).orElseThrow());if(r.status()==DurableSnapshotStore.Status.PENDING)publishing.add(id);else if(r.status()!=DurableSnapshotStore.Status.APPLIED){problem="ENGINEERING_SAVE_REVIEW:"+r.reason();return;}}
        if(WarMusterRuntime.existing(server).isEmpty())return;
        for(int n=Math.min(2,lifecycle.size());n>0;n--){var it=lifecycle.iterator();var id=it.next();it.remove();var j=find(id).orElseThrow();
            if(durable(j)&&!j.terminal()&&ended(j)&&(j.phase()==WarEngineeringJob.Phase.ACTIVE||j.phase()==WarEngineeringJob.Phase.PAUSED)){put(j.change(WarEngineeringJob.Phase.CANCELLED,now(),"原军役结束，停止未发生的后续施工"));release(j);j=find(id).orElseThrow();}
            if(!j.terminal()||!durable(j))lifecycle.add(id);
        }
    }
    private static Path path(MinecraftServer s){return s.getWorldPath(LevelResource.ROOT).resolve("data/"+FILE);}
    private long now(){return server.overworld().getGameTime();}
    private void check(){if(!server.isSameThread())throw new IllegalStateException("engineering requires server thread");}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void started(ServerStartedEvent e){var s=e.getServer();var p=path(s);if(Files.exists(p)||Files.exists(p.resolveSibling(p.getFileName()+".old"))||FieldworkData.get(s).receipts().stream().anyMatch(r->military(r.action().planId())))try{open(s);}catch(Exception ignored){}}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){var r=STATES.get(e.getServer());if(r!=null)try{r.tick();}catch(RuntimeException failure){r.problem="ENGINEERING_REVIEW:"+failure.getClass().getSimpleName();}}
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){var r=STATES.remove(e.getServer());FAILURES.remove(e.getServer());if(r!=null)r.close();}
    public void close(){store.close();workers.clear();previews.clear();labor.clear();publishing.clear();liveIssue.clear();}
}
