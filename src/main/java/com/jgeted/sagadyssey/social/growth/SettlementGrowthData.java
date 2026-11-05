package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.politics.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.social.work.NpcWorkContext;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import java.util.*;

/** New v5 decisions only. Old worlds, title rules and existing ledgers keep their original meanings. */
public final class SettlementGrowthData extends SavedData {
    public static final String NAME="sagadyssey_settlement_growth_v1";
    private static final String HERD="SagadysseyGrowthHerd";
    private static final Factory<SettlementGrowthData> FACTORY=new Factory<>(SettlementGrowthData::new,SettlementGrowthData::load);
    private final Map<String,CompoundTag> sites=new LinkedHashMap<>();
    private final Map<UUID,String> residents=new HashMap<>();
    public static SettlementGrowthData get(MinecraftServer s){return s.overworld().getDataStorage().computeIfAbsent(FACTORY,NAME);}
    public static SettlementGrowthData existing(MinecraftServer s){return s.overworld().getDataStorage().get(FACTORY,NAME);}
    public List<CompoundTag> sites(){return sites.values().stream().map(CompoundTag::copy).toList();}
    public CompoundTag site(String key){var r=sites.get(key);return r==null?null:r.copy();}
    private static NaturalVillageBuilding building(CompoundTag row){return NaturalVillageLifeData.decode(row.getCompound("Village"));}
    private CompoundTag forPerson(UUID id){return sites.get(residents.get(id));}
    private void index(CompoundTag row){var b=building(row);for(int i=0;i<3;i++)residents.put(NaturalVillageLifeData.person(b,i),b.buildingKey());}
    private void reason(CompoundTag r,String why){if(!why.equals(r.getString("Reason"))){r.putString("Reason",why);setDirty();}}
    private static ItemFingerprint item(Item i){return ItemFingerprint.of(new ItemStack(i));}
    private static boolean loaded(ServerLevel l,BlockPos p){return l.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)!=null;}
    private static Container store(MinecraftServer s,NaturalVillageBuilding b,int i){return NaturalPolityData.inventory(s,b,i);}
    private static int count(Container c,Item item){return c==null?0:SupplyFlowService.count(c,item(item));}
    boolean authority(MinecraftServer s,NaturalVillageBuilding b){
        var home=HouseholdData.get(s).byId(NaturalVillageLifeData.home(b));
        return home!=null&&PublicHousingConditions.registered(s,b)
                &&NaturalPolityData.get(s).authorized(s,b,home.headCharacterId());
    }
    boolean eligible(NpcBase n,CompoundTag r){
        if(r==null||r.getBoolean("Review")||!n.isAlive()||n.getTarget()!=null||n.getCommand()!=NpcCommand.WORK
                ||!NpcSchedulePlanner.allowsWork(n))return false;
        var b=building(r);var life=NpcLifeData.get(n.getServer()).record(n.getCharacterId());
        return life!=null&&life.authority()==NpcWorkAuthority.INSTITUTION
                &&Objects.equals(NpcWorkContext.from(n).workplaceSettlementId(),b.settlementId())
                &&!NaturalPolityData.paused(n.getServer(),n.getCharacterId())&&authority(n.getServer(),b);
    }
    /** Exact new columns and resource patch; never broaden a worker to demolish its own house. */
    public static boolean workAllowed(NpcBase n,BlockPos pos){
        var development=SettlementDevelopmentData.residentVillage(n.getServer(),n.getCharacterId());
        if(development!=null && n.getProfession()==com.jgeted.sagadyssey.npc.profession.NpcProfession.WORKER) return GrowthLayout.quarry(development,pos)||GrowthLayout.timber(development,pos)||SettlementDevelopmentLayout.timber(development,pos)||SettlementDevelopmentData.resourceAllowed(n,pos);
        var d=existing(n.getServer());var r=d==null?null:d.forPerson(n.getCharacterId());
        if(r==null)return true;
        var b=building(r);
        if(n.getCharacterId().equals(NaturalVillageLifeData.person(b,2)))
            return SettlementCoordinatorData.resourceAllowed(n,pos)||GrowthLayout.timber(b,pos)
                    ||(r.getBoolean("HouseApproved")||SettlementCoordinatorData.active(n.getServer(),b.buildingKey()))&&GrowthLayout.quarry(b,pos);
        return true;
    }
    public static boolean extraField(NpcBase n,BlockPos pos){
        if(SettlementDevelopmentData.residentVillage(n.getServer(),n.getCharacterId())!=null)return SettlementDevelopmentData.cultivation(n,pos);
        if(SettlementCoordinatorData.extraField(n,pos))return true;
        var d=existing(n.getServer());var r=d==null?null:d.forPerson(n.getCharacterId());
        if(r==null||!r.getBoolean("FieldApproved"))return false;var b=building(r);
        return n.getCharacterId().equals(NaturalVillageLifeData.person(b,0))&&GrowthLayout.extension().stream()
                .map(p->NaturalVillageLayout.at(b,p)).anyMatch(p->p.getX()==pos.getX()&&p.getZ()==pos.getZ());
    }
    private void enroll(MinecraftServer s){
        var life=NaturalVillageLifeData.existing(s);if(life==null)return;
        for(var v:life.villages()){
            var b=NaturalVillageLifeData.decode(v);if(!v.getBoolean("Ready")||!GrowthLayout.supports(b)||sites.containsKey(b.buildingKey()))continue;
            var l=SupplyFlowService.level(s,b.dimension());if(l==null||!loaded(l,NaturalVillageLayout.at(b,GrowthLayout.PEN_GATE)))continue;
            var r=new CompoundTag();r.put("Village",v.copy());r.putLong("Enrolled",l.getGameTime());r.put("Animals",new ListTag());
            r.putString("HouseStage","WAITING_DECISION");r.putString("Reason","原居民住房缺口及有限公共建设用地");
            sites.put(b.buildingKey(),r);index(r);setDirty();
            // These are declared founding animals, created once, never replacements for missing entities.
            for(int i=0;i<2;i++){
                UUID id=ContainerIds.named("growth-founder-pig:"+b.buildingKey()+":"+i);
                var p=NaturalVillageLayout.at(b,new BlockPos(4+i,1,18));
                r.putBoolean("InitializingAnimal",true);setDirty();
                var pig=EntityType.PIG.create(l);if(pig==null)throw new IllegalStateException("Pig type unavailable");
                pig.setUUID(id);pig.moveTo(p.getX()+.5,p.getY(),p.getZ()+.5,0,0);pig.setPersistenceRequired();
                pig.getPersistentData().putString(HERD,b.buildingKey());
                if(!l.addFreshEntity(pig)){r.putBoolean("Review",true);reason(r,"创始动物实体冲突；不重造");break;}
                recordAnimal(r,pig,l.getGameTime(),"FOUNDING");r.remove("InitializingAnimal");setDirty();
            }
        }
    }
    private void recordAnimal(CompoundTag r,Pig pig,long now,String cause){
        var list=r.getList("Animals",Tag.TAG_COMPOUND);
        for(var t:list)if(((CompoundTag)t).getUUID("Id").equals(pig.getUUID()))return;
        var a=new CompoundTag();a.putUUID("Id",pig.getUUID());a.putLong("Born",now);a.putString("Cause",cause);
        a.putUUID("OwnerHousehold",NaturalVillageLifeData.home(building(r)));
        if(pig.getPersistentData().hasUUID("GrowthParentA")){a.putUUID("ParentA",pig.getPersistentData().getUUID("GrowthParentA"));a.putUUID("ParentB",pig.getPersistentData().getUUID("GrowthParentB"));}
        list.add(a);r.put("Animals",list);setDirty();
    }
    private List<Pig> animals(ServerLevel l,CompoundTag r){
        var out=new ArrayList<Pig>();
        for(var t:r.getList("Animals",Tag.TAG_COMPOUND)){var a=(CompoundTag)t;
            if(!a.contains("Died")&&l.getEntity(a.getUUID("Id")) instanceof Pig p&&p.isAlive())out.add(p);}
        return out;
    }
    private int livingCount(CompoundTag r){int n=0;for(var t:r.getList("Animals",Tag.TAG_COMPOUND))if(!((CompoundTag)t).contains("Died"))n++;return n;}
    private void assess(MinecraftServer s,CompoundTag r){
        var b=building(r);var l=SupplyFlowService.level(s,b.dimension());
        if(l==null||!loaded(l,NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC))||r.getBoolean("Review"))return;
        long now=l.getGameTime();if(!authority(s,b)){reason(r,"缺少本聚落现任公职或合法公共用地");return;}
        maintainEntrance(s,l,b,r,now);
        if (SettlementCoordinatorData.active(s, b.buildingKey())) return;
        int food=NaturalPolityData.availableFood(s,b);
        if(!r.getBoolean("FieldApproved")&&food>=20&&food<GrowthLayout.FOOD_RESERVE){
            r.putBoolean("FieldApproved",true);r.putLong("FieldApprovedAt",now);r.putInt("FoodAtFieldApproval",food);
            r.putUUID("FieldApprovedBy",PoliticalStateData.get(s).titleHolder(b.settlementId()));
            reason(r,"生活储备尚不足64，授权原农民开垦圈定的12块泥土，兼顾有限养殖饲料");setDirty();
        }
        var worker=NpcLifeData.get(s).record(NaturalVillageLifeData.person(b,2));
        if(!r.getBoolean("HouseApproved")&&worker!=null&&worker.bedPosition()==null&&food>=20){
            if(!r.contains("SurplusSince")){r.putLong("SurplusSince",now);setDirty();}
            if(now-r.getLong("SurplusSince")>=48000){
                r.putBoolean("HouseApproved",true);r.putLong("HouseApprovedAt",now);
                r.putUUID("HouseApprovedBy",PoliticalStateData.get(s).titleHolder(b.settlementId()));r.putString("HouseStage","MATERIALS");
                var flows=SupplyFlowData.get(s);UUID dest=NaturalVillageEconomyData.storeId(b,1);var owner=SubjectRef.household(NaturalVillageLifeData.home(b));
                flows.policy(new SupplyFlowData.Policy("growth-stone:"+b.buildingKey(),owner,dest,item(Items.COBBLESTONE),55,"household"));
                flows.policy(new SupplyFlowData.Policy("growth-timber:"+b.buildingKey(),owner,dest,item(Items.OAK_LOG),3,"household"));setDirty();
            }
        }else if(!r.getBoolean("HouseApproved")&&r.contains("SurplusSince")){r.remove("SurplusSince");setDirty();}
    }
    /** The one approved construction entrance, then its registered occupant's door. */
    void maintainEntrance(MinecraftServer s,ServerLevel l,NaturalVillageBuilding b,CompoundTag r,long now){
        var safety=SettlementSafetyData.existing(s);
        if(safety!=null&&safety.site(b.buildingKey())!=null)return;
        if(r.getInt("Placed")<56)return;
        var door=NaturalVillageLayout.at(b,new BlockPos(16,1,12));if(!loaded(l,door))return;
        var state=l.getBlockState(door);if(!state.is(Blocks.OAK_DOOR))return;
        NpcBase n=null;
        for(int i=0;i<3;i++)if(l.getEntity(NaturalVillageLifeData.body(b,i)) instanceof NpcBase candidate && candidate.isAlive() && candidate.getTarget()==null
                && candidate.distanceToSqr(door.getX()+.5,door.getY(),door.getZ()+.5)<=9D){n=candidate;break;}
        if(n==null)return;
        var home=HouseholdData.get(s).byId(NaturalVillageLifeData.home(b));var life=NpcLifeData.get(s).record(n.getCharacterId());
        if(home==null||!home.contains(n.getCharacterId())||life==null)return;
        boolean authorized=r.getString("HouseStage").equals("BUILD")&&eligible(n,r)
                ||r.getString("HouseStage").equals("DONE")&&NaturalVillageLayout.at(b,GrowthLayout.BED_HEAD).equals(life.bedPosition());
        if(!authorized)return;
        boolean close=n.distanceToSqr(door.getX()+.5,door.getY(),door.getZ()+.5)<=9D;
        boolean open=state.getValue(DoorBlock.OPEN);
        if(close&&!open){
            ((DoorBlock)state.getBlock()).setOpen(n,l,state,door,true);
            r.putLong("DoorOpenedAt",now);r.putInt("DoorOpens",r.getInt("DoorOpens")+1);setDirty();
        }else if(!close&&open&&r.contains("DoorOpenedAt")&&now-r.getLong("DoorOpenedAt")>=40
                &&l.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new net.minecraft.world.phys.AABB(door).expandTowards(0,1,0).inflate(.3)).isEmpty()){
            ((DoorBlock)state.getBlock()).setOpen(n,l,state,door,false);r.putLong("DoorClosedAt",now);setDirty();
        }
    }
    public boolean canWork(NpcBase n){
        var r=forPerson(n.getCharacterId());if(!eligible(n,r))return false;var b=building(r);
        if(n.level().getGameTime()<r.getLong("RetryWorkAt"))return false;
        if(!n.getCharacterId().equals(NaturalVillageLifeData.person(b,2)))return false;
        if(r.getBoolean("Feeding"))return true;
        if (SettlementCoordinatorData.active(n.getServer(), b.buildingKey())) return canFeed(n,r);
        String stage=r.getString("HouseStage");
        if(!Set.of("WAITING_DECISION","DONE","MATERIALS").contains(stage))return true;
        if(stage.equals("MATERIALS")&&count(store(n.getServer(),b,1),Items.OAK_LOG)>=3
                &&count(store(n.getServer(),b,1),Items.COBBLESTONE)>=55)return true;
        return canFeed(n,r);
    }
    private boolean canFeed(NpcBase n,CompoundTag r){
        if(n.level().getGameTime()<r.getLong("TransferRetryAt"))return false;
        if(livingCount(r)>=GrowthLayout.HERD_LIMIT||n.level().getGameTime()<r.getLong("NextFeeding"))return false;
        var b=building(r);if(NaturalPolityData.availableFood(n.getServer(),b)<32)return false;
        var pigs=animals((ServerLevel)n.level(),r);
        return pigs.size()==livingCount(r)&&pigs.stream().filter(p->p.getAge()==0&&!p.isInLove()).count()>=2
                &&count(store(n.getServer(),b,1),Items.CARROT)>=10;
    }
    public String work(NpcBase n){
        var r=forPerson(n.getCharacterId());if(!eligible(n,r))return "YIELD";
        long now=n.level().getGameTime();if(r.getLong("LastWork")==now)return "同刻重复调度不重复劳动";
        r.putLong("LastWork",now);var b=building(r);
        if(r.getBoolean("Feeding"))return feed(n,r);
        if (SettlementCoordinatorData.active(n.getServer(), b.buildingKey())) return canFeed(n,r)?beginFeed(n,r):"YIELD";
        String stage=r.getString("HouseStage");
        if(stage.equals("MATERIALS")&&count(store(n.getServer(),b,1),Items.OAK_LOG)>=3&&count(store(n.getServer(),b,1),Items.COBBLESTONE)>=55){
            if(!near(n,NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY)))return "前往材料粮仓";
            var legs=List.of(new TransferLeg(NaturalVillageEconomyData.storeId(b,1),NaturalVillageEconomyData.storeId(b,3),item(Items.OAK_LOG),3),
                    new TransferLeg(NaturalVillageEconomyData.storeId(b,1),NaturalVillageEconomyData.storeId(b,3),item(Items.COBBLESTONE),55));
            UUID txn=ContainerIds.named("growth-donation:"+b.buildingKey());
            if(!transfer(n,r,txn,SubjectRef.household(NaturalVillageLifeData.home(b)),TransferIntent.GIFT,legs,
                    Map.of(legs.getFirst().sourceContainerId(),store(n.getServer(),b,1),legs.getFirst().destinationContainerId(),store(n.getServer(),b,3))))return "YIELD";
            var flows=SupplyFlowData.get(n.getServer());flows.removePolicy("growth-stone:"+b.buildingKey());flows.removePolicy("growth-timber:"+b.buildingKey());
            r.putString("HouseStage","PLANKS");r.putLong("DonatedAt",now);r.putUUID("Donation",r.getUUID("LastTransfer"));setDirty();return "合法材料已捐入本楼公共仓，保留原运输回执";
        }
        if(Set.of("PLANKS","BED","DOOR").contains(stage))return craft(n,r,stage);
        if(stage.equals("BUILD"))return build(n,r);
        if(canFeed(n,r))return beginFeed(n,r);
        return "YIELD";
    }
    boolean near(NpcBase n,BlockPos p){
        if(FieldworkRoutes.closeEnough(n,p)){n.getNavigation().stop();return true;}
        if(SettlementDevelopmentData.route(n,p))return false;
        var row=forPerson(n.getCharacterId());
        if(row!=null){
            var b=building(row);
            boolean leaving=p.equals(NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC))
                    ||p.equals(NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY));
            boolean inside=false;
            for(int x=15;x<=17;x++)for(int z=13;z<=15;z++)if(NaturalVillageLayout.at(b,new BlockPos(x,1,z)).equals(n.blockPosition()))inside=true;
            if(leaving&&inside){
                var inner=NaturalVillageLayout.at(b,new BlockPos(16,1,13));var outer=NaturalVillageLayout.at(b,new BlockPos(16,1,11));
                double dx=outer.getX()-inner.getX(),dz=outer.getZ()-inner.getZ();
                double across=Math.abs((n.getX()-inner.getX()-.5)*dz-(n.getZ()-inner.getZ()-.5)*dx)/2D;
                walkTo(n,across<.2?outer:inner);return false;
            }
        }
        var route=FieldworkRoutes.plan(n,p,false);
        if(route!=null){
            walkTo(n,route.stand());
        }
        return false;
    }
    static void walkTo(NpcBase n,BlockPos stand){
            // A native path may finish before the stand's centre. At a wall corner
            // that tolerance can leave the real eye behind the wall although the
            // planned stand has sight. Finish this last short walk physically.
            var delta=net.minecraft.world.phys.Vec3.atBottomCenterOf(stand).subtract(n.position());
            boolean clear=n.onGround()&&Math.abs(delta.y)<.1&&delta.lengthSqr()<9D;
            int segments=Math.max(8,(int)Math.ceil(delta.length()*8));
            for(int i=1;clear&&i<=segments;i++){
                var next=delta.scale(i/(double)segments);
                clear=n.level().noCollision(n,n.getBoundingBox().move(next))
                        &&FieldworkRoutes.walkable(n,BlockPos.containing(n.position().add(next)));
            }
            if(clear){
                n.getNavigation().stop();
                n.getMoveControl().setWantedPosition(stand.getX()+.5,stand.getY(),stand.getZ()+.5,1D);
            }else FieldworkRoutes.move(n,stand);
    }
    boolean transfer(NpcBase n,CompoundTag r,UUID txn,SubjectRef actor,TransferIntent intent,List<TransferLeg> legs,Map<UUID,Container> inventories){
        if(r.getBoolean("Review"))return false;
        String key=txn.toString();var attempts=r.getCompound("Transfers");var attempt=attempts.getCompound(key);
        if(attempt.getBoolean("Success")){
            boolean originalPerson=(!r.contains("Person")||r.hasUUID("Person")&&r.getUUID("Person").equals(n.getCharacterId()))
                    &&(!r.contains("Body")||r.hasUUID("Body")&&r.getUUID("Body").equals(n.getUUID()));
            TransferLedgerData ledger=null;
            try{ledger=n.getServer().overworld().getDataStorage().get(TransferLedgerData.FACTORY,TransferLedgerData.DATA_NAME);}
            catch(RuntimeException unavailable){/* Missing/unreadable original evidence is not a new writable ledger. */}
            if(!originalPerson||!cachedRequestMatches(attempt,txn,actor,intent,legs,ledger)){
                r.putBoolean("Review",true);reason(r,"成功转移请求身份不一致或原回执证据不足；保留原记录待核对，不重复扣物");setDirty();return false;
            }
            if(n.level().getGameTime()<r.getLong("TransferRetryAt"))return false;
            r.putUUID("LastTransfer",attempt.getUUID("Id"));return true;
        }
        boolean foodCapacityRetry=false;
        if(r.getString("Kind").equals("FOOD")&&!r.getBoolean("Cargo")&&attempt.getInt("Attempt")>0){
            TransferLedgerData ledger=null;
            try{ledger=n.getServer().overworld().getDataStorage().get(TransferLedgerData.FACTORY,TransferLedgerData.DATA_NAME);}
            catch(RuntimeException unavailable){/* Original result must remain authoritative. */}
            int evidence=r.getBoolean("Submitting")?-1:foodCapacityEvidence(attempt,txn,actor,intent,legs,ledger);
            if(evidence<0){reviewFood(r,"原FOOD失败回执或请求身份不足，保留原物资与义务待核对");return false;}
            if(evidence>0){if(!foodCapacityReady(n,r,txn,actor,legs,inventories))return false;foodCapacityRetry=true;}
        }
        if(n.level().getGameTime()<r.getLong("TransferRetryAt")&&!foodCapacityRetry)return false;
        int number=attempt.getInt("Attempt");
        if(number>=64){r.putBoolean("Review",true);reason(r,"同一材料转移持续失败，保留物资及失败回执待核对");return false;}
        UUID actual=ContainerIds.named("growth-transfer-attempt:"+txn+":"+number);
        var rights=RightsStateData.get(n.getServer());var endpoints=new HashMap<UUID,TransferEndpoint>();var revisions=new ArrayList<ExpectedRevision>();
        for(var entry:inventories.entrySet()){
            if(entry.getValue()==null||rights.context(entry.getKey())==null)return false;
            endpoints.put(entry.getKey(),new TransferEndpoint(entry.getKey(),entry.getValue()));revisions.add(new ExpectedRevision(entry.getKey(),rights.context(entry.getKey()).revision()));
        }
        r.putBoolean("Submitting",true);setDirty();
        var result=new TransferService(rights,TransferLedgerData.get(n.getServer())).submit(
                new TransferRequest(actual,actor,intent,legs,revisions,"v5合法建设或饲料领取",n.level().getGameTime()),endpoints,n.level().getGameTime());
        attempt.putInt("Attempt",number+1);attempt.putUUID("Id",actual);attempt.putBoolean("Success",result.successful());
        attempt.putString("Reason",result.reason());attempts.put(key,attempt);r.put("Transfers",attempts);r.putUUID("LastTransfer",actual);
        r.remove("Submitting");setDirty();if(!result.successful()){reason(r,result.reason());r.putLong("TransferRetryAt",n.level().getGameTime()+1200);}else{if(foodCapacityRetry)r.remove("TransferRetryAt");reason(r,"正式转移已完成，继续原工作");}return result.successful();
    }
    private void reviewFood(CompoundTag r,String why){r.putBoolean("Review",true);reason(r,why);setDirty();}
    /** -1 unresolved, 0 another failure (unchanged backoff), 1 proven pre-commit capacity rejection. */
    static int foodCapacityEvidence(CompoundTag attempt,UUID txn,SubjectRef actor,TransferIntent intent,List<TransferLeg> legs,TransferLedgerData ledger){
        int number=attempt.getInt("Attempt");
        if(ledger==null||attempt.getBoolean("Success")||number<1||number>64||!attempt.hasUUID("Id"))return -1;
        UUID actual=attempt.getUUID("Id");if(!actual.equals(ContainerIds.named("growth-transfer-attempt:"+txn+":"+(number-1))))return -1;
        var receipt=ledger.receipt(actual);if(receipt==null||receipt.successful())return -1;
        if(receipt.status()!=TransferReceipt.Status.CAPACITY_EXCEEDED)return 0;
        var old=ledger.requestIdentity(actual);
        if(!receipt.lawful()||!receipt.finalRevisions().isEmpty()||old==null||old.getInt("Version")!=1||intent!=TransferIntent.GIFT||legs.size()!=1||!legs.getFirst().fingerprint().equals(item(Items.CARROT)))return -1;
        try{
            var requested=new TransferRequest(actual,actor,intent,legs,List.of(),"v5合法建设或饲料领取",0).identity();
            old.remove("ExpectedRevisions");requested.remove("ExpectedRevisions");var originalLegs=old.getList("Legs",Tag.TAG_COMPOUND);
            if(originalLegs.size()!=1||originalLegs.getCompound(0).getInt("Quantity")<1)return -1;
            // A definite no-effect failure may be re-evaluated against today's real deficit.
            // All stable request fields still match; both actual attempts remain in the ledger.
            requested.getList("Legs",Tag.TAG_COMPOUND).getCompound(0).putInt("Quantity",originalLegs.getCompound(0).getInt("Quantity"));
            return old.equals(requested)?1:-1;
        }catch(IllegalArgumentException invalid){return -1;}
    }
    private boolean foodCapacityReady(NpcBase n,CompoundTag r,UUID txn,SubjectRef actor,List<TransferLeg> legs,Map<UUID,Container> inventories){
        if(r.getString("Id").isEmpty()||!txn.equals(ContainerIds.named(r.getString("Id")+":pick:"+r.getInt("Cycle")))||!r.hasUUID("Person")||!r.getUUID("Person").equals(n.getCharacterId())||!r.hasUUID("Body")||!r.getUUID("Body").equals(n.getUUID())){reviewFood(r,"原FOOD子操作或责任人物不一致，不替换原任务");return false;}
        var s=n.getServer();var data=SettlementDevelopmentData.existing(s);if(data==null){reviewFood(r,"原成长义务不存在，保留失败回执");return false;}
        var b=NaturalVillageLifeData.decode(r.getCompound("Village"));var row=data.site(b.buildingKey());
        var project=row==null?null:row.getList("Projects",Tag.TAG_COMPOUND).stream().map(x->(CompoundTag)x).filter(p->(p.getString("Id")+":FOOD").equals(r.getString("Id"))).findFirst().orElse(null);
        var admission=project==null?null:data.application(project.getUUID("Household"));
        if(project==null||admission==null||!actor.equals(SubjectRef.household(admission.getUUID("Household")))){reviewFood(r,"原FOOD接纳主体或请求人不一致，保留原义务");return false;}
        if(row.getBoolean("Review")||!project.getString("State").equals("READY")||admission.contains("Departed"))return false;
        var home=HouseholdData.get(s).forMember(n.getCharacterId());var life=NpcLifeData.get(s).record(n.getCharacterId());
        if(!n.isAlive()||n.isNoAi()||n.isSleeping()||n.getTarget()!=null||n.getCommand()!=NpcCommand.WORK||home==null||life==null||life.authority()!=NpcWorkAuthority.INSTITUTION||!b.settlementId().equals(life.activeWorkplaceSettlementId())||!b.settlementId().equals(home.settlementId())||!SettlementDevelopmentData.capacity(s,home).stableFloor())return false;
        var rights=RightsStateData.get(s);var approver=project.getUUID("ApprovedBy");
        if(!approver.equals(PoliticalStateData.get(s).titleHolder(b.settlementId()))||new RightsService(rights).can(SubjectRef.character(approver),AssetRight.MANAGE,NaturalVillageEconomyData.storeId(b,3),-1,item(Items.WHITE_WOOL),n.level().getGameTime()).status()!=RightsVerdict.Status.ALLOW)return false;
        var leg=legs.getFirst();UUID personal=ContainerIds.npcPersonal(n.getCharacterId());var from=NaturalVillageEconomyData.storeId(b,0);
        if(!leg.sourceContainerId().equals(from)||!leg.destinationContainerId().equals(personal)||inventories.get(personal)!=n.getPersonalInventory()){reviewFood(r,"原FOOD来源或个人袋不一致，不重新扣物");return false;}
        var point=SupplyFlowData.get(s).point(from);var destination=levelForFood(s,b,project.getInt("Lot"));
        if(point==null||destination==null||inventories.get(from)!=SupplyFlowService.container(s,point))return false;
        int deficit=(int)Math.ceil(admission.getList("People",Tag.TAG_COMPOUND).size()*SettlementPolicy.DAILY_MEALS_PER_PERSON*2)-count(destination,Items.CARROT);
        int available=SupplyFlowService.available(s,point,actor,item(Items.CARROT),n.level().getGameTime());
        if(deficit<=0||available<=0)return false;
        if(leg.quantity()!=Math.min(deficit,available)){reviewFood(r,"重评数量与实际合法缺口不一致，不重发原义务");return false;}
        int space=foodGiftSpace(rights,personal,n.getPersonalInventory());
        if(space<0){reviewFood(r,"个人袋权属记录与实物不一致，保留容量失败待核对");return false;}
        if(space<leg.quantity()){reason(r,"原个人袋容量仍不足；保留原失败，不重复提交");return false;}
        return true;
    }
    private static Container levelForFood(MinecraftServer s,NaturalVillageBuilding b,int lot){var l=SupplyFlowService.level(s,b.dimension());var at=SettlementDevelopmentLayout.pantry(b,lot);return l!=null&&loaded(l,at)&&l.getBlockEntity(at) instanceof Container c?c:null;}
    /** Exact single-carrot GIFT subset of TransferService.insert, including ClaimMeta provenance. */
    static int foodGiftSpace(RightsStateData rights,UUID destination,Container bag){
        var context=rights.context(destination);if(context==null||bag==null)return -1;int space=0;var moving=new ItemStack(Items.CARROT);
        for(int slot=0;slot<bag.getContainerSize();slot++){
            var stack=bag.getItem(slot);if(stack.isEmpty()){space+=moving.getMaxStackSize();continue;}
            var claim=rights.claimAt(destination,slot,ItemFingerprint.of(stack));if(claim!=null&&claim.quantity()!=stack.getCount())return -1;
            if(!ItemStack.isSameItemSameComponents(stack,moving))continue;
            if(claim==null||claim.beneficialOwner().equals(context.defaultBeneficialOwner())&&claim.custodian().equals(context.custodian())&&claim.encumbrances().isEmpty()&&claim.reservations().isEmpty()&&claim.provenance().equals("container_default"))space+=Math.max(0,stack.getMaxStackSize()-stack.getCount());
        }return space;
    }
    /** A completed attempt confirms its original intent, not the current physical inventory snapshot. */
    static boolean cachedRequestMatches(CompoundTag attempt,UUID txn,SubjectRef actor,TransferIntent intent,List<TransferLeg> legs,TransferLedgerData ledger){
        if(ledger==null)return false;
        int number=attempt.getInt("Attempt");
        if(!attempt.getBoolean("Success")||!attempt.hasUUID("Id")||number<1||number>64)return false;
        UUID actual=attempt.getUUID("Id");
        if(!actual.equals(ContainerIds.named("growth-transfer-attempt:"+txn+":"+(number-1))))return false;
        var receipt=ledger.receipt(actual);var original=ledger.requestIdentity(actual);
        if(receipt==null||!receipt.successful()||!receipt.lawful()||original==null||original.getInt("Version")!=1)return false;
        try{
            var requested=new TransferRequest(actual,actor,intent,legs,List.of(),"v5合法建设或饲料领取",0).identity();
            // The original versions are pre-commit conditions. Requiring them to match
            // today's inventory would reject a legitimate replay after its own commit.
            original.remove("ExpectedRevisions");requested.remove("ExpectedRevisions");
            return original.equals(requested);
        }catch(IllegalArgumentException e){return false;}
    }
    String craft(NpcBase n,CompoundTag r,String stage){
        var b=building(r);if(!near(n,NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC)))return "前往公共工作地点";
        var s=n.getServer();long now=n.level().getGameTime();var inventory=store(s,b,3);if(inventory==null)return "YIELD";
        UUID id=NaturalVillageEconomyData.storeId(b,3),batch=ContainerIds.named("growth-craft:"+projectKey(r,b)+":"+stage);
        var owner=SubjectRef.institution(b.settlementId());
        List<ProductionMaterial> inputs=switch(stage) {
            case "PLANKS" -> List.of(new ProductionMaterial(item(Items.OAK_LOG),3));
            case "BED" -> List.of(new ProductionMaterial(item(Items.OAK_PLANKS),3),new ProductionMaterial(item(Items.WHITE_WOOL),3));
            case "DOOR" -> List.of(new ProductionMaterial(item(Items.OAK_PLANKS),6));
            case "STICKS" -> List.of(new ProductionMaterial(item(Items.OAK_PLANKS),2));
            case "TORCH", "CHARCOAL_TORCH" -> List.of(new ProductionMaterial(item(stage.equals("TORCH")?Items.COAL:Items.CHARCOAL),1),new ProductionMaterial(item(Items.STICK),1));
            default -> throw new IllegalArgumentException("Unsupported bounded recipe: "+stage);
        };
        var output=switch(stage) {
            case "PLANKS" -> new ProductionMaterial(item(Items.OAK_PLANKS),12);
            case "BED" -> new ProductionMaterial(item(Items.WHITE_BED),1);
            case "DOOR" -> new ProductionMaterial(item(Items.OAK_DOOR),3);
            case "STICKS" -> new ProductionMaterial(item(Items.STICK),4);
            default -> new ProductionMaterial(item(Items.TORCH),4);
        };
        int required=stage.equals("BED")?400:200;
        try{
            ProductionService.begin(s,batch,n.getCharacterId(),owner,id,inventory,"sagadyssey:growth_"+stage.toLowerCase(),inputs,output,required,now);
            int work=Math.min(required,r.getInt("Work")+1);r.putInt("Work",work);r.putInt("Labor",r.getInt("Labor")+1);setDirty();
            if(work<required)return "实际制作住宅部件";
            if(!ProductionService.canAcceptOutput(s,id,inventory,owner,output,now)){reason(r,"成品仓满，保留原批次");return "YIELD";}
            r.putBoolean("Submitting",true);ProductionService.complete(s,batch,inventory,work,now);r.remove("Submitting");
            r.putString("HouseStage",stage.equals("PLANKS")?"BED":stage.equals("BED")?"DOOR":stage.equals("DOOR")?"BUILD":"DONE");r.putInt("Work",0);setDirty();return "真实部件已完成入仓";
        }catch(RuntimeException ex){
            if(r.getBoolean("Submitting")){r.putBoolean("Review",true);reason(r,"制作结算中断，保留原料及批次待核对："+ex.getMessage());}
            else reason(r,"材料或工作条件不足："+ex.getMessage());return "YIELD";
        }
    }
    String build(NpcBase n,CompoundTag r){
        var b=building(r);var s=n.getServer();var l=(ServerLevel)n.level();long now=l.getGameTime();
        var blueprint=GrowthLayout.house(b,r.getString("WallMaterial").equals("WOOD"));int index=r.getInt("Placed");
        if(index==blueprint.size()){
            for(var piece:blueprint)for(int i=0;i<piece.positions().size();i++)if(!l.getBlockState(piece.positions().get(i)).is(piece.states().get(i).getBlock())){reason(r,"已施工建筑被改变，不能登记为完工");return "YIELD";}
            var head=NaturalVillageLayout.at(b,GrowthLayout.BED_HEAD);
            if(!NpcLifeData.get(s).assignBed(NaturalVillageLifeData.person(b,2),b.dimension(),head,"原聚落合法新住宅",now)){reason(r,"床位归属交接被拒绝");return "YIELD";}
            SettlementRegistryData.get(s).registerConstructedBuilding(s,b.dimension(),NaturalVillageLayout.at(b,new BlockPos(14,1,12)),"sagadyssey:growth_cottage_v1","house","small",b.settlementId(),now);
            r.putString("HouseStage","DONE");r.putLong("HouseCompleted",now);reason(r,"原工人实际采料、施工的新住宅完成并分配原居民");setDirty();return "YIELD";
        }
        return place(n,r,blueprint.get(index),index);
    }
    static String projectKey(CompoundTag r,NaturalVillageBuilding b){return r.contains("ProjectKey")?r.getString("ProjectKey"):b.buildingKey();}
    String place(NpcBase n,CompoundTag r,GrowthLayout.Placement piece,int index){
        var b=building(r);var s=n.getServer();var l=(ServerLevel)n.level();long now=l.getGameTime();
        UUID personal=ContainerIds.npcPersonal(n.getCharacterId());var rights=RightsStateData.get(s);
        var actor=SubjectRef.character(n.getCharacterId());rights.ensureContext(personal,"npc:"+n.getCharacterId()+":personal",actor,actor,AccessPolicy.PRIVATE);
        if(!r.getBoolean("MaterialCarried")){
            if(!near(n,NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC)))return "领取原公共施工材料";
            UUID source=NaturalVillageEconomyData.storeId(b,3),txn=ContainerIds.named("growth-piece:"+projectKey(r,b)+":"+index);
            int quantity=1;
            if(r.getBoolean("Development")&&piece.material()==Items.COBBLESTONE)
                quantity=Math.min(16,Math.min(SettlementDevelopmentLayout.stoneCost(b)-index,SettlementCoordinatorData.available(s,b,3,Items.COBBLESTONE)));
            if(quantity<=0){reason(r,"公共来源没有未承诺的合法材料，等待补给");return "YIELD";}
            if(!transfer(n,r,txn,SubjectRef.institution(b.settlementId()),TransferIntent.ISSUE,List.of(new TransferLeg(source,personal,item(piece.material()),quantity)),Map.of(source,store(s,b,3),personal,n.getPersonalInventory())))return "YIELD";
            if(r.getBoolean("Development")){
                r.putInt("MaterialsHeld",quantity);r.putUUID("ReturnContainer",source);
                r.putLong("ReturnPosition",NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC).asLong());
                r.put("ReturnOwner",SubjectRef.institution(b.settlementId()).save());
                r.putString("CarriedItem",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(piece.material()).toString());
            }
            r.putBoolean("MaterialCarried",true);r.putUUID("MaterialTransfer",r.getUUID("LastTransfer"));r.putInt("Work",0);setDirty();
        }
        for(var p:piece.positions())if(!loaded(l,p)||!l.getBlockState(p).isAir()){reason(r,"施工空间不可用，保留随身原材料和责任");return "YIELD";}
        if(!near(n,piece.positions().getFirst())){
            if(FieldworkRoutes.plan(n,piece.positions().getFirst(),false)==null){
                if(r.getBoolean("Development")){
                    SettlementDevelopmentData.constructionApproach(n,r,piece.positions().getFirst());
                    return "经新工程的实际可达站位施工，保留原携料";
                }
                // Enclosing walls can require a route through the doorway before
                // the short work-position search can reach the inner roof/bed.
                var inside=NaturalVillageLayout.at(b,new BlockPos(16,1,13));
                var path=n.getNavigation().createPath(inside,0);
                walkTo(n,path!=null&&path.canReach()?inside:NaturalVillageLayout.at(b,new BlockPos(16,1,11)));
            }
            return "携带真实材料经门口前往施工点";
        }
        if(piece.positions().stream().anyMatch(p->!l.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new net.minecraft.world.phys.AABB(p)).isEmpty())){
            for(var candidate:BlockPos.betweenClosed(n.blockPosition().offset(-2,0,-2),n.blockPosition().offset(2,0,2))){
                if(!FieldworkRoutes.walkable(n,candidate)||piece.positions().stream().anyMatch(p->p.getX()==candidate.getX()&&p.getZ()==candidate.getZ()))continue;
                var path=n.getNavigation().createPath(candidate,0);
                if(path!=null&&path.canReach()&&FieldworkRoutes.move(n,candidate))break;
            }return "施工格有人，等待安全站位";
        }
        r.putInt("Work",r.getInt("Work")+1);r.putInt("Labor",r.getInt("Labor")+1);setDirty();
        if(r.getInt("Work")<GrowthLayout.BUILD_LABOR)return "原工人实际施工";
        var before=new ItemStack[n.getPersonalInventory().getContainerSize()];
        for(int i=0;i<before.length;i++)before[i]=n.getPersonalInventory().getItem(i).copy();
        var ownership=rights.snapshotFor(Set.of(personal));
        r.putBoolean("Submitting",true);setDirty();
        try{
            var consumed=new ConsumptionService(rights).consume(personal,n.getPersonalInventory(),SubjectRef.institution(b.settlementId()),item(piece.material()),1,"growth-place:"+projectKey(r,b)+":"+index,now);
            if(!consumed.successful())throw new IllegalStateException(consumed.reason());
            for(int i=0;i<piece.positions().size();i++)if(!l.setBlock(piece.positions().get(i),piece.states().get(i),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE))throw new IllegalStateException("方块放置失败");
            r.putInt("Placed",index+1);r.putInt("Work",0);
            int held=r.getBoolean("Development")?Math.max(1,r.getInt("MaterialsHeld"))-1:0;
            if(held>0)r.putInt("MaterialsHeld",held);
            else{r.remove("MaterialsHeld");r.remove("MaterialCarried");r.remove("MaterialTransfer");}
            r.remove("Submitting");r.putLong("LastPlaced",now);setDirty();return "真实材料已成为建筑";
        }catch(RuntimeException ex){
            for(int i=0;i<before.length;i++)n.getPersonalInventory().setItem(i,before[i]);
            rights.restore(ownership);for(var p:piece.positions())l.setBlock(p,Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            r.remove("Submitting");r.putBoolean("Review",true);reason(r,"施工事务已回退，需核对："+ex.getMessage());setDirty();return "YIELD";
        }
    }
    private String beginFeed(NpcBase n,CompoundTag r){
        var b=building(r);if(!near(n,NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY)))return "前往领取有限饲料";
        var pigs=animals((ServerLevel)n.level(),r).stream().filter(p->p.getAge()==0&&!p.isInLove()).sorted(Comparator.comparing(Pig::getUUID)).limit(2).toList();
        if(pigs.size()!=2)return "YIELD";
        var rights=RightsStateData.get(n.getServer());UUID personal=ContainerIds.npcPersonal(n.getCharacterId()),source=NaturalVillageEconomyData.storeId(b,1);
        var actor=SubjectRef.character(n.getCharacterId());rights.ensureContext(personal,"npc:"+n.getCharacterId()+":personal",actor,actor,AccessPolicy.PRIVATE);
        int cycle=r.getInt("FeedCycle");UUID txn=ContainerIds.named("growth-feed:"+b.buildingKey()+":"+cycle);
        // The standing herd budget is a household decision by its existing head, checked by authority().
        // Membership alone does not give this worker TRANSFER rights over household food.
        if(!transfer(n,r,txn,SubjectRef.household(NaturalVillageLifeData.home(b)),TransferIntent.GIFT,List.of(new TransferLeg(source,personal,item(Items.CARROT),2)),Map.of(source,store(n.getServer(),b,1),personal,n.getPersonalInventory())))return "YIELD";
        r.putBoolean("Feeding",true);r.putUUID("FeedA",pigs.get(0).getUUID());r.putUUID("FeedB",pigs.get(1).getUUID());r.putInt("FeedIndex",0);r.putLong("FeedStarted",n.level().getGameTime());r.putInt("FeedCycle",cycle+1);r.putUUID("FeedTransfer",r.getUUID("LastTransfer"));setDirty();return "两份合法饲料已实际领取";
    }
    private String feed(NpcBase n,CompoundTag r){
        var b=building(r);var l=(ServerLevel)n.level();long now=l.getGameTime();int i=r.getInt("FeedIndex");
        if(i>=2||now-r.getLong("FeedStarted")>1200){r.remove("Feeding");r.putLong("NextFeeding",now+6000);setDirty();return "YIELD";}
        var entity=l.getEntity(r.getUUID(i==0?"FeedA":"FeedB"));
        if(!(entity instanceof Pig pig)||!pig.isAlive()||pig.getAge()!=0){reason(r,"原饲养对象未加载或不再适合繁殖，保留已领饲料");return "YIELD";}
        if(!near(n,pig.blockPosition()))return "携带饲料接近原饲养对象";
        if(pig.isInLove()){reason(r,"对象已有繁殖状态，不重复消耗");return "YIELD";}
        UUID personal=ContainerIds.npcPersonal(n.getCharacterId());r.putBoolean("Submitting",true);setDirty();
        var result=new ConsumptionService(RightsStateData.get(n.getServer())).consume(personal,n.getPersonalInventory(),SubjectRef.character(n.getCharacterId()),item(Items.CARROT),1,"growth-feed-consume:"+r.getUUID("FeedTransfer")+":"+i,now);
        if(result.successful()){
            pig.setInLove(null);r.putInt("FeedIndex",i+1);r.putInt("ConsumedFeed",r.getInt("ConsumedFeed")+1);r.putLong("LastFed",now);
        }else reason(r,"原饲料当前不可用："+result.reason());
        r.remove("Submitting");setDirty();return result.successful()?"已消耗真实饲料，等待动物正常繁殖":"YIELD";
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){
        var s=e.getServer();if(s.overworld().getGameTime()%100!=0||NaturalVillageLifeData.existing(s)==null)return;
        var d=get(s);d.enroll(s);for(var r:d.sites.values())d.assess(s,r);
    }
    public void defer(NpcBase n){var r=forPerson(n.getCharacterId());if(r!=null){r.putLong("RetryWorkAt",n.level().getGameTime()+200);setDirty();}}
    @SubscribeEvent public static void birth(net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent e){
        if(!(e.getParentA().level() instanceof ServerLevel l))return;
        String key=e.getParentA().getPersistentData().getString(HERD);var d=existing(l.getServer());
        var r=d==null?null:d.sites.get(key);if(r==null)return;
        if(!key.equals(e.getParentB().getPersistentData().getString(HERD))||d.livingCount(r)>=GrowthLayout.HERD_LIMIT){e.setCanceled(true);return;}
        if(e.getChild() instanceof Pig pig){pig.getPersistentData().putString(HERD,key);pig.getPersistentData().putUUID("GrowthParentA",e.getParentA().getUUID());pig.getPersistentData().putUUID("GrowthParentB",e.getParentB().getUUID());pig.setPersistenceRequired();}
    }
    @SubscribeEvent public static void joined(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e){
        if(!(e.getLevel() instanceof ServerLevel l)||!(e.getEntity() instanceof Pig pig))return;
        var d=existing(l.getServer());var r=d==null?null:d.sites.get(pig.getPersistentData().getString(HERD));
        if(r!=null&&pig.getPersistentData().hasUUID("GrowthParentA"))d.recordAnimal(r,pig,l.getGameTime(),"BORN_BY_ANIMAL_AI");
    }
    @SubscribeEvent public static void died(net.neoforged.neoforge.event.entity.living.LivingDeathEvent e){
        if(!(e.getEntity().level() instanceof ServerLevel l)||!(e.getEntity() instanceof Pig pig))return;
        var d=existing(l.getServer());var r=d==null?null:d.sites.get(pig.getPersistentData().getString(HERD));if(r==null)return;
        for(var t:r.getList("Animals",Tag.TAG_COMPOUND)){var a=(CompoundTag)t;if(a.getUUID("Id").equals(pig.getUUID())&&!a.contains("Died")){a.putLong("Died",l.getGameTime());a.putString("DeathCause",e.getSource().getMsgId());d.setDirty();}}
    }
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider lookup){t.putInt("Schema",1);var list=new ListTag();sites.values().forEach(r->list.add(r.copy()));t.put("Sites",list);return t;}
    private static SettlementGrowthData load(CompoundTag t,HolderLookup.Provider lookup){
        if(t.getInt("Schema")!=1)throw new IllegalArgumentException("Unknown growth schema");var d=new SettlementGrowthData();
        for(var value:t.getList("Sites",Tag.TAG_COMPOUND)){
            var r=((CompoundTag)value).copy();var b=building(r);
            if(!GrowthLayout.supports(b)||r.getInt("Placed")<0||r.getInt("Placed")>57||d.sites.putIfAbsent(b.buildingKey(),r)!=null)throw new IllegalArgumentException("Invalid growth record");
            if(r.getBoolean("Submitting")||r.getBoolean("InitializingAnimal")){r.putBoolean("Review",true);r.putString("Reason","事务中断，保留原身份、材料与义务待核对，不重放");}
            d.index(r);
        }return d;
    }
}
