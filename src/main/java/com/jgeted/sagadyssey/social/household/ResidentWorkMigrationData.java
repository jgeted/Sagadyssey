package com.jgeted.sagadyssey.social.household;

import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.execution.PlanCancellationReason;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.logistics.*;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.ProductionOrderData;
import com.jgeted.sagadyssey.worldsim.PersistentDataAccess;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Explicit consenting individual application; no population creation, asset inheritance or hidden teleport. */
public final class ResidentWorkMigrationData extends SavedData {
    public static final String NAME="sagadyssey_resident_work_moves_v1";
    public static final Factory<ResidentWorkMigrationData> FACTORY=new Factory<>(ResidentWorkMigrationData::new,ResidentWorkMigrationData::load);
    public static final long DEADLINE=24000;
    private final Map<UUID,CompoundTag> applications=new LinkedHashMap<>();
    private final Map<UUID,CompoundTag> deathGoods=new LinkedHashMap<>();
    public CompoundTag deathGoods(UUID person){var t=deathGoods.get(person);return t==null?null:t.copy();}
    public static ResidentWorkMigrationData get(MinecraftServer s){return PersistentDataAccess.getOrCreate(s,FACTORY,NAME);}
    public static ResidentWorkMigrationData existing(MinecraftServer s){var d=s.overworld().getDataStorage().get(FACTORY,NAME);
        var path=s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data");
        if(d==null&&(java.nio.file.Files.exists(path.resolve(NAME+".dat"))||java.nio.file.Files.exists(path.resolve(NAME+".dat_old"))))throw new IllegalStateException("Resident move ledger unreadable; preserve original identity and obligations");return d;}
    public CompoundTag application(UUID person){var t=applications.get(person);return t==null?null:t.copy();}
    public List<CompoundTag> applications(){return applications.values().stream().map(CompoundTag::copy).toList();}
    public static boolean moving(MinecraftServer s,UUID person){var d=existing(s);var t=d==null?null:d.applications.get(person);return t!=null&&Set.of("WALKING","WAIT_DESTINATION","REVIEW").contains(t.getString("State"));}
    /** A departed original no longer owns the source village's unload boundary; footprint chunks still do. */
    public static boolean departed(MinecraftServer s,UUID person){if(com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.departed(s,person))return true;var d=existing(s);var t=d==null?null:d.applications.get(person);return t!=null&&t.contains("Departed");}
    private static NaturalVillageBuilding from(CompoundTag t){return NaturalVillageLifeData.decode(t.getCompound("Source"));}
    private static NaturalVillageBuilding to(CompoundTag t){return NaturalVillageLifeData.decode(t.getCompound("Destination"));}
    private static boolean alive(MinecraftServer s,UUID person){var p=CharacterRegistryData.get(s).byId(person);return p!=null&&p.alive();}
    private static NpcBase actor(MinecraftServer s,NaturalVillageBuilding b,int i){var l=SupplyFlowService.level(s,b.dimension());return l!=null&&l.getEntity(NaturalVillageLifeData.body(b,i)) instanceof NpcBase n?n:null;}
    private static boolean ready(MinecraftServer s,NaturalVillageBuilding b){var d=NaturalVillageLifeData.existing(s);var t=d==null?null:d.village(b.buildingKey());return t!=null&&t.getBoolean("Ready")&&NaturalVillageLifeData.decode(t).equals(b);}
    private static boolean physical(MinecraftServer s,NaturalVillageBuilding b){var d=NaturalVillageEconomyData.existing(s);var r=d==null?null:d.row(b.buildingKey());return r==null||r.getString("Phase").equals("NEAR");}
    public static BlockPos bed(NaturalVillageBuilding b){return NaturalVillageLayout.at(b,NaturalVillageLayout.spareBeds().getFirst());}
    public static BlockPos workMin(NaturalVillageBuilding b){return NaturalVillageLayout.at(b,new BlockPos(18,1,14));}
    public static BlockPos workMax(NaturalVillageBuilding b){return NaturalVillageLayout.at(b,new BlockPos(20,3,18));}
    private static boolean inZone(BlockPos p,NaturalVillageBuilding b){var a=workMin(b);var z=workMax(b);return p.getX()>=Math.min(a.getX(),z.getX())&&p.getX()<=Math.max(a.getX(),z.getX())&&p.getY()>=Math.min(a.getY(),z.getY())&&p.getY()<=Math.max(a.getY(),z.getY())&&p.getZ()>=Math.min(a.getZ(),z.getZ())&&p.getZ()<=Math.max(a.getZ(),z.getZ());}
    /** Caller delivers the resident's explicit application; v2 release/admission charter is the only consent rule. */
    public String request(MinecraftServer s,UUID person,NaturalVillageBuilding source,NaturalVillageBuilding target){
        if(!s.isSameThread())throw new IllegalStateException("Resident application requires server thread");
        var prior=applications.get(person);
        if(prior!=null)return from(prior).equals(source)&&to(prior).equals(target)?prior.getString("State"):"CONFLICTING_APPLICATION";
        if(!source.templateId().equals(NaturalVillageLayout.WOOD_V2.toString())||!target.templateId().equals(NaturalVillageLayout.WOOD_V2.toString())
                ||!source.dimension().equals(target.dimension())||source.settlementId().equals(target.settlementId())
                ||source.buildingOrigin().distSqr(target.buildingOrigin())>256*256||!person.equals(NaturalVillageLifeData.person(source,2))||!ready(s,source)||!ready(s,target))return "UNSUPPORTED";
        if(applications.size()>=64||applications.values().stream().anyMatch(t->!Set.of("DONE","REJECTED").contains(t.getString("State"))))return "OTHER_APPLICATION_ACTIVE";
        var whole=NaturalHouseholdMigrationData.existing(s);if(whole!=null&&whole.moves().stream().anyMatch(t->!t.getString("State").equals("DONE")))return "OTHER_APPLICATION_ACTIVE";
        var t=new CompoundTag();t.putUUID("Person",person);t.putUUID("Body",NaturalVillageLifeData.body(source,2));
        t.putUUID("Id",ContainerIds.named("resident-work-move:v1:"+person+":"+target.buildingKey()));
        t.put("Source",NaturalVillageLifeData.get(s).village(source.buildingKey()));t.put("Destination",NaturalVillageLifeData.get(s).village(target.buildingKey()));
        t.putLong("Applied",s.overworld().getGameTime());t.putLong("Deadline",s.overworld().getGameTime()+DEADLINE);
        t.putString("State","WAITING");t.putString("Reason","本人明确申请，按版本化留任与接纳规则核对，不预留报酬或财产");
        applications.put(person,t);setDirty();history(s,t,"resident_move_applied");return "WAITING";
    }
    public static String obligations(MinecraftServer s,UUID person,UUID body){
        var f=FieldworkData.get(s);long now=s.overworld().getGameTime();
        if(f.pendingDelivery(body).isPresent()||f.pendingCargo(body).values().stream().anyMatch(q->q>0)||!f.outstandingScaffolds(body).isEmpty())return "田作交付、真实产出或脚手架义务未结清";
        if(SupplyFlowData.get(s).routes().stream().anyMatch(r->!r.closed()&&(person.equals(r.carrierCharacter())||body.equals(r.carrierEntity()))))return "原承运路线尚未结清";
        if(LogisticsData.get(s).orders().stream().anyMatch(o->body.equals(o.assignedNpcId())&&o.status()!=LogisticsOrderStatus.DELIVERED&&o.status()!=LogisticsOrderStatus.CANCELLED))return "原物流单尚未结清";
        if(!ProductionOrderData.get(s).assignedTo(body).isEmpty()||!ProductionOrderData.get(s).assignedTo(person).isEmpty())return "原生产承诺尚未结清";
        var rights=RightsStateData.get(s);var owner=SubjectRef.character(person);var bag=ContainerIds.npcPersonal(person);
        if(rights.reservations().stream().anyMatch(r->r.activeAt(now)&&(owner.equals(r.reservedFor())||bag.equals(r.containerId()))))return "个人库存或名下预留尚未结清";
        if(rights.claims().stream().anyMatch(c->bag.equals(c.containerId())&&(!c.encumbrances().isEmpty()||!c.reservations().isEmpty()||!c.beneficialOwner().equals(owner))))return "个人携带物存在他人权属或未清负担";
        return null;
    }
    private String conditions(MinecraftServer s,CompoundTag t,boolean departed){
        var a=from(t);var b=to(t);UUID person=t.getUUID("Person");var n=actor(s,a,2);var houses=HouseholdData.get(s);var life=NpcLifeData.get(s);
        if(!alive(s,person))return "申请人已确认死亡";
        if(n==null)return "原居民暂未加载，保留身份和位置";
        if(!physical(s,a)||!physical(s,b))return "来源或目标正处远方交接，等待正式实体账返回";
        if(!n.isAlive()||n.isBaby()||n.isOwned()||n.isNoAi()||n.getProfession()!=NpcProfession.WORKER||!NpcBrainRuntime.isPlanned(n)||n.getCommand()!=NpcCommand.WORK||n.getTarget()!=null)return "申请人资格、命令或当前可用状态不满足";
        var r=life.record(person);
        if(r==null||r.authority()!=(departed?NpcWorkAuthority.NONE:NpcWorkAuthority.INSTITUTION)||!a.settlementId().equals(r.institutionWorkplaceSettlementId())||!departed&&!a.settlementId().equals(r.activeWorkplaceSettlementId()))return "原工作权威发生变化";
        var contract=life.contract(person);if(contract!=null&&(contract.status().ongoing()||contract.unpaidEmeralds()>0))return "现有雇佣或工资义务未结清";
        if(com.jgeted.sagadyssey.social.combat.NaturalConflictData.engaged(s,person)||com.jgeted.sagadyssey.social.politics.NaturalPolityData.paused(s,person))return "现有机构职责尚未结束";
        var whole=NaturalHouseholdMigrationData.existing(s);if(whole!=null&&whole.moves().stream().anyMatch(m->m.getUUID("Household").equals(NaturalVillageLifeData.home(a))&&!m.getString("State").equals("DONE")))return "已有整户迁移义务";
        var source=houses.forMember(person);var dest=houses.byId(NaturalVillageLifeData.home(b));
        if(source==null||!source.id().equals(NaturalVillageLifeData.home(a))||source.headCharacterId().equals(person)||!Objects.equals(source.settlementId(),a.settlementId())||dest==null||dest.members().size()>=HouseholdRecord.MAX_MEMBERS||!Objects.equals(dest.settlementId(),b.settlementId())||!Objects.equals(dest.sharedStorage(),NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY)))return "来源户籍或目标接纳容量发生变化";
        if(!alive(s,source.headCharacterId())||!alive(s,dest.headCharacterId()))return "来源或接纳户主不能确认既有约定";
        var retained=actor(s,a,0);if(retained==null||!alive(s,retained.getCharacterId())||retained.isNoAi()||retained.getProfession()!=NpcProfession.WORKER||retained.getCommand()!=NpcCommand.WORK||!a.settlementId().equals(com.jgeted.sagadyssey.social.work.NpcWorkContext.from(retained).workplaceSettlementId()))return "来源没有可保留的在岗林工";
        UUID old=NaturalVillageLifeData.person(b,2);if(CharacterRegistryData.get(s).byId(old)==null||alive(s,old))return "目标原林工未确认死亡，不能把卸载当空缺";
        if(!deathGoods.containsKey(old)||!deathGoods.get(old).getString("State").equals("DROPPED"))return "离任者随身物品去向尚待核对，不接管或继承";
        String obligation=obligations(s,person,n.getUUID());if(obligation!=null)return obligation;
        obligation=obligations(s,old,NaturalVillageLifeData.body(b,2));if(obligation!=null)return "离任者"+obligation;
        if(!n.getTransportInventory().isEmpty())return "携货尚未结清";
        boolean axe=false;for(int i=0;i<n.getPersonalInventory().getContainerSize();i++){var stack=n.getPersonalInventory().getItem(i);var rights=RightsStateData.get(s);var bag=ContainerIds.npcPersonal(person);if(stack.is(Items.IRON_AXE)&&stack.getDamageValue()<stack.getMaxDamage()&&(rights.context(bag)==null||new RightsService(rights).can(SubjectRef.character(person),AssetRight.USE,bag,i,ItemFingerprint.of(stack),s.overworld().getGameTime()).status()==RightsVerdict.Status.ALLOW))axe=true;}
        if(!axe)return "缺少本人合法可用的铁斧";
        var l=SupplyFlowService.level(s,b.dimension());var bed=bed(b);var at=NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY);
        if(!l.hasChunkAt(bed)||!l.hasChunkAt(at))return "接纳设施暂未加载";
        if(!l.getBlockState(bed).is(BlockTags.BEDS)||life.bedAssigned(bed,b.dimension()))return "接纳床位缺失或已分配";
        for(var other:life.records())if(!other.npcId().equals(person)&&alive(s,other.npcId())&&b.settlementId().equals(other.activeWorkplaceSettlementId())){
            var zone=life.workZone(other.npcId());if(zone!=null&&zone.activeMin()!=null&&zone.activeMax()!=null){
                var x=zone.activeMin();var z=zone.activeMax();var min=workMin(b);var max=workMax(b);
                if(Math.min(x.getX(),z.getX())<=Math.max(min.getX(),max.getX())&&Math.max(x.getX(),z.getX())>=Math.min(min.getX(),max.getX())
                        &&Math.min(x.getZ(),z.getZ())<=Math.max(min.getZ(),max.getZ())&&Math.max(x.getZ(),z.getZ())>=Math.min(min.getZ(),max.getZ())
                        &&Math.min(x.getY(),z.getY())<=Math.max(min.getY(),max.getY())&&Math.max(x.getY(),z.getY())>=Math.min(min.getY(),max.getY()))return "目标存在在世的重叠职责，不能把未加载当空缺";
            }
        }
        boolean logs=false;for(var p:BlockPos.betweenClosed(workMin(b),workMax(b))){if(!l.hasChunkAt(p))return "目标作业区暂未加载";if(l.getBlockState(p).is(BlockTags.LOGS))logs=true;}
        if(!logs)return "目标没有实际可作业原木";
        UUID pantry=NaturalVillageEconomyData.storeId(b,1);var context=RightsStateData.get(s).context(pantry);
        if(!(l.getBlockEntity(at) instanceof Container)||context==null||!context.defaultBeneficialOwner().equals(SubjectRef.household(dest.id())))return "粮仓缺失或权属不符";
        try{var point=SupplyFlowData.get(s).point(pantry);if(SupplyFlowService.available(s,point,SubjectRef.household(dest.id()),new ItemFingerprint("minecraft:carrot",0),s.overworld().getGameTime())<4)return "接纳家庭缺少四份可支配实际食物";}
        catch(NoSuchElementException e){return "接纳粮仓未正式登记";}
        return null;
    }
    private void state(CompoundTag t,String state,String reason){if(!state.equals(t.getString("State"))||!reason.equals(t.getString("Reason"))){t.putString("State",state);t.putString("Reason",reason);setDirty();}}
    private static void history(MinecraftServer s,CompoundTag t,String kind){WorldHistoryData.get(s).append(s.overworld().getGameTime(),kind,t.getUUID("Person"),"林工迁移",t.getString("Reason")+"；来源="+from(t).settlementId()+"；目标="+to(t).settlementId(),30);}
    private void tick(MinecraftServer s){long now=s.overworld().getGameTime();for(var t:applications.values()){
        String st=t.getString("State");if(Set.of("DONE","REJECTED","REVIEW").contains(st))continue;
        boolean departed=!st.equals("WAITING");
        if(now>=t.getLong("Deadline")){state(t,departed?"REVIEW":"REJECTED","达到固定申请与步行等待上限，保留原身份、财产与义务供核对");history(s,t,"resident_move_timeout");continue;}
        String why=conditions(s,t,departed);
        if(why!=null){if(departed&&why.equals("申请人已确认死亡"))state(t,"REVIEW",why);else state(t,departed?"WAIT_DESTINATION":"WAITING",why);continue;}
        var a=from(t);var b=to(t);var n=actor(s,a,2);
        if(!departed){
            // Commit intent first; cancellation is permitted only after all concrete obligations are clear.
            t.putLong("Departed",now);t.putLong("DeparturePosition",n.blockPosition().asLong());
            t.put("DepartureBag",NaturalVillageEconomyData.items(n.getPersonalInventory(),s.registryAccess()));
            for(int i=0;i<n.getPersonalInventory().getContainerSize();i++)if(n.getPersonalInventory().getItem(i).is(Items.IRON_AXE))t.putInt("InitialAxeDamage",n.getPersonalInventory().getItem(i).getDamageValue());
            NpcBrainRuntime.cancel(n,PlanCancellationReason.AUTHORIZED_COMMAND,"本人申请并获版本化接纳，旧义务已核对");
            NpcLifeData.get(s).suspendForMigration(n,now);state(t,"WALKING","旧岗位暂停，同一居民携个人工具步行前往空缺岗位");history(s,t,"resident_move_departed");continue;
        }
        state(t,"WALKING","沿现有导航步行；旧私仓和离任者遗产保留原主");
        var at=NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY);
        if(n.blockPosition().distSqr(at)>=49)continue;
        if(!HouseholdData.get(s).transferMember(n.getCharacterId(),NaturalVillageLifeData.home(a),NaturalVillageLifeData.home(b),now)){state(t,"REVIEW","户籍提交条件变化，保留原义务不重试财产转移");continue;}
        NpcLifeData.get(s).commitWorkerMigration(n,b.settlementId(),bed(b),workMin(b),workMax(b),now);
        n.getNavigation().stop();NpcBrainRuntime.cancel(n,PlanCancellationReason.AUTHORIZED_COMMAND,"已抵达并提交，交由原生产计划器接续");
        t.putLong("Completed",now);t.putLong("ArrivalPosition",n.blockPosition().asLong());state(t,"DONE","原人物已迁入并接替实际林地，新产出按原规则贡献家庭；劳动回执另行验证");history(s,t,"resident_move_completed");
    }}
    public static boolean tickActor(NpcBase n){var d=existing(n.getServer());var t=d==null?null:d.applications.get(n.getCharacterId());if(t==null||!moving(n.getServer(),n.getCharacterId()))return false;
        if(t.getString("State").equals("REVIEW")){n.getNavigation().stop();return true;}
        var b=to(t);var target=NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY);
        if(!n.isAlive()||n.getTarget()!=null||n.getCommand()!=NpcCommand.WORK||n.blockPosition().distSqr(target)<49){n.getNavigation().stop();return true;}
        if(n.tickCount%20==0&&!FieldworkRoutes.move(n,target))d.state(t,"WAIT_DESTINATION","道路或下一路段不可用，在原位置等待，不传送或强制加载");return true;
    }
    @SubscribeEvent public static void post(ServerTickEvent.Post e){if(e.getServer().overworld().getGameTime()%20==0){var d=existing(e.getServer());if(d!=null)d.tick(e.getServer());}}
    /** Bounded death disposition, not inheritance: actual bag goods remain real owner-marked drops. */
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGH) public static void death(net.neoforged.neoforge.event.entity.living.LivingDeathEvent e){
        if(!(e.getEntity() instanceof NpcBase n)||!(n.level() instanceof net.minecraft.server.level.ServerLevel l))return;
        var life=NaturalVillageLifeData.existing(l.getServer());if(life==null||life.villages().stream().filter(v->v.getBoolean("Ready")).map(NaturalVillageLifeData::decode).noneMatch(b->b.templateId().equals(NaturalVillageLayout.WOOD_V2.toString())&&NaturalVillageLifeData.person(b,2).equals(n.getCharacterId())))return;
        var d=get(l.getServer());if(d.deathGoods.containsKey(n.getCharacterId()))return;
        var t=new CompoundTag();t.putUUID("Person",n.getCharacterId());t.putLong("At",l.getGameTime());t.putLong("Position",n.blockPosition().asLong());t.put("Before",NaturalVillageEconomyData.items(n.getPersonalInventory(),l.registryAccess()));
        // Native equipped goods require separate reconciliation; never guess whether an equipment view is a mirror.
        boolean equipment=false;for(var slot:net.minecraft.world.entity.EquipmentSlot.values())if(!n.getItemBySlot(slot).isEmpty())equipment=true;
        if(equipment){t.putString("State","REVIEW");t.putString("Reason","存在原版装备，保留现场，不猜测是否与私人库存重复");}
        else{
            var nearby=n.getBoundingBox().inflate(3);var before=l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,nearby).stream().map(Entity->Entity.getUUID()).collect(java.util.stream.Collectors.toSet());
            net.minecraft.world.Containers.dropContents(l,n.blockPosition(),n.getPersonalInventory());n.getPersonalInventory().clearContent();
            var drops=new ListTag();for(var item:l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,nearby))if(!before.contains(item.getUUID())){item.setTarget(n.getCharacterId());var a=new CompoundTag();a.putUUID("Entity",item.getUUID());a.put("Stack",item.getItem().save(l.registryAccess()));drops.add(a);}
            t.put("Drops",drops);t.putString("State","DROPPED");t.putString("Reason","原随身物品真实掉落并标记死者；未转交迁入者，后续遵循原版掉落实体生命周期");
        }
        d.deathGoods.put(n.getCharacterId(),t);d.setDirty();
    }
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider lookup){t.putInt("Schema",1);var list=new ListTag();applications.values().forEach(x->list.add(x.copy()));t.put("Applications",list);var goods=new ListTag();deathGoods.values().forEach(x->goods.add(x.copy()));t.put("DeathGoods",goods);return t;}
    public static ResidentWorkMigrationData load(CompoundTag t,HolderLookup.Provider lookup){if(t.getInt("Schema")!=1)throw new IllegalArgumentException("Unknown resident move schema");var d=new ResidentWorkMigrationData();for(var e:t.getList("Applications",Tag.TAG_COMPOUND)){
        var a=((CompoundTag)e).copy();if(!Set.of("WAITING","WALKING","WAIT_DESTINATION","DONE","REJECTED","REVIEW").contains(a.getString("State"))||d.applications.size()>=64||d.applications.putIfAbsent(a.getUUID("Person"),a)!=null)throw new IllegalArgumentException("Conflicting resident application");
        var source=from(a);var target=to(a);if(!source.templateId().equals(NaturalVillageLayout.WOOD_V2.toString())||!target.templateId().equals(NaturalVillageLayout.WOOD_V2.toString())||!a.getUUID("Person").equals(NaturalVillageLifeData.person(source,2))||!a.getUUID("Body").equals(NaturalVillageLifeData.body(source,2))||a.getLong("Deadline")-a.getLong("Applied")!=DEADLINE)throw new IllegalArgumentException("Resident application identity or deadline changed");
    }for(var e:t.getList("DeathGoods",Tag.TAG_COMPOUND)){var a=((CompoundTag)e).copy();if(!Set.of("DROPPED","REVIEW").contains(a.getString("State"))||d.deathGoods.putIfAbsent(a.getUUID("Person"),a)!=null)throw new IllegalArgumentException("Conflicting death goods");}return d;}
}
