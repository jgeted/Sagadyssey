package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.core.MinecraftApi;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.*;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarCampaignEntry.*;
import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarCommandService;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.work.*;
import com.jgeted.sagadyssey.social.service.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit campaign orchestration. Original entities, rights, physical effects and service wages remain authoritative. */
@EventBusSubscriber(modid="sagadyssey")
public final class WarCampaignRuntime implements AutoCloseable {
    public static final String FILE="sagadyssey_war_campaign.sgw";
    private static final String FEED="SagadysseyWarFeedV1";
    private static final Map<MinecraftServer,WarCampaignRuntime> STATES=new WeakHashMap<>();
    private static final Map<MinecraftServer,String> FAILURES=new WeakHashMap<>();
    private final MinecraftServer server;private final DurableSnapshotStore<UUID,WarCampaignEntry> store;
    private final PhysicalItemService physical;private final RightsStateData rights;
    private final WarNativeBindings.ServiceCheckpoint obligationCheckpoint;
    private final Set<UUID> recoveredAllowances=new HashSet<>();
    private final Map<UUID,UUID> carrierJobs=new HashMap<>();private final LinkedHashSet<UUID> ready=new LinkedHashSet<>();
    private final LinkedHashSet<UUID> publishing=new LinkedHashSet<>();
    private final Map<UUID,String> problems=new HashMap<>();private final Map<UUID,Long> nextPath=new HashMap<>(),retryAt=new HashMap<>();
    private String problem="";private long pathTick=-1;private int paths;
    private WarCampaignRuntime(MinecraftServer server)throws Exception {
        this.server=server;rights=RightsStateData.get(server);physical=PhysicalItemRuntime.activate(server);
        obligationCheckpoint=WarNativeBindings.currentBuild().serviceCheckpoints().apply(server);
        if(!Files.exists(path(server))&&rights.claims().stream().anyMatch(c->c.provenance().startsWith("sagadyssey:war/cargo/")&&c.encumbrances().contains("issued")))throw new IllegalStateException("missing campaign journal with original military cargo title evidence");
        store=new DurableSnapshotStore<>(path(server),new WarCampaignCodec(),WarCampaignEntry::id,WarCampaignEntry::follows,"sagadyssey-war-campaign");
        try{for(var e:store.recover()){
            if(e instanceof Shipment s&&(!s.run().terminal()||s.run().retireAfter())){
                if(s.run().changedAt()>now()||carrierJobs.putIfAbsent(s.run().service(),s.id())!=null)throw new IllegalArgumentException("duplicate/future carrier responsibility");
            }
            if(e instanceof Allowance a){if(a.changedAt()>now())throw new IllegalArgumentException("future allowance");recoveredAllowances.add(a.id());verifyAllowance(a);if(a.stop()<0||a.pending().isPresent()||a.owed(now()).signum()>0)ready.add(e.id());}
            if(e instanceof Shipment s)verifyShipment(s);
            if(e instanceof WarCampaignEntry.Horse h&&h.changedAt()>now())throw new IllegalStateException("future original mount care");
        }
        for(var r:ServiceRelationshipData.get(server).relationships())if(r.statusReason().startsWith(ServiceRelationshipData.OFFICE_ALLOWANCE_PREFIX)&&store.desired(r.id()).filter(Allowance.class::isInstance).isEmpty())throw new IllegalStateException("original allowance obligation without campaign evidence");
        for(var c:rights.claims())if(c.provenance().startsWith("sagadyssey:war/cargo/")&&c.encumbrances().contains("issued")){
            var parts=c.provenance().substring("sagadyssey:war/cargo/".length()).split("/");if(parts.length!=2)throw new IllegalStateException("invalid original cargo provenance");
            var s=store.desired(UUID.fromString(parts[1])).filter(Shipment.class::isInstance).map(Shipment.class::cast).orElseThrow(()->new IllegalStateException("orphan original military cargo"));if(s.run().terminal()||!s.run().service().toString().equals(parts[0])||!c.containerId().equals(ContainerIds.npcCargo(s.run().person())))throw new IllegalStateException("original cargo reference inconsistent");
        }}catch(RuntimeException invalid){store.close();throw invalid;}
    }
    public static Optional<WarCampaignRuntime> existing(MinecraftServer s){return Optional.ofNullable(STATES.get(s));}
    public static WarCampaignRuntime open(MinecraftServer s)throws Exception {
        if(!s.isSameThread())throw new IllegalStateException("server thread required");if(FAILURES.containsKey(s))throw new IllegalStateException(FAILURES.get(s));var v=STATES.get(s);if(v!=null)return v;
        try{v=new WarCampaignRuntime(s);STATES.put(s,v);return v;}catch(Exception e){FAILURES.put(s,"CAMPAIGN_REVIEW:"+e.getClass().getSimpleName());throw e;}
    }
    public Collection<WarCampaignEntry> entries(){check();return store.desiredValues();}
    public Optional<WarCampaignEntry> find(UUID id){check();return store.desired(id);}
    public boolean durable(WarCampaignEntry e){return store.durable(e.id()).filter(e::equals).isPresent();}
    public String problem(){return problem;}
    private CharacterRegistryData characters(){return CharacterRegistryData.get(server);}
    private WarMusterRuntime muster(){return WarMusterRuntime.existing(server).orElseThrow();}
    private UUID owner(ServerPlayer p,UUID army){check();var who=muster().actor(p);if(!muster().book().owns(who,army)||WarServerEvents.existing(server).orElseThrow().permission(who,army)!=WarCommandService.Permission.ALLOWED)throw new IllegalArgumentException("仅当前领主人物可管理本军保障，OP不授予此权");return who;}
    private WarMusterEntry.Offer service(UUID id){return muster().book().offer(id).orElseThrow(()->new IllegalArgumentException("原军役不存在"));}
    public Optional<Depot> depot(UUID army){return store.desired(army).filter(Depot.class::isInstance).map(Depot.class::cast).filter(this::durable);}
    /** Explicit claim only for a previously unclaimed single container; registered private/institutional title is preserved. */
    public String bindDepot(ServerPlayer player,UUID army,BlockPos pos){
        var actor=owner(player,army);var l=player.level();if(player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>36||!l.hasChunkAt(pos)||!(l.getBlockEntity(pos) instanceof net.minecraft.world.Container))throw new IllegalArgumentException("请在六格内指定已加载的单个箱子或木桶");
        String locator=l.dimension().identifier()+":"+pos.asLong();var contexts=rights.contexts().stream().filter(c->c.locator().equals(locator)).toList();var subject=SubjectRef.character(actor);
        String dimension=l.dimension().identifier().toString();
        if(com.jgeted.sagadyssey.social.life.NpcLifeData.get(server).records().stream().anyMatch(r->r.dimension().equals(dimension)&&pos.equals(r.privateStoragePosition())&&!r.npcId().equals(actor))
            ||com.jgeted.sagadyssey.social.household.HouseholdData.get(server).households().stream().anyMatch(r->r.dimension().equals(dimension)&&pos.equals(r.sharedStorage()))
            ||com.jgeted.sagadyssey.social.logistics.LogisticsData.get(server).warehouses().stream().anyMatch(r->r.dimension().equals(dimension)&&pos.equals(r.position())))throw new IllegalArgumentException("此处已有原人物、家庭或聚落仓储权属；请经原授权实际转交物资，不能军事接管");
        if(contexts.stream().anyMatch(c->!c.defaultBeneficialOwner().equals(subject)))throw new IllegalArgumentException("已有他人、家庭或机构仓库权属；不能军事征用");
        var marker=l.getBlockEntity(pos).getPersistentData().read("SagadysseyContainerIdentityV1",UUIDUtil.CODEC);
        if(marker.isEmpty()&&!contexts.isEmpty())throw new IllegalArgumentException("此位置已有原容器登记但缺少原身份；不能把替换箱子视为原库存，请核对旧责任或另设新库");
        UUID id=marker.orElseGet(()->contexts.isEmpty()?ContainerIds.block("military_depot",l.dimension().identifier().toString(),pos):contexts.getFirst().containerId());
        var context=rights.context(id);if(context!=null&&!context.defaultBeneficialOwner().equals(subject))throw new IllegalArgumentException("原容器权属不属于领主");
        var location=ItemLocation.block(id,l.dimension().identifier().toString(),pos);location.bindBlock(server);
        rights.ensureContext(id,locator,subject,subject,AccessPolicy.PRIVATE);
        var old=store.desired(army);if(old.isPresent()&&!(old.get() instanceof Depot))throw new IllegalStateException("depot identity collision");
        put(new Depot(army,actor,location,old.map(e->e.revision()+1).orElse(1L)));return "军库登记正在保存；不改变已有私产，原借装仍归还原库";
    }
    public ItemLocation treasury(UUID army,UUID principal,UUID account){return depot(army).filter(d->d.owner().equals(principal)).map(Depot::location).orElseGet(()->ItemLocation.player(principal,account));}
    public boolean mayBorrow(UUID army,UUID principal){return depot(army).filter(d->d.owner().equals(principal)&&d.location().resolve(server,characters()).isPresent()).isPresent();}
    /** Existing original kit plan is immutable; after publication it wins over later depot changes. */
    public List<WarKitRecord.Line> kit(UUID army,NpcBase npc,WarLoadout expected){
        if(depot(army).isEmpty())return List.of();var lines=new ArrayList<WarKitRecord.Line>();var seen=WarEquipmentInspector.inspect(npc,expected.primaryWeapon());
        if(!seen.availableWeapons().contains(expected.primaryWeapon()))lines.add(new WarKitRecord.Line(ItemFingerprint.of(new ItemStack(switch(expected.primaryWeapon()){case SWORD->Items.IRON_SWORD;case BOW->Items.BOW;case CROSSBOW->Items.CROSSBOW;case SPEAR->Items.IRON_SPEAR;})),1));
        if(expected.shield()&&!seen.observed().shield())lines.add(new WarKitRecord.Line(ItemFingerprint.of(new ItemStack(Items.SHIELD)),1));
        if(expected.riderArmor()&&!seen.observed().riderArmor())lines.add(new WarKitRecord.Line(ItemFingerprint.of(new ItemStack(Items.IRON_CHESTPLATE)),1));
        return List.copyOf(lines);
    }
    public static ItemLocation destination(ServerPlayer player, BlockPos pos) {
        var block = player.level().getBlockEntity(pos);
        if (block == null) throw new IllegalArgumentException("目的库原容器未加载");
        var id = block.getPersistentData().read("SagadysseyContainerIdentityV1", UUIDUtil.CODEC)
                .orElseThrow(() -> new IllegalArgumentException("目的库尚未正式登记"));
        return ItemLocation.block(id, player.level().dimension().identifier().toString(), pos);
    }
    public UUID ship(ServerPlayer player,UUID service,ItemLocation target,String item,int quantity,int trips){
        var o=service(service);var actor=owner(player,o.request().armyId());
        if(o.stage()!=WarMusterEntry.Stage.ACTIVE||!muster().book().durable(o)||o.request().role()!=WarMilitiaRequest.Role.STAFF||!permits(o))throw new IllegalArgumentException("承运人须有本军当前生效的专职军务军役");
        var previous=Optional.ofNullable(carrierJobs.get(service)).flatMap(store::desired).filter(Shipment.class::isInstance).map(Shipment.class::cast);
        if(previous.filter(s->s.run().terminal()&&!s.run().retireAfter()&&durable(s)).isPresent())carrierJobs.remove(service);
        if(carrierJobs.containsKey(service)||WarStaffRelayRuntime.existing(server).filter(r->r.pendingPerson(o.request().characterId())).isPresent())throw new IllegalArgumentException("原人物已有军务或运输占用");
        var depot=depot(o.request().armyId()).filter(d->d.owner().equals(actor)).orElseThrow(()->new IllegalArgumentException("请先登记并保存军库"));
        var npc=body(o).orElseThrow(()->new IllegalArgumentException("原承运身体未加载"));if(!npc.getTransportInventory().isEmpty())throw new IllegalArgumentException("原货舱尚有责任，不接新运单");
        if(target.kind()==ItemLocation.Kind.BLOCK){var c=rights.context(target.id());if(c==null||!c.defaultBeneficialOwner().equals(SubjectRef.character(actor)))throw new IllegalArgumentException("目的库必须已登记且属同一领主人物");}
        else if(target.kind()==ItemLocation.Kind.PERSONAL){var recipient=muster().book().latest(target.character());if(recipient.isEmpty()||!recipient.get().body().equals(target.owner())||!recipient.get().request().armyId().equals(o.request().armyId())||recipient.get().stage()!=WarMusterEntry.Stage.ACTIVE)throw new IllegalArgumentException("目的背包须是本军在役原人物");}
        else throw new IllegalArgumentException("只向已登记军库或本军原人物送达");
        var source=depot.location();var at=position(source);var to=position(target);if(!at.dimension().equals(to.dimension())||distance(at,to)>128*128)throw new IllegalArgumentException("首版运输限同维度128格内的实际端点");
        if(source.resolve(server,characters()).isEmpty()||target.resolve(server,characters()).isEmpty())throw new IllegalArgumentException("实际端点未加载");
        var itemId=net.minecraft.resources.Identifier.parse(item);if(!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(itemId))throw new IllegalArgumentException("未知物品");
        var fingerprint=new ItemFingerprint(item,0);var material=net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(itemId);if(material==Items.AIR||quantity>new ItemStack(material).getMaxStackSize()||npc.getTransportSlotLimit()<1)throw new IllegalArgumentException("首版每趟最多一个实际堆叠，承运人须有原货舱运力");
        var id=UUID.randomUUID();var r=new WarSupplyRun(id,o.request().armyId(),actor,service,o.request().characterId(),o.body(),quantity,trips,now(),Math.min(o.endsAt(),Math.addExact(now(),7L*24000)),WarSupplyRun.Phase.TO_SOURCE,0,0,1,now(),false,"原领主批准有界往返运输");
        put(new Shipment(r,source,target,fingerprint,Optional.empty()));carrierJobs.put(service,id);return id;
    }
    public String cancel(ServerPlayer p,UUID id,boolean retire){
        var s=store.desired(id).filter(Shipment.class::isInstance).map(Shipment.class::cast).orElseThrow();owner(p,s.run().army());if(s.run().terminal())return "原运单已结束";
        var r=s.run();if(retire&&!r.retireAfter()){r=r.retirement(now());put(new Shipment(r,s.source(),s.target(),s.item(),s.pending()));return "退役请求已记录；原货物返回来源后自动接续退役";}
        if(s.pending().isPresent())throw new IllegalArgumentException("原实物提交待核对；须确认原回执后再取消");
        if(r.phase()==WarSupplyRun.Phase.TO_SOURCE||r.phase()==WarSupplyRun.Phase.LOADING)put(s.next(r.advance(WarSupplyRun.Phase.CANCELLED,now(),"尚未领取，取消后无货物转移")));
        else if(r.phase()==WarSupplyRun.Phase.TO_TARGET||r.phase()==WarSupplyRun.Phase.UNLOADING)put(s.next(r.advance(WarSupplyRun.Phase.RETURNING,now(),"保留原货物，返回原来源")));
        return "运输取消已受理；已取货须真实返库后释放";
    }
    public boolean holds(UUID service){var id=carrierJobs.get(service);return id!=null&&store.desired(id).filter(Shipment.class::isInstance).map(Shipment.class::cast).filter(s->!s.run().terminal()||!durable(s)).isPresent();}
    public boolean hasTransportDuty(UUID service,UUID person,UUID body){
        var shipment=carrierJobs.get(service);
        return shipment!=null&&store.durable(shipment).filter(Shipment.class::isInstance).map(Shipment.class::cast)
                .map(Shipment::run).filter(run->!run.terminal()&&run.person().equals(person)&&run.body().equals(body)).isPresent();
    }
    public static boolean unresolved(MinecraftServer server,WarMusterEntry.Offer o){
        if(FAILURES.containsKey(server)&&o.request().role()==WarMilitiaRequest.Role.STAFF)return true;
        if(existing(server).filter(r->r.holds(o.id())).isPresent())return true;
        return ItemLocation.personal(o.request().characterId(),o.body()).body(server,CharacterRegistryData.get(server)).filter(n->!n.getTransportInventory().isEmpty()).isPresent();
    }
    public String allowance(ServerPlayer p,UUID appointment,int daily){
        var staff=WarStaffRuntime.existing(server).orElseThrow();var a=staff.book().appointment(appointment).filter(staff::effective).orElseThrow(()->new IllegalArgumentException("需要已保存且实际有效的原机构任命"));var t=a.terms();owner(p,t.army());
        if(store.desired(appointment).isPresent())throw new IllegalArgumentException("本任命已有津贴约定，不重复开账或改写历史");
        var o=service(t.service());var account=characters().accountForCharacter(t.owner());var terms=new WarAllowanceTerms(t.id(),t.army(),t.owner(),t.holder(),o.body(),o.id(),now(),t.endsAt(),daily);
        put(new Allowance(terms,treasury(t.army(),t.owner(),account),-1,1,now(),List.of(),Optional.empty(),0));ready.add(t.id());return "岗位津贴约定正在保存；原军饷仍独立履行，撤职停止新增但保留欠款";
    }
    public boolean allowanceReady(UUID id){var a=store.desired(id).filter(Allowance.class::isInstance).map(Allowance.class::cast);var r=ServiceRelationshipData.get(server).relationship(id);return a.isPresent()&&durable(a.get())&&r!=null&&matchesAllowance(a.get(),r)&&obligationCheckpoint.contains(r);}
    public List<String> status(ServerPlayer p,UUID army){
        owner(p,army);var rows=new ArrayList<String>();for(var e:store.desiredValues()){
            if(e instanceof Depot d&&d.id().equals(army))rows.add("军库 "+d.location().dimension()+" "+BlockPos.of(d.location().position())+(durable(d)?" · 已保存":" · 保存中"));
            if(e instanceof Shipment s&&s.run().army().equals(army))rows.add(s.id()+" · 运输 "+s.run().phase()+" · 已交付 "+s.run().delivered()+"/"+s.run().trips()+"趟 · "+s.item().itemId()+" ×"+s.run().quantity()+" · "+problems.getOrDefault(s.id(),s.run().reason()));
            if(e instanceof Allowance a&&a.terms().army().equals(army))rows.add(a.id()+" · 津贴每日 "+a.terms().daily()+" · 已付 "+a.paid()+" · 当前欠款 "+a.owed(now())+(a.stop()<0?" · 在计提":" · 已停计")+(allowanceReady(a.id())?" · 原约定已保存":" · 原约定待确认"));
            if(e instanceof WarCampaignEntry.Horse h&&muster().book().offer(h.service()).filter(o->o.request().armyId().equals(army)).isPresent())rows.add("原坐骑 "+h.id()+" · 最后实际喂料刻 "+h.fedAt()+" · 每12000刻需一份干草块 · "+problems.getOrDefault(h.id(),h.fresh(now())?"当前有实际供养凭据":"等待原背包实际饲料"));
        }if(rows.isEmpty())rows.add("尚无本军保障配置；原领主直接管理与原军饷仍可用");if(!problem.isEmpty())rows.add(problem);return List.copyOf(rows);
    }
    /** Returns true only when this original main worker owns a transport responsibility. */
    public boolean work(NpcBase npc,WorkCommitment work,long now){
        check();var id=carrierJobs.get(work.intent().id());if(id==null){if(!npc.getTransportInventory().isEmpty()){npc.getNavigation().stop();npc.setTarget(null);npc.reportWorkActivity("运输待核对",null,"原货舱有货但无可用运单，不释放或猜测重建");return true;}return false;}
        var s=(Shipment)store.desired(id).orElseThrow();var r=s.run();npc.setTarget(null);npc.stopUsingItem();
        if(r.terminal()){
            npc.getNavigation().stop();if(durable(s)){if(r.retireAfter()){var account=characters().accountForCharacter(r.owner());var p=account==null?null:server.getPlayerList().getPlayer(account);if(p!=null){carrierJobs.remove(work.intent().id());muster().retire(p,r.service());}}else carrierJobs.remove(work.intent().id());}return true;
        }
        if(!r.body().equals(npc.getUUID())||!r.person().equals(npc.getCharacterId())||work.phase()!=WorkCommitment.Phase.ACTIVE||now!=now()||!fit(npc)){npc.getNavigation().stop();problems.put(id,"原身体、伤势或需求不可履职；货物仍保留");return true;}
        npc.reportWorkActivity("军事运输 "+r.phase(),null,problems.getOrDefault(id,r.reason()));
        if(!problem.isEmpty()||!durable(s)){npc.getNavigation().stop();return true;}
        try{stepShipment(npc,s);}catch(RuntimeException invalid){npc.getNavigation().stop();problems.put(id,"运输需核对:"+invalid.getClass().getSimpleName());}
        return true;
    }
    private void stepShipment(NpcBase npc,Shipment s){
        var r=s.run();var o=service(r.service());if(r.phase()==WarSupplyRun.Phase.REVIEW){npc.getNavigation().stop();return;}
        if(s.pending().isPresent()){finishShipment(npc,s);return;}
        if(r.retireAfter()||now()>=r.deadline()||!permits(o)){
            if(r.phase()==WarSupplyRun.Phase.TO_SOURCE||r.phase()==WarSupplyRun.Phase.LOADING){put(s.next(r.advance(WarSupplyRun.Phase.CANCELLED,now(),"未取货，原军役、期限或退役要求结束运输")));return;}
            if(r.phase()==WarSupplyRun.Phase.TO_TARGET||r.phase()==WarSupplyRun.Phase.UNLOADING){put(s.next(r.advance(WarSupplyRun.Phase.RETURNING,now(),"原货物需返还原来源")));return;}
        }
        boolean picking=r.phase()==WarSupplyRun.Phase.TO_SOURCE||r.phase()==WarSupplyRun.Phase.LOADING;
        boolean returning=r.phase()==WarSupplyRun.Phase.RETURNING||r.phase()==WarSupplyRun.Phase.RETURN_UNLOAD;
        var site=picking||returning?s.source():s.target();var point=position(site);
        if(!point.dimension().toString().equals(npc.level().dimension().identifier().toString())||site.resolve(server,characters()).isEmpty()){npc.getNavigation().stop();problems.put(s.id(),"原端点未加载或原箱身份已改变");return;}
        if(distance(position(s.source()),point)>128*128){npc.getNavigation().stop();problems.put(s.id(),"目的端点移出原批准的128格范围，等待核对或取消返库");return;}
        if(!reachable(npc,site,point)){navigate(npc,point,s.id());return;}
        npc.getNavigation().stop();problems.remove(s.id());
        if(r.phase()==WarSupplyRun.Phase.TO_SOURCE){put(s.next(r.advance(WarSupplyRun.Phase.LOADING,now(),"原承运人实际到达来源")));return;}
        if(r.phase()==WarSupplyRun.Phase.TO_TARGET){put(s.next(r.advance(WarSupplyRun.Phase.UNLOADING,now(),"原承运人实际到达目的地")));return;}
        if(r.phase()==WarSupplyRun.Phase.RETURNING){put(s.next(r.advance(WarSupplyRun.Phase.RETURN_UNLOAD,now(),"原货物实际返回来源")));return;}
        if(now()<retryAt.getOrDefault(s.id(),0L))return;
        var cargo=cargo(npc);var own=SubjectRef.character(r.owner());rights.ensureContext(cargo.id(),"npc:"+r.person()+":cargo",SubjectRef.character(r.person()),own,AccessPolicy.PRIVATE);
        var from=picking?s.source().resolve(server,characters()).orElseThrow():cargo;var to=picking?cargo:site.resolve(server,characters()).orElseThrow();
        if(picking&&!npc.getTransportInventory().isEmpty()){put(s.next(r.advance(WarSupplyRun.Phase.REVIEW,now(),"原货舱已有其他物资，不混货")));return;}
        var op=named("shipment/"+s.id()+"/"+r.attempt());var intent=transfer(op,own,picking?TransferIntent.ISSUE:returning?TransferIntent.RETURN:TransferIntent.DELIVERY,from,to,s.item(),r.quantity(),now(),WarCampaignIdentity.cargo(r.service(),r.id()));
        put(new Shipment(r.attempted(now(),"原实物意图保存后才提交"),s.source(),s.target(),s.item(),Optional.of(intent)));
    }
    private void finishShipment(NpcBase npc,Shipment s){
        var r=s.run();boolean picking=r.phase()==WarSupplyRun.Phase.LOADING;var site=picking||r.phase()==WarSupplyRun.Phase.RETURN_UNLOAD?s.source():s.target();
        var endpoint=site.resolve(server,characters());if(endpoint.isEmpty()){problems.put(s.id(),"原端点未加载，保留原提交意图");return;}var bag=cargo(npc);
        var point=position(site);if(distance(position(s.source()),point)>128*128){npc.getNavigation().stop();problems.put(s.id(),"原提交端点已移出批准范围，保留意图待核对");return;}
        if(!reachable(npc,site,point)){navigate(npc,point,s.id());return;}npc.getNavigation().stop();
        var result=physical.submit(s.pending().orElseThrow(),Map.of(endpoint.get().id(),endpoint.get(),bag.id(),bag),now());
        if(result.status()==PhysicalItemService.Status.APPLIED){
            var next=picking?WarSupplyRun.Phase.TO_TARGET:r.phase()==WarSupplyRun.Phase.RETURN_UNLOAD?WarSupplyRun.Phase.CANCELLED:r.delivered()+1==r.trips()?WarSupplyRun.Phase.DONE:WarSupplyRun.Phase.TO_SOURCE;
            put(s.next(r.advance(next,now(),"原实物回执已确认")));if(next==WarSupplyRun.Phase.DONE)WarDutyExperience.complete(npc,s.id());problems.remove(s.id());
        }else if(result.status()==PhysicalItemService.Status.DENIED){
            if(r.attempt()==64)put(s.next(r.advance(WarSupplyRun.Phase.REVIEW,now(),"重试记录满，保留责任")));
            else{put(new Shipment(r.attempted(now(),"原回执证明未转移，稍后重试"),s.source(),s.target(),s.item(),Optional.empty()));retryAt.put(s.id(),now()+100);}
            problems.put(s.id(),"实物未转移:"+result.reason());
        }else{problems.put(s.id(),"原提交:"+result.status()+":"+result.reason());if(result.status()==PhysicalItemService.Status.UNKNOWN||result.status()==PhysicalItemService.Status.CONFLICT)problem="CAMPAIGN_PHYSICAL_REVIEW";}
    }
    private boolean reachable(NpcBase npc,ItemLocation site,WarIntent.Position point){
        if(!point.dimension().toString().equals(npc.level().dimension().identifier().toString())||npc.distanceToSqr(point.x()+.5,point.y()+.5,point.z()+.5)>9)return false;
        if(site.kind()!=ItemLocation.Kind.BLOCK)return site.body(server,characters()).filter(npc::hasLineOfSight).isPresent();
        var at=new BlockPos(point.x(),point.y(),point.z());var hit=npc.level().clip(new net.minecraft.world.level.ClipContext(npc.getEyePosition(),net.minecraft.world.phys.Vec3.atCenterOf(at),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,npc));
        return hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS||hit.getBlockPos().equals(at);
    }
    private void navigate(NpcBase npc,WarIntent.Position point,UUID job){
        if(!point.dimension().toString().equals(npc.level().dimension().identifier().toString())){npc.getNavigation().stop();problems.put(job,"原端点维度不同，不能传送交货");return;}
        if(now()>=nextPath.getOrDefault(job,0L)){if(pathTick!=now()){pathTick=now();paths=0;}if(paths<2){paths++;nextPath.put(job,now()+40);if(!npc.getNavigation().moveTo(point.x()+.5,point.y(),point.z()+.5,.75))problems.put(job,"实际道路或交接视线受阻，等待重试；没有传送或加载区块");}}
    }
    /** Queue-time freshness fence; feeding and original proof verification remain in the main work tick. */
    public boolean horseReady(NpcBase npc){
        check();if(!(npc.getVehicle() instanceof Horse horse))return !npc.isPassenger();
        return problem.isEmpty()&&store.desired(horse.getUUID()).filter(WarCampaignEntry.Horse.class::isInstance).map(WarCampaignEntry.Horse.class::cast).filter(h->durable(h)&&h.fresh(now())).isPresent();
    }
    public boolean horse(NpcBase npc,WarMilitiaRequest request){
        check();if(!(npc.getVehicle() instanceof Horse horse))return true;var ref=new WarMountReferenceCodec().read(npc.getPersistentData(),WarMountReferenceCodec.RIDER_KEY).orElseThrow();
        if(!ref.mount().equals(horse.getUUID())||!ref.principal().equals(request.actorId())||!Objects.equals(MinecraftApi.ownerId(horse),ref.owner()))return false;
        var old=store.desired(horse.getUUID());if(old.isPresent()&&!(old.get() instanceof WarCampaignEntry.Horse))return false;
        var h=old.map(WarCampaignEntry.Horse.class::cast).orElse(null);
        if(h==null){if(horse.getPersistentData().contains(FEED)){problem="ORIGINAL_MOUNT_CARE_WITHOUT_CAMPAIGN_EVIDENCE";throw new IllegalStateException(problem);}put(new WarCampaignEntry.Horse(horse.getUUID(),request.id(),request.characterId(),npc.getUUID(),request.actorId(),-1,1,now(),0,Optional.empty()));return false;}
        if(!h.owner().equals(request.actorId())||!durable(h)||!problem.isEmpty())return false;
        if(h.fresh(now())){
            var mark=horse.getPersistentData().getCompoundOrEmpty(FEED);var op=mark.read("Operation",UUIDUtil.CODEC);
            boolean valid=mark.getLongOr("At",-1)==h.fedAt()&&op.flatMap(physical::find).filter(v->v.phase()==PhysicalItemOperation.Phase.APPLIED&&v.intent().consumption().filter(c->c.quantity()==1&&c.item().equals(new ItemFingerprint("minecraft:hay_block",0))&&c.purpose().equals("WAR_HORSE_FEED:"+horse.getUUID())).isPresent()&&v.proof().orElseThrow().occurredAt()==h.fedAt()).isPresent();
            var originalSource=ItemLocation.personal(h.person(),h.body()).resolve(server,characters());valid=valid&&originalSource.filter(physical::verifyOwner).isPresent();
            if(!valid)problems.put(h.id(),"原坐骑与原实物饲料凭据不一致或原来源未加载，保留核对；不重新扣料猜测补喂");else problems.remove(h.id());return valid;
        }
        var location=h.pending().isPresent()?ItemLocation.personal(h.person(),h.body()):ItemLocation.personal(request.characterId(),npc.getUUID());var bag=location.resolve(server,characters());if(bag.isEmpty())return false;
        if(h.pending().isEmpty()){
            if(h.attempts()==4096)return false;var context=rights.context(bag.get().id());if(context==null)return false;
            var intent=PhysicalItemOperation.Intent.consume(new PhysicalItemOperation.Consumption(named("horse/"+h.id()+"/"+h.attempts()),bag.get().id(),SubjectRef.character(request.characterId()),new ItemFingerprint("minecraft:hay_block",0),1,"","WAR_HORSE_FEED:"+h.id(),context.revision(),now()));
            put(new WarCampaignEntry.Horse(h.id(),request.id(),request.characterId(),npc.getUUID(),h.owner(),h.fedAt(),h.revision()+1,now(),h.attempts()+1,Optional.of(intent)));return false;
        }
        var result=physical.submit(h.pending().get(),Map.of(bag.get().id(),bag.get()),now());
        if(result.status()==PhysicalItemService.Status.APPLIED){
            long at=result.proof().orElseThrow().occurredAt();put(new WarCampaignEntry.Horse(h.id(),h.service(),h.person(),h.body(),h.owner(),at,h.revision()+1,now(),h.attempts(),Optional.empty()));
            var mark=new CompoundTag();mark.putLong("At",at);mark.store("Operation",UUIDUtil.CODEC,h.pending().get().id());horse.getPersistentData().put(FEED,mark);return false;
        }
        if(result.status()==PhysicalItemService.Status.DENIED&&now()>=retryAt.getOrDefault(h.id(),0L)){put(new WarCampaignEntry.Horse(h.id(),h.service(),h.person(),h.body(),h.owner(),h.fedAt(),h.revision()+1,now(),h.attempts(),Optional.empty()));retryAt.put(h.id(),now()+100);}
        if(result.status()==PhysicalItemService.Status.UNKNOWN||result.status()==PhysicalItemService.Status.CONFLICT)problem="MOUNT_FEED_REVIEW";return false;
    }
    private void stepAllowance(Allowance a){
        if(!durable(a)||now()<retryAt.getOrDefault(a.id(),0L))return;var t=a.terms();
        var agreements=ServiceRelationshipData.get(server);var legal=agreements.relationship(a.id());
        if(legal==null){
            if(recoveredAllowances.contains(a.id()))throw new IllegalStateException("restored stipend lacks original legal agreement");
            var staff=WarStaffRuntime.existing(server);if(staff.flatMap(v->v.book().appointment(a.id()).filter(v::effective)).isEmpty())throw new IllegalStateException("original office changed before supplementary agreement");
            var parent=Objects.requireNonNull(agreements.relationship(t.service()));agreements.recordAcceptedOfficeAllowance(a.id(),t.owner(),t.person(),t.service(),parent.revision(),t.daily(),t.startsAt(),t.endsAt(),now());obligationCheckpoint.request();return;
        }
        if(!matchesAllowance(a,legal))throw new IllegalStateException("original stipend agreement terms changed");
        if(!obligationCheckpoint.contains(legal)){obligationCheckpoint.request();return;}
        if(!legal.status().grantsAuthority()){
            long stop=Objects.requireNonNull(legal.endsAt());if(a.stop()<0){put(new Allowance(t,a.treasury(),stop,a.revision()+1,now(),a.payments(),a.pending(),a.attempts()));return;}if(a.stop()!=stop)throw new IllegalStateException("original stipend stop time mismatch");
        }else if(a.stop()>=0)throw new IllegalStateException("military stop receipt is ahead of original agreement");
        if(a.stop()<0){var staff=WarStaffRuntime.existing(server);var current=staff.flatMap(v->v.book().appointment(t.id()));if(now()>=t.endsAt()||current.isEmpty()||!staff.get().effective(current.get())){agreements.stopOfficeAllowance(t.id(),Math.max(t.startsAt(),Math.min(now(),t.endsAt())));obligationCheckpoint.request();return;}}
        var bag=ItemLocation.personal(t.person(),t.body()).resolve(server,characters());var treasury=a.treasury().resolve(server,characters());if(bag.isEmpty()||treasury.isEmpty())return;
        if(a.pending().isEmpty()){
            if(a.owed(now()).signum()==0||a.attempts()==1024)return;
            int amount=a.owed(now()).min(java.math.BigInteger.valueOf(1000)).intValueExact();var op=named("allowance/"+a.id()+"/"+a.attempts());
            var intent=transfer(op,SubjectRef.character(t.owner()),TransferIntent.PAYMENT,treasury.get(),bag.get(),new ItemFingerprint("minecraft:emerald",0),amount,now());
            put(new Allowance(t,a.treasury(),a.stop(),a.revision()+1,now(),a.payments(),Optional.of(intent),a.attempts()+1));return;
        }
        var result=physical.submit(a.pending().get(),Map.of(bag.get().id(),bag.get(),treasury.get().id(),treasury.get()),now());
        if(result.status()==PhysicalItemService.Status.APPLIED){var list=new ArrayList<>(a.payments());list.add(new Payment(a.pending().get().id(),a.pending().get().transfer().orElseThrow().legs().getFirst().quantity(),result.proof().orElseThrow().occurredAt()));put(new Allowance(t,a.treasury(),a.stop(),a.revision()+1,now(),list,Optional.empty(),a.attempts()));}
        else if(result.status()==PhysicalItemService.Status.DENIED){put(new Allowance(t,a.treasury(),a.stop(),a.revision()+1,now(),a.payments(),Optional.empty(),a.attempts()));retryAt.put(a.id(),now()+100);}
        else if(result.status()==PhysicalItemService.Status.UNKNOWN||result.status()==PhysicalItemService.Status.CONFLICT)problem="ALLOWANCE_PAYMENT_REVIEW";
    }
    private boolean matchesAllowance(Allowance a,ServiceRelationship r){
        var t=a.terms();var prefix=ServiceRelationshipData.OFFICE_ALLOWANCE_PREFIX+t.service()+"/"+t.id();
        return r.principalId().equals(t.owner())&&r.agentId().equals(t.person())&&r.basis()==ServiceBasis.INSTITUTIONAL_OFFICE&&r.scopes().equals(Set.of(AuthorityScope.RECEIVE_SERVICE_ALLOWANCE))&&!r.limits().dangerousOrdersAllowed()&&!r.limits().privateInventoryAccess()&&r.startedAt()==t.startsAt()&&r.endsAt()!=null&&r.endsAt()<=t.endsAt()&&r.endsAt()>=t.startsAt()&&r.statusReason().startsWith(prefix)&&r.obligations().size()==1&&r.obligations().getFirst().kind().equals("office_allowance_daily")&&r.obligations().getFirst().description().equals(prefix)&&r.obligations().getFirst().responsible()==ObligationParty.PRINCIPAL&&r.obligations().getFirst().amount()==t.daily()&&r.obligations().getFirst().unit().equals("minecraft:emerald");
    }
    private void verifyAllowance(Allowance a){
        var legal=ServiceRelationshipData.get(server).relationship(a.id());if(legal==null||!matchesAllowance(a,legal))throw new IllegalStateException("original supplementary payment agreement missing or inconsistent");
        var known=new HashSet<UUID>();for(var p:a.payments()){
            var actual=physical.find(p.operation()).filter(v->v.phase()==PhysicalItemOperation.Phase.APPLIED&&v.proof().orElseThrow().occurredAt()==p.at()).orElseThrow(()->new IllegalStateException("original stipend payment proof missing"));
            var t=actual.intent().transfer().orElseThrow();var leg=t.legs().getFirst();if(t.intent()!=TransferIntent.PAYMENT||!t.initiatedBy().equals(SubjectRef.character(a.terms().owner()))||!leg.sourceContainerId().equals(a.treasury().id())||!leg.destinationContainerId().equals(ContainerIds.npcPersonal(a.terms().person()))||leg.quantity()!=p.amount()||!leg.fingerprint().equals(new ItemFingerprint("minecraft:emerald",0))||!actual.witness().equals(rights.itemOperationWitness(p.operation())))throw new IllegalStateException("stipend receipt does not match original payment");known.add(p.operation());
        }
        for(int i=0;i<a.attempts();i++){var op=named("allowance/"+a.id()+"/"+i);if(physical.find(op).filter(v->v.phase()==PhysicalItemOperation.Phase.APPLIED).isPresent()&&!known.contains(op)&&a.pending().filter(v->v.id().equals(op)).isEmpty())throw new IllegalStateException("orphan original stipend effect");}
    }
    private void verifyShipment(Shipment s){
        var r=s.run();if(r.changedAt()>now())throw new IllegalStateException("future supply run");if(r.phase()==WarSupplyRun.Phase.REVIEW)return;int picks=0,delivered=0,returned=0;TransferIntent pendingApplied=null;
        for(int i=0;i<r.attempt();i++){
            var actual=physical.find(named("shipment/"+s.id()+"/"+i));if(actual.isEmpty()||actual.get().phase()!=PhysicalItemOperation.Phase.APPLIED)continue;
            var v=actual.get();var t=v.intent().transfer().orElseThrow();if(t.legs().size()!=1||!t.initiatedBy().equals(SubjectRef.character(r.owner()))||!t.authority().equals(WarCampaignIdentity.cargo(r.service(),r.id()))||!v.witness().equals(rights.itemOperationWitness(v.intent().id())))throw new IllegalStateException("original cargo receipt actor or witness inconsistent");
            var leg=t.legs().getFirst();if(!leg.fingerprint().equals(s.item())||leg.quantity()!=r.quantity())throw new IllegalStateException("original cargo amount inconsistent");var cargo=ContainerIds.npcCargo(r.person());
            if(t.intent()==TransferIntent.ISSUE&&leg.sourceContainerId().equals(s.source().id())&&leg.destinationContainerId().equals(cargo))picks++;
            else if(t.intent()==TransferIntent.DELIVERY&&leg.sourceContainerId().equals(cargo)&&leg.destinationContainerId().equals(s.target().id()))delivered++;
            else if(t.intent()==TransferIntent.RETURN&&leg.sourceContainerId().equals(cargo)&&leg.destinationContainerId().equals(s.source().id()))returned++;
            else throw new IllegalStateException("original cargo receipt endpoints inconsistent");
            if(s.pending().filter(p->p.id().equals(v.intent().id())).isPresent())pendingApplied=t.intent();
        }
        int custody=r.custody()?1:0;if(pendingApplied==TransferIntent.ISSUE)custody++;if(pendingApplied==TransferIntent.DELIVERY||pendingApplied==TransferIntent.RETURN)custody--;
        if(delivered!=r.delivered()+(pendingApplied==TransferIntent.DELIVERY?1:0)||picks!=delivered+returned+custody||returned>1||returned==1&&r.phase()!=WarSupplyRun.Phase.CANCELLED&&pendingApplied!=TransferIntent.RETURN)throw new IllegalStateException("original cargo effects ahead of or missing from military evidence");
    }
    private PhysicalItemOperation.Intent transfer(UUID op,SubjectRef actor,TransferIntent intent,WitnessedItemEndpoint from,WitnessedItemEndpoint to,ItemFingerprint item,int quantity,long now){
        return transfer(op,actor,intent,from,to,item,quantity,now,"");
    }
    private PhysicalItemOperation.Intent transfer(UUID op,SubjectRef actor,TransferIntent intent,WitnessedItemEndpoint from,WitnessedItemEndpoint to,ItemFingerprint item,int quantity,long now,String authority){
        var revisions=List.of(new ExpectedRevision(from.id(),Objects.requireNonNull(rights.context(from.id())).revision()),new ExpectedRevision(to.id(),Objects.requireNonNull(rights.context(to.id())).revision())).stream().sorted(Comparator.comparing(r->r.containerId().toString())).toList();
        return PhysicalItemOperation.Intent.transfer(new TransferRequest(op,actor,intent,List.of(new TransferLeg(from.id(),to.id(),item,quantity)),revisions,authority,now));
    }
    private WitnessedItemEndpoint cargo(NpcBase n){var person=n.getCharacterId();return WitnessedItemEndpoint.forOwner(ContainerIds.npcCargo(person),n.getTransportInventory(),n::getPersistentData,()->!n.isRemoved()&&n.isAlive()&&person.equals(n.getCharacterId())&&server.isSameThread(),()->n.getTransportInventory().setChanged());}
    private Optional<NpcBase> body(WarMusterEntry.Offer o){return ItemLocation.personal(o.request().characterId(),o.body()).body(server,characters());}
    private boolean permits(WarMusterEntry.Offer o){return WarServerEvents.existing(server).filter(c->c.servicePermits(o.request(),now())).isPresent();}
    private boolean fit(NpcBase n){var h=com.jgeted.sagadyssey.social.combat.InjuryData.get(server).observeTriage(n.getCharacterId());var needs=com.jgeted.sagadyssey.social.life.NpcLifeData.get(server).needs(n.getCharacterId());return n.isAlive()&&!n.isNoAi()&&!n.isSleeping()&&!n.isInWater()&&!n.isPassenger()&&!h.incapacitated()&&!h.mortalDanger()&&n.getHealth()>n.getMaxHealth()*.25F&&needs!=null&&needs.hunger()<900&&needs.lastUpdatedAt()<=now()&&(n.getLastHurtByMob()==null||n.tickCount-n.getLastHurtByMobTimestamp()>=100);}
    private WarIntent.Position position(ItemLocation l){if(l.kind()==ItemLocation.Kind.BLOCK){var p=BlockPos.of(l.position());return new WarIntent.Position(WarKey.parse(l.dimension()),p.getX(),p.getY(),p.getZ());}var n=l.body(server,characters()).orElseThrow(()->new IllegalArgumentException("原目的身体未加载"));return new WarIntent.Position(WarKey.parse(n.level().dimension().identifier().toString()),n.getBlockX(),n.getBlockY(),n.getBlockZ());}
    private static double distance(WarIntent.Position a,WarIntent.Position b){double x=(long)a.x()-b.x(),y=(long)a.y()-b.y(),z=(long)a.z()-b.z();return x*x+y*y+z*z;}
    public static UUID named(String s){return WarCampaignIdentity.operation(s);}
    private void put(WarCampaignEntry e){var r=store.persist(e);if(r.status()!=DurableSnapshotStore.Status.APPLIED&&r.status()!=DurableSnapshotStore.Status.PENDING){problem=r.reason();throw new IllegalStateException(r.reason());}if(r.status()==DurableSnapshotStore.Status.PENDING)publishing.add(e.id());}
    private void tick(){
        check();if(!problem.isEmpty())return;obligationCheckpoint.tick(now());if(!obligationCheckpoint.problem().isEmpty()){problem=obligationCheckpoint.problem();return;}
        for(int n=Math.min(4,publishing.size());n>0;n--){
            var it=publishing.iterator();var id=it.next();it.remove();var row=store.desired(id).orElseThrow();var r=store.persist(row);
            if(r.status()==DurableSnapshotStore.Status.PENDING)publishing.add(id);
            else if(r.status()!=DurableSnapshotStore.Status.APPLIED){problem="CAMPAIGN_PUBLICATION_REVIEW:"+r.reason();return;}
            else if(row instanceof Shipment shipment)WarDutyChangedEvent.publish(server,shipment.run().person());
        }
        if(WarServerEvents.existing(server).flatMap(WarServerRuntime::nativeMobilization).isEmpty())return;
        for(int n=Math.min(2,ready.size());n>0;n--){var it=ready.iterator();var id=it.next();it.remove();var a=(Allowance)store.desired(id).orElseThrow();try{stepAllowance(a);}catch(RuntimeException invalid){problem="ALLOWANCE_REVIEW:"+invalid.getClass().getSimpleName();}var current=(Allowance)store.desired(id).orElseThrow();if(current.stop()<0||current.pending().isPresent()||current.owed(now()).signum()>0||!durable(current))ready.add(id);}
    }
    private void check(){if(!server.isSameThread())throw new IllegalStateException("campaign requires server thread");}
    private long now(){return server.overworld().getGameTime();}
    private static Path path(MinecraftServer s){return s.getWorldPath(LevelResource.ROOT).resolve("data/"+FILE);}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void started(ServerStartedEvent e){var p=path(e.getServer());if(Files.exists(p)||Files.exists(p.resolveSibling(p.getFileName()+".old"))||Files.exists(p.resolveSibling(p.getFileName()+"_old"))||RightsStateData.get(e.getServer()).claims().stream().anyMatch(c->c.provenance().startsWith("sagadyssey:war/cargo/")&&c.encumbrances().contains("issued"))||ServiceRelationshipData.get(e.getServer()).relationships().stream().anyMatch(r->r.statusReason().startsWith(ServiceRelationshipData.OFFICE_ALLOWANCE_PREFIX)))try{open(e.getServer());}catch(Exception ignored){}}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){var r=STATES.get(e.getServer());if(r!=null)try{r.tick();}catch(RuntimeException invalid){r.problem="CAMPAIGN_REVIEW:"+invalid.getClass().getSimpleName();}}
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){var r=STATES.remove(e.getServer());FAILURES.remove(e.getServer());if(r!=null)r.close();}
    public void close(){store.close();carrierJobs.clear();ready.clear();publishing.clear();}
}
