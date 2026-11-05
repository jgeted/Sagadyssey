package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.politics.NaturalPolityData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.*;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import java.util.*;
import static com.jgeted.sagadyssey.social.growth.SettlementCoordinatorData.*;

/** New records reference original material/identity ledgers; querying never enables a site. */
public final class SettlementSafetyData extends SavedData {
    public static final String NAME = "sagadyssey_settlement_safety_v1";
    private static final Factory<SettlementSafetyData> FACTORY = new Factory<>(SettlementSafetyData::new, SettlementSafetyData::load);
    private final Map<String, CompoundTag> sites = new LinkedHashMap<>();
    public static SettlementSafetyData get(MinecraftServer s) { return s.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME); }
    public static SettlementSafetyData existing(MinecraftServer s) { return s == null ? null : s.overworld().getDataStorage().get(FACTORY, NAME); }
    public CompoundTag site(String key) { var r=sites.get(key); return r==null?null:r.copy(); }
    public List<CompoundTag> sites() { return sites.values().stream().map(CompoundTag::copy).toList(); }
    CompoundTag resident(NpcBase n) {
        var development=SettlementDevelopmentData.residentVillage(n.getServer(),n.getCharacterId());
        if(development!=null){
            var body=com.jgeted.sagadyssey.social.character.CharacterRegistryData.get(n.getServer()).bodyOf(n.getCharacterId());
            if(body!=null&&body.entityId().equals(n.getUUID())&&body.dimensionId().equals(n.level().dimension().location().toString()))
                return sites.get(development.buildingKey());
        }
        for(var r:sites.values()) {
            var b=building(r);
            if(!b.dimension().equals(n.level().dimension().location().toString())) continue;
            for(int i=0;i<3;i++) if(NaturalVillageLifeData.person(b,i).equals(n.getCharacterId())
                    &&NaturalVillageLifeData.body(b,i).equals(n.getUUID())) return r;
        }
        return null;
    }
    public static boolean enabled(NpcBase n) { var d=existing(n.getServer()); return d!=null&&d.resident(n)!=null; }

    /** Public works permission is explicit, bounded and separate from the mining parcel. */
    public String activate(MinecraftServer s,String key,UUID official,long now) {
        var coord=SettlementCoordinatorData.existing(s); var previous=coord==null?null:coord.site(key);
        if(previous==null) return "NO_ACTIVE_COORDINATION";
        var b=building(previous);
        if(!NaturalPolityData.get(s).authorized(s,b,official)||!SettlementGrowthData.get(s).authority(s,b)) return "UNAUTHORIZED";
        if(sites.containsKey(key)) return "ALREADY_ACTIVE";
        var r=new CompoundTag();r.put("Village",previous.getCompound("Village").copy());
        r.putUUID("ApprovedBy",official);r.putLong("Activated",now);r.put("Tasks",new CompoundTag());
        r.put("ProtectedEdits",new CompoundTag());
        // Template common floor, excluding the private animal pen, crop beds and resource patch.
        var permit=new ArrayList<Long>();
        for(int x=1;x<=24;x++)for(int z=9;z<=20;z++) {
            if(x>=2&&x<=7&&z>=16||x>=21&&z>=15)continue;
            permit.add(NaturalVillageLayout.at(b,new BlockPos(x,1,z)).asLong());
        }
        for(int x=9;x<=12;x++)for(int z=3;z<=8;z++)permit.add(NaturalVillageLayout.at(b,new BlockPos(x,1,z)).asLong());
        if(SettlementDevelopmentLayout.supports(b))for(int lot=0;lot<2;lot++){
            var base=SettlementDevelopmentLayout.localLot(lot);
            // Only each declared home's aisle and front access. Beds/crops/private stores are excluded.
            permit.add(NaturalVillageLayout.at(b,base.offset(3,1,1)).asLong());
            for(int x=1;x<=3;x++)permit.add(NaturalVillageLayout.at(b,base.offset(x,1,-1)).asLong());
        }
        r.putLongArray("LightingPermit",permit);r.putLong("LastThreat",now-SettlementSafetyPolicy.QUIET_TICKS);
        sites.put(key,r);setDirty();return "ACTIVATED";
    }
    static boolean loaded(ServerLevel l,BlockPos p) { return l.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)!=null; }
    boolean permitted(CompoundTag r,BlockPos p) {
        return Arrays.stream(r.getLongArray("LightingPermit")).anyMatch(v->v==p.asLong())
                &&!r.getCompound("ProtectedEdits").contains(Long.toString(p.asLong()));
    }
    private void scan(MinecraftServer s,CompoundTag r,long now) {
        var b=building(r);var l=SupplyFlowService.level(s,b.dimension());if(l==null)return;
        int dark=0, observed=0;
        for(long encoded:r.getLongArray("LightingPermit")) {
            var p=BlockPos.of(encoded);if(!loaded(l,p))continue;
            // Sky light is deliberately ignored: a covered dark floor remains a night risk.
            if(!l.getBlockState(p.below()).isFaceSturdy(l,p.below(),Direction.UP)
                    ||!l.getBlockState(p).isAir()||!l.getBlockState(p.above()).isAir())continue;
            observed++;
            if(l.getBrightness(LightLayer.BLOCK,p)==0)dark++;
        }
        r.putInt("ObservedFloor",observed);r.putInt("DarkFloor",dark);r.putLong("Scanned",now);
        var tasks=r.getCompound("Tasks");
        // Do not issue more material while an earlier placement awaits light propagation.
        boolean pending=false;
        for(String key:tasks.getAllKeys()) {
            var task=tasks.getCompound(key);
            if(task.getInt("Placed")==1&&!task.getString("State").equals("DONE")) {
                var p=BlockPos.of(task.getLong("Position"));
                if(loaded(l,p)&&l.getBlockState(p).is(Blocks.TORCH)&&l.getBrightness(LightLayer.BLOCK,p)>0) {
                    complete(task,now);task.putInt("MeasuredBlockLight",l.getBrightness(LightLayer.BLOCK,p));
                } else task.putString("Reason","已施工，等待真实光照或核对外部改变");
            }
            if(!task.getString("State").equals("DONE"))pending=true;
        }
        if(!pending&&dark>0&&SettlementGrowthData.get(s).authority(s,b)) {
            for(long encoded:r.getLongArray("LightingPermit")) {
                var p=BlockPos.of(encoded);
                if(!loaded(l,p)||!permitted(r,p)||!l.getBlockState(p).isAir()
                        ||!Blocks.TORCH.defaultBlockState().canSurvive(l,p)||l.getBrightness(LightLayer.BLOCK,p)>0)continue;
                int cycle=r.getInt("Sequence");r.putInt("Sequence",cycle+1);
                String key=b.buildingKey()+":safety-light:"+cycle;
                var task=new CompoundTag();task.put("Village",r.getCompound("Village").copy());
                task.putString("Id",key);task.putString("ProjectKey",key);task.putString("State","WAITING");
                task.putLong("Position",encoded);task.putLong("Created",now);task.putString("Reason","实际暗点等待合法材料与施工");
                tasks.put(key,task);break;
            }
        }
        setDirty();
    }
    CompoundTag choose(NpcBase n,long now) {
        var r=resident(n);if(r==null||!SettlementGrowthData.get(n.getServer()).eligible(n,r))return null;
        var c=SettlementCoordinatorData.existing(n.getServer());var coordination=c==null?null:c.resident(n.getCharacterId());
        if(n.getProfession()==com.jgeted.sagadyssey.npc.profession.NpcProfession.FARMER
                &&coordination!=null&&coordination.getBoolean("Harvest"))return null;
        for(String key:r.getCompound("Tasks").getAllKeys()) {
            var task=r.getCompound("Tasks").getCompound(key);
            if(task.getString("State").equals("DONE")||task.getBoolean("Review")||now<task.getLong("RetryAt")||task.getInt("Placed")==1)continue;
            if(task.hasUUID("Person")&&!task.getUUID("Person").equals(n.getCharacterId()))continue;
            task.putUUID("Person",n.getCharacterId());task.putUUID("Body",n.getUUID());setDirty();return task;
        }
        return null;
    }
    String work(NpcBase n,String key,long now) {
        var r=resident(n);if(r==null)return "YIELD";
        var t=r.getCompound("Tasks").getCompound(key);var b=building(r);var l=(ServerLevel)n.level();
        if(!t.hasUUID("Person")||!t.getUUID("Person").equals(n.getCharacterId())||!t.getUUID("Body").equals(n.getUUID())
                ||t.getBoolean("Review")||t.getInt("Placed")==1||!SettlementGrowthData.get(n.getServer()).eligible(n,r))return "YIELD";
        if(t.getLong("LastTick")==now)return "同刻不重复劳动";
        t.putLong("LastTick",now);setDirty();var p=BlockPos.of(t.getLong("Position"));
        if(!permitted(r,p)||!loaded(l,p)||!l.getBlockState(p).isAir()||!Blocks.TORCH.defaultBlockState().canSurvive(l,p))return waitFor(t,now,"照明位置或施工许可失效，保留原材料责任");
        var growth=SettlementGrowthData.get(n.getServer());
        if(t.getBoolean("SupplyCargo"))return supply(n,t,b,null,now);
        if(t.getBoolean("MaterialCarried")||available(n.getServer(),b,3,Items.TORCH)>0) {
            String result=growth.place(n,t,new GrowthLayout.Placement(Items.TORCH,List.of(p),List.of(Blocks.TORCH.defaultBlockState())),0);
            t.putString("Reason",result);setDirty();return result;
        }
        // Finish a claimed batch even when its inputs are now reserved by that batch.
        var craft=t.getCompound("Craft");
        if(!craft.isEmpty()&&!craft.getString("HouseStage").equals("DONE")) {
            String stage=craft.getString("Kind");String result=growth.craft(n,craft,stage);
            t.putString("Reason",result);setDirty();return result.equals("YIELD")?waitFor(t,now,craft.getString("Reason")):result;
        }
        Item fuel=available(n.getServer(),b,3,Items.COAL)>0?Items.COAL:Items.CHARCOAL;
        String stage=null;
        if(available(n.getServer(),b,3,Items.STICK)>0&&available(n.getServer(),b,3,fuel)>0)stage=fuel==Items.COAL?"TORCH":"CHARCOAL_TORCH";
        else if(available(n.getServer(),b,3,Items.STICK)==0&&available(n.getServer(),b,3,Items.OAK_PLANKS)>=2)stage="STICKS";
        if(stage!=null) {
            int seq=t.getInt("CraftSequence");t.putInt("CraftSequence",seq+1);craft=new CompoundTag();
            craft.put("Village",r.getCompound("Village").copy());craft.putString("Kind",stage);craft.putString("HouseStage",stage);
            craft.putString("ProjectKey",key+":craft:"+seq);t.put("Craft",craft);setDirty();return "领取真实照明制作工项";
        }
        Item missing=null;
        if(available(n.getServer(),b,1,Items.TORCH)>0)missing=Items.TORCH;
        else if(available(n.getServer(),b,3,Items.STICK)==0) {
            if(available(n.getServer(),b,1,Items.STICK)>0)missing=Items.STICK;
            else if(available(n.getServer(),b,3,Items.OAK_PLANKS)<2&&available(n.getServer(),b,1,Items.OAK_PLANKS)>=2)missing=Items.OAK_PLANKS;
        }
        if(missing==null&&available(n.getServer(),b,3,Items.COAL)==0&&available(n.getServer(),b,3,Items.CHARCOAL)==0) {
            if(available(n.getServer(),b,1,Items.COAL)>0)missing=Items.COAL;
            else if(available(n.getServer(),b,1,Items.CHARCOAL)>0)missing=Items.CHARCOAL;
        }
        if(missing!=null)return supply(n,t,b,missing,now);
        return waitFor(t,now,"公共仓及已授权家庭来源缺少可处分的火把或煤/木炭、木棍/木板；等待正式补给，不征用私人库存");
    }
    private String supply(NpcBase n,CompoundTag t,NaturalVillageBuilding b,Item missing,long now) {
        var growth=SettlementGrowthData.get(n.getServer());var personal=ContainerIds.npcPersonal(n.getCharacterId());
        var actor=SubjectRef.character(n.getCharacterId());var rights=RightsStateData.get(n.getServer());
        rights.ensureContext(personal,"npc:"+n.getCharacterId()+":personal",actor,actor,AccessPolicy.PRIVATE);
        int cycle=t.getInt("SupplyCycle");
        if(!t.getBoolean("SupplyCargo")) {
            if(!growth.near(n,NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY)))return "前往已授权家庭仓领取有限照明材料";
            int quantity=missing==Items.OAK_PLANKS?2:1;
            if(available(n.getServer(),b,1,missing)<quantity)return waitFor(t,now,"原照明来源可用余量不足");
            var source=NaturalVillageEconomyData.storeId(b,1);
            if(!growth.transfer(n,t,ContainerIds.named(t.getString("Id")+":supply-pickup:"+cycle),SubjectRef.household(NaturalVillageLifeData.home(b)),
                    TransferIntent.GIFT,List.of(new TransferLeg(source,personal,item(missing),quantity)),
                    Map.of(source,store(n.getServer(),b,1),personal,n.getPersonalInventory())))return waitFor(t,now,"原材料领取未成功，保留原回执");
            t.putBoolean("SupplyCargo",true);t.putString("SupplyItem",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(missing).toString());
            t.putInt("SupplyQuantity",quantity);t.putUUID("SupplyPickup",t.getUUID("LastTransfer"));setDirty();return "原材料实际入原居民背包";
        }
        if(!growth.near(n,NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC)))return "携带原照明材料前往公共工作仓";
        var type=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(t.getString("SupplyItem")));
        var dest=NaturalVillageEconomyData.storeId(b,3);
        if(!growth.transfer(n,t,ContainerIds.named(t.getString("Id")+":supply-delivery:"+cycle),actor,TransferIntent.GIFT,
                List.of(new TransferLeg(personal,dest,item(type),t.getInt("SupplyQuantity"))),
                Map.of(personal,n.getPersonalInventory(),dest,store(n.getServer(),b,3))))return waitFor(t,now,"照明材料尚未入公共仓，保留实际携货");
        t.remove("SupplyCargo");t.putInt("SupplyCycle",cycle+1);t.putUUID("SupplyDelivery",t.getUUID("LastTransfer"));setDirty();return "照明材料实际交付公共仓";
    }
    private String waitFor(CompoundTag t,long now,String reason) { t.putString("Reason",reason);t.putLong("RetryAt",now+200);setDirty();return "YIELD"; }
    void observedThreat(CompoundTag r,long now) { r.putLong("LastThreat",now);setDirty(); }

    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var s=event.getServer();long now=s.overworld().getGameTime();if(now%SettlementSafetyPolicy.SCAN_INTERVAL!=0)return;
        var data=existing(s);if(data!=null)for(var r:data.sites.values())data.scan(s,r,now);
    }
    @SubscribeEvent public static void joined(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if(!(event.getLevel() instanceof ServerLevel)||event.getEntity().getClass()!=Zombie.class)return;
        var z=(Zombie)event.getEntity();
        // Goal objects belong to this entity lifetime; persistent flags would wrongly skip reload installation.
        z.targetSelector.addGoal(3,new NearestAttackableTargetGoal<>(z,NpcBase.class,10,true,false,
                e->e instanceof NpcBase n&&n.isAlive()&&NpcBrainRuntime.isPlanned(n)&&enabled(n)
                        &&SettlementSafetyRuntime.insideDefence(n,z.blockPosition())));
    }
    @SubscribeEvent public static void placed(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent e) {
        if(e.getEntity() instanceof net.minecraft.world.entity.player.Player&&e.getLevel() instanceof ServerLevel l)protect(l,e.getPos());
    }
    @SubscribeEvent public static void broken(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent e) {
        if(e.getLevel() instanceof ServerLevel l) {
            // Breaking our light permits repair; changing another block revokes that point.
            if(!e.getState().is(Blocks.TORCH))protect(l,e.getPos());
        }
    }
    private static void protect(ServerLevel l,BlockPos p) {
        var d=existing(l.getServer());if(d==null)return;
        for(var r:d.sites.values())if(building(r).dimension().equals(l.dimension().location().toString())) {
            for(var cell:List.of(p,p.above()))if(d.permitted(r,cell))r.getCompound("ProtectedEdits").putLong(Long.toString(cell.asLong()),l.getGameTime());
        }d.setDirty();
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup) {
        tag.putInt("Schema",1);var list=new ListTag();sites.values().forEach(r->list.add(r.copy()));tag.put("Sites",list);return tag;
    }
    static SettlementSafetyData load(CompoundTag tag,HolderLookup.Provider lookup) {
        if(tag.getInt("Schema")!=1)throw new IllegalStateException("Unsupported safety schema");
        var d=new SettlementSafetyData();
        for(var t:tag.getList("Sites",Tag.TAG_COMPOUND)) {
            var r=((CompoundTag)t).copy();
            for(String key:r.getCompound("Tasks").getAllKeys()) {
                var job=r.getCompound("Tasks").getCompound(key);
                if(job.getBoolean("Submitting")||job.getCompound("Craft").getBoolean("Submitting")) {
                    job.putBoolean("Review",true);job.putString("Reason","中断事务需核对原回执，不重新发料");
                }
            }
            d.sites.put(building(r).buildingKey(),r);
        }return d;
    }
}
