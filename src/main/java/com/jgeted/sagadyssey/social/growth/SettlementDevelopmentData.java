package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.knowledge.*;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.*;
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
import net.minecraft.world.item.*;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import java.util.*;

/** New versioned coordination only; original identity, inventory and transaction ledgers remain authoritative. */
public final class SettlementDevelopmentData extends SavedData {
    public static final String NAME="sagadyssey_settlement_development_v1";
    public static final Factory<SettlementDevelopmentData> FACTORY=new Factory<>(SettlementDevelopmentData::new,SettlementDevelopmentData::load);
    private final Map<String,CompoundTag> sites=new LinkedHashMap<>();
    private final Map<UUID,CompoundTag> applications=new LinkedHashMap<>();
    public static SettlementDevelopmentData get(MinecraftServer s){return s.overworld().getDataStorage().computeIfAbsent(FACTORY,NAME);}
    public static SettlementDevelopmentData existing(MinecraftServer s){return s.overworld().getDataStorage().get(FACTORY,NAME);}
    public List<CompoundTag> sites(){return sites.values().stream().map(CompoundTag::copy).toList();}
    public List<CompoundTag> applications(){return applications.values().stream().map(CompoundTag::copy).toList();}
    public CompoundTag site(String key){var t=sites.get(key);return t==null?null:t.copy();}
    public CompoundTag application(UUID household){var t=applications.get(household);return t==null?null:t.copy();}
    static NaturalVillageBuilding village(CompoundTag t){return NaturalVillageLifeData.decode(t.getCompound("Village"));}
    static ItemFingerprint item(Item i){return ItemFingerprint.of(new ItemStack(i));}
    static ServerLevel level(MinecraftServer s,NaturalVillageBuilding b){return SupplyFlowService.level(s,b.dimension());}
    static boolean loaded(ServerLevel l,BlockPos p){return l!=null&&l.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)!=null;}
    static boolean alive(MinecraftServer s,UUID person){var c=CharacterRegistryData.get(s).byId(person);return c!=null&&c.alive();}
    static Container box(ServerLevel l,BlockPos p){return loaded(l,p)&&l.getBlockEntity(p) instanceof Container c?c:null;}
    static int count(Container c,Item i){return c==null?0:SupplyFlowService.count(c,item(i));}
    private static UUID pantryId(String dimension,BlockPos p){return ContainerIds.block("household_storage",dimension,p);}
    private void state(CompoundTag t,String state,String reason,long now){
        if(!state.equals(t.getString("State"))||!reason.equals(t.getString("Reason"))){
            t.putString("State",state);t.putString("Reason",reason);t.putLong("Changed",now);setDirty();
            Sagadyssey.LOGGER.info("DEVELOPMENT_EVENT tick={} id={} state={} reason={}",now,t.getString("Id"),state,reason);
        }
    }

    /** Unknown or unloaded facilities never become free beds or spendable food. */
    public static SettlementCapacity.Home capacity(MinecraftServer s,HouseholdRecord h){
        var l=SupplyFlowService.level(s,h.dimension());var chars=CharacterRegistryData.get(s);var life=NpcLifeData.get(s);
        int living=0,beds=0,workers=0;boolean known=h.sharedStorage()!=null&&loaded(l,h.sharedStorage());var distinctBeds=new HashSet<BlockPos>();
        for(var m:h.members()){
            var c=chars.byId(m.characterId());if(c==null){known=false;living++;continue;}if(!c.alive())continue;living++;
            var r=life.record(m.characterId());if(r==null){known=false;continue;}
            if(r.bedPosition()!=null&&!loaded(l,r.bedPosition()))known=false;
            else if(r.bedPosition()!=null&&PublicHousingConditions.validBed(l,r.bedPosition())&&distinctBeds.add(r.bedPosition()))beds++;
            var hint=chars.bodyOf(m.characterId());
            var body=hint!=null&&hint.dimensionId().equals(h.dimension())?l.getEntity(hint.entityId()):null;
            if(body instanceof NpcBase n&&n.isAlive()&&!n.isBaby()&&!n.isNoAi()&&n.getCommand()==NpcCommand.WORK&&n.getTarget()==null
                    &&r.authority()==NpcWorkAuthority.INSTITUTION&&Objects.equals(r.activeWorkplaceSettlementId(),h.settlementId()))workers++;
        }
        int food=0;var inv=h.sharedStorage()==null?null:box(l,h.sharedStorage());
        if(inv!=null){
            var id=pantryId(h.dimension(),h.sharedStorage());var rights=RightsStateData.get(s);var service=new RightsService(rights);
            for(int i=0;i<inv.getContainerSize();i++)if(inv.getItem(i).is(Items.CARROT)
                    &&service.can(SubjectRef.household(h.id()),AssetRight.TRANSFER,id,i,item(Items.CARROT),s.overworld().getGameTime()).status()==RightsVerdict.Status.ALLOW)food+=inv.getItem(i).getCount();
            food=Math.max(0,food-rights.reservedQuantity(id,item(Items.CARROT),null,s.overworld().getGameTime()));
        }
        int incoming=SupplyFlowData.get(s).routes().stream().filter(r->!r.closed()&&r.principal().equals(SubjectRef.household(h.id()))).mapToInt(SupplyFlowData.Route::quantity).sum();
        return new SettlementCapacity.Home(h.id(),living,food,beds,workers,known,incoming,NaturalFarmRules.SEEDS);
    }
    private CompoundTag forPerson(MinecraftServer s,UUID person){
        var h=HouseholdData.get(s).forMember(person);if(h==null)return null;
        for(var row:sites.values())if(Objects.equals(h.settlementId(),village(row).settlementId()))return row;return null;
    }
    private CompoundTag moveFor(MinecraftServer s,UUID person){var h=HouseholdData.get(s).forMember(person);return h==null?null:applications.get(h.id());}
    public static NaturalVillageBuilding residentVillage(MinecraftServer s,UUID person){
        var d=existing(s);var r=d==null?null:d.forPerson(s,person);return r==null?null:village(r);
    }
    public static boolean moving(MinecraftServer s,UUID person){
        var d=existing(s);var a=d==null?null:d.moveFor(s,person);return a!=null&&Set.of("WALKING","WAIT_DESTINATION","ARRIVING").contains(a.getString("State"));
    }
    public static boolean departed(MinecraftServer s,UUID person){var d=existing(s);var a=d==null?null:d.moveFor(s,person);return a!=null&&a.contains("Departed");}
    public static int materialDeficit(MinecraftServer s,NaturalVillageBuilding b){
        var d=existing(s);var row=d==null?null:d.sites.get(b.buildingKey());var p=row==null?null:d.current(row);if(p==null||!p.getString("State").equals("BUILDING"))return 0;
        var build=d.task(p,"BUILD");int placed=build.getInt("Placed");
        var stone=d.task(p,"STONE");int carried=(build.getBoolean("MaterialCarried")?Math.max(1,build.getInt("MaterialsHeld")):0)+(stone.getBoolean("Cargo")?stone.getInt("Quantity"):0);
        return Math.max(0,SettlementDevelopmentLayout.stoneCost(b)-Math.min(SettlementDevelopmentLayout.stoneCost(b),placed)-count(NaturalPolityData.inventory(s,b,1),Items.COBBLESTONE)-count(NaturalPolityData.inventory(s,b,2),Items.COBBLESTONE)-count(NaturalPolityData.inventory(s,b,3),Items.COBBLESTONE)-carried);
    }
    public static boolean ordinaryWorker(NpcBase n){
        var b=residentVillage(n.getServer(),n.getCharacterId());if(b==null)return false;var d=existing(n.getServer());var p=d.current(d.forPerson(n.getServer(),n.getCharacterId()));
        if(p==null||!p.getString("State").equals("BUILDING"))return false;
        return materialDeficit(n.getServer(),b)>0||count(NaturalPolityData.inventory(n.getServer(),b,1),Items.OAK_LOG)+count(NaturalPolityData.inventory(n.getServer(),b,2),Items.OAK_LOG)+count(NaturalPolityData.inventory(n.getServer(),b,3),Items.OAK_LOG)<6;
    }
    /** Selection within existing rights, never a new resource permission. */
    public static boolean productionTarget(NpcBase n,net.minecraft.world.level.block.state.BlockState state){
        var b=residentVillage(n.getServer(),n.getCharacterId());if(b==null)return true;
        var d=existing(n.getServer());var p=d.current(d.forPerson(n.getServer(),n.getCharacterId()));
        if(p==null||!p.getString("State").equals("BUILDING"))return true;
        if(state.is(net.minecraft.tags.BlockTags.LOGS)||state.is(net.minecraft.tags.BlockTags.LEAVES))
            return count(NaturalPolityData.inventory(n.getServer(),b,1),Items.OAK_LOG)
                    +count(NaturalPolityData.inventory(n.getServer(),b,2),Items.OAK_LOG)
                    +count(NaturalPolityData.inventory(n.getServer(),b,3),Items.OAK_LOG)<6;
        return materialDeficit(n.getServer(),b)>0;
    }
    public static boolean resourceAllowed(NpcBase n,BlockPos pos){
        var b=residentVillage(n.getServer(),n.getCharacterId());if(b==null)return false;var d=existing(n.getServer());var p=d.current(d.forPerson(n.getServer(),n.getCharacterId()));
        return p!=null&&SettlementResourceSurvey.allowed(n,p,pos);
    }
    private static BlockPos local(NaturalVillageBuilding b,BlockPos world){
        var p=world.subtract(b.buildingOrigin());
        return switch(b.rotation()){
            case NONE->p;
            case CLOCKWISE_90->new BlockPos(p.getZ(),p.getY(),-p.getX());
            case CLOCKWISE_180->new BlockPos(-p.getX(),p.getY(),-p.getZ());
            case COUNTERCLOCKWISE_90->new BlockPos(-p.getZ(),p.getY(),p.getX());
        };
    }
    /** Known, authorized gates are waypoints. A failed target path must not send a new worker into the old cottage. */
    private record DoorRoute(BlockPos door,BlockPos waypoint,boolean entering){}
    private static NaturalVillageBuilding navigationVillage(NpcBase n){
        var resident=residentVillage(n.getServer(),n.getCharacterId());if(resident!=null)return resident;
        var d=existing(n.getServer());var a=d==null?null:d.moveFor(n.getServer(),n.getCharacterId());
        if(a!=null&&moving(n.getServer(),n.getCharacterId()))return NaturalVillageLifeData.decode(a.getCompound("Source"));
        return null;
    }
    private static boolean rectangle(BlockPos p,int x1,int x2,int z1,int z2){return p.getX()>=x1&&p.getX()<=x2&&p.getZ()>=z1&&p.getZ()<=z2;}
    private static DoorRoute doorway(NpcBase n,NaturalVillageBuilding b,BlockPos here,BlockPos to,
                                    int x1,int x2,int z1,int z2,BlockPos door,BlockPos inside,BlockPos outside){
        boolean in=rectangle(here,x1,x2,z1,z2),targetIn=rectangle(to,x1,x2,z1,z2);
        var at=NaturalVillageLayout.at(b,door);
        if(!SettlementDevelopmentLayout.wide(b)){
            if(in==targetIn)return null;
            if(!loaded((ServerLevel)n.level(),at)||!(n.level().getBlockState(at).getBlock() instanceof net.minecraft.world.level.block.DoorBlock))return null;
            var first=NaturalVillageLayout.at(b,in?inside:outside);var second=NaturalVillageLayout.at(b,in?outside:inside);
            return new DoorRoute(at,n.distanceToSqr(first.getCenter())<2?second:first,!in);
        }
        var inner=NaturalVillageLayout.at(b,inside);var outer=NaturalVillageLayout.at(b,outside);
        var inward=net.minecraft.world.phys.Vec3.atBottomCenterOf(inner).subtract(net.minecraft.world.phys.Vec3.atBottomCenterOf(outer)).normalize();
        var relative=n.position().subtract(net.minecraft.world.phys.Vec3.atBottomCenterOf(at));
        double depth=relative.dot(inward);
        // The integer block boundary is not evidence that the whole body cleared
        // the doorway. Keep the crossing until its near side is physically clear.
        boolean threshold=relative.horizontalDistanceSqr()<2.25&&Math.abs(relative.y)<1
                &&(targetIn?depth<.85:depth>-.85);
        if(in==targetIn&&!threshold)return null;
        if(!loaded((ServerLevel)n.level(),at)||!(n.level().getBlockState(at).getBlock() instanceof net.minecraft.world.level.block.DoorBlock))return null;
        var first=targetIn?outer:inner;var second=targetIn?inner:outer;
        return new DoorRoute(at,doorWaypoint(n.position(),first,second),targetIn);
    }
    /** Align with the actual passage before crossing; never cut its door frame.
     * Progress along the passage keeps the forward point after alignment, so
     * this requires no new persistent state and cannot oscillate by distance. */
    static BlockPos doorWaypoint(net.minecraft.world.phys.Vec3 actual,BlockPos first,BlockPos second){
        var start=net.minecraft.world.phys.Vec3.atBottomCenterOf(first);
        var axis=net.minecraft.world.phys.Vec3.atBottomCenterOf(second).subtract(start).normalize();
        var delta=actual.subtract(start);double along=delta.dot(axis);
        double lateral=Math.abs(delta.x*axis.z-delta.z*axis.x);
        return along>=-.15&&lateral<=.15?second:first;
    }
    /** A shared doorway is a passage, not three residents' idle destination.
     * Own admitted household only; walking here creates no work/resource rights. */
    public static BlockPos idleHomeStand(NpcBase n){
        var d=existing(n.getServer());var row=d==null?null:d.forPerson(n.getServer(),n.getCharacterId());
        var home=HouseholdData.get(n.getServer()).forMember(n.getCharacterId());
        if(row==null||home==null)return null;var b=village(row);if(!SettlementDevelopmentLayout.wide(b))return null;
        for(var p:d.projects(row)){
            if(!Set.of("OCCUPIED","STABLE").contains(p.getString("State"))||!home.id().equals(p.getUUID("Household")))continue;
            var members=home.members().stream().map(m->m.characterId()).sorted().toList();int index=members.indexOf(n.getCharacterId());if(index<0||index>2)return null;
            var stand=NaturalVillageLayout.at(b,SettlementDevelopmentLayout.localLot(p.getInt("Lot")).offset(index*2,1,-2));
            return loaded((ServerLevel)n.level(),stand)&&FieldworkRoutes.walkable(n,stand)?stand:null;
        }return null;
    }
    private static DoorRoute doorRoute(NpcBase n,BlockPos target){
        var b=navigationVillage(n);if(b==null)return null;
        var here=local(b,n.blockPosition());var to=local(b,target);
        // Exit the enclosing home before trying to reach the courtyard's exit.
        var old=SettlementDevelopmentLayout.supports(b)?doorway(n,b,here,to,15,17,13,15,new BlockPos(16,1,12),new BlockPos(16,1,13),new BlockPos(16,1,11)):null;
        if(old!=null&&rectangle(here,15,17,13,15))return old;
        var d=existing(n.getServer());var row=d.forPerson(n.getServer(),n.getCharacterId());
        var home=HouseholdData.get(n.getServer()).forMember(n.getCharacterId());
        DoorRoute incoming=null;
        for(var p:row==null?List.<CompoundTag>of():d.projects(row)){
            var base=SettlementDevelopmentLayout.localLot(p.getInt("Lot"));
            var life=NpcLifeData.get(n.getServer()).record(n.getCharacterId());
            boolean builder=Set.of("BUILDING","READY").contains(p.getString("State"))&&life!=null
                    &&life.authority()==NpcWorkAuthority.INSTITUTION&&Objects.equals(life.activeWorkplaceSettlementId(),b.settlementId())
                    &&Set.of(NpcProfession.WORKER,NpcProfession.COURIER).contains(n.getProfession());
            if(!home.id().equals(p.getUUID("Household"))&&!builder)continue;
            var route=doorway(n,b,here,to,base.getX()+1,base.getX()+SettlementDevelopmentLayout.width(b)-2,base.getZ()+1,base.getZ()+3,
                    base.offset(2,1,0),base.offset(2,1,1),base.offset(2,1,-1));
            if(route!=null){if(rectangle(here,base.getX()+1,base.getX()+SettlementDevelopmentLayout.width(b)-2,base.getZ()+1,base.getZ()+3))return route;incoming=route;}
        }
        var yard=doorway(n,b,here,to,0,25,0,20,new BlockPos(12,1,21),new BlockPos(12,1,20),new BlockPos(12,1,22));
        return yard!=null?yard:old!=null?old:incoming;
    }
    public static BlockPos gateWaypoint(NpcBase n,BlockPos target){var route=doorRoute(n,target);return route==null?null:route.waypoint();}
    public static boolean route(NpcBase n,BlockPos target){
        var path=doorRoute(n,target);if(path==null)return false;
        var b=navigationVillage(n);var state=n.level().getBlockState(path.door());
        var safety=SettlementSafetyData.existing(n.getServer());var r=safety==null?null:safety.site(b.buildingKey());
        boolean alarm=r!=null&&n.level().getGameTime()-r.getLong("LastThreat")<SettlementSafetyPolicy.QUIET_TICKS;
        if(n.distanceToSqr(path.door().getCenter())<16&&state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock door&&(!alarm||path.entering()))
            door.setOpen(n,(ServerLevel)n.level(),state,path.door(),true);
        SettlementSurveyNavigation.move(n,path.waypoint());return true;
    }
    static void constructionApproach(NpcBase n,CompoundTag task,BlockPos target){
        var b=village(task);var candidates=new ArrayList<BlockPos>();
        for(var stand:BlockPos.betweenClosed(target.offset(-2,-4,-2),target.offset(2,1,2))){
            if(!FieldworkRoutes.walkable(n,stand))continue;
            var eye=net.minecraft.world.phys.Vec3.atBottomCenterOf(stand).add(0,n.getEyeHeight(),0);
            var hit=n.level().clip(new net.minecraft.world.level.ClipContext(eye,net.minecraft.world.phys.Vec3.atCenterOf(target),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,n));
            if(hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS||hit.getBlockPos().equals(target))candidates.add(stand.immutable());
        }
        candidates.sort(Comparator.comparingDouble(n.blockPosition()::distSqr));
        for(var stand:candidates)if(SettlementSurveyNavigation.move(n,stand))return;
        SettlementSurveyNavigation.move(n,SettlementDevelopmentLayout.entrance(b,task.getInt("Lot")));
    }
    private void doors(MinecraftServer s,CompoundTag row,long now){
        var b=village(row);var l=level(s,b);
        var safety=SettlementSafetyData.existing(s);var safetyRow=safety==null?null:safety.site(b.buildingKey());
        boolean alarm=safetyRow!=null&&now-safetyRow.getLong("LastThreat")<SettlementSafetyPolicy.QUIET_TICKS;
        for(var p:projects(row)){
            var base=SettlementDevelopmentLayout.localLot(p.getInt("Lot"));var doorAt=NaturalVillageLayout.at(b,base.offset(2,1,0));
            if(!loaded(l,doorAt))continue;var state=l.getBlockState(doorAt);
            if(!(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock door))continue;
            boolean crossing=false;
            for(var n:l.getEntitiesOfClass(NpcBase.class,new net.minecraft.world.phys.AABB(doorAt).inflate(4))){
                if(!n.isAlive()||n.getTarget()!=null)continue;
                var home=HouseholdData.get(s).forMember(n.getCharacterId());if(home==null)continue;
                boolean occupant=home.id().equals(p.getUUID("Household"))&&Objects.equals(home.settlementId(),b.settlementId());
                boolean worker=p.getString("State").equals("BUILDING")&&eligible(n,row);
                var active=NpcBrainRuntime.data(s).active(n.getUUID()).orElse(null);
                if(alarm&&local(b,n.blockPosition()).getZ()>base.getZ())continue;
                if(active!=null&&(occupant&&active.currentStep().phase()==com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase.TRAVELLING
                        ||worker&&active.currentStep().params().getOrDefault("development","").equals(p.getString("Id"))))crossing=true;
            }
            if(crossing){door.setOpen(null,l,state,doorAt,true);p.putLong("DoorOpen",now);}
            else if(state.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)&&now-p.getLong("DoorOpen")>=40
                    &&l.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new net.minecraft.world.phys.AABB(doorAt).expandTowards(0,1,0)).isEmpty())
                door.setOpen(null,l,state,doorAt,false);
        }
    }
    public static int sourceProtection(MinecraftServer s,FieldworkStorage point,SubjectRef principal,ItemFingerprint fingerprint){
        if(principal.type()!=SubjectRef.SubjectType.HOUSEHOLD||!fingerprint.equals(item(Items.CARROT)))return 0;
        var d=existing(s);if(d==null)return 0;var a=d.applications.get(principal.id());if(a==null)return 0;
        var b=village(a);if(!NaturalVillageEconomyData.storeId(b,0).equals(point.containerId()))return 0;
        var home=HouseholdData.get(s).byId(NaturalVillageLifeData.home(b));return NaturalFarmRules.SEEDS+(home==null?6:(int)Math.ceil(capacity(s,home).dailyMeals()));
    }
    public static boolean farmer(NpcBase n){return n.getProfession()==NpcProfession.FARMER&&residentVillage(n.getServer(),n.getCharacterId())!=null&&!moving(n.getServer(),n.getCharacterId());}
    public static List<BlockPos> farmCells(NpcBase n){
        var b=residentVillage(n.getServer(),n.getCharacterId());if(b==null)return List.of();
        var d=existing(n.getServer());var row=d.forPerson(n.getServer(),n.getCharacterId());var h=HouseholdData.get(n.getServer()).forMember(n.getCharacterId());
        var fields=new ArrayList<BlockPos>();
        if(h.id().equals(NaturalVillageLifeData.home(b))){
            NaturalVillageLayout.crops().forEach(p->fields.add(NaturalVillageLayout.at(b,p)));
            for(var p:d.projects(row)){var a=d.applications.get(p.getUUID("Household"));
                if(a!=null&&!Set.of("ARRIVED","STABLE","WITHDRAWN").contains(a.getString("State")))fields.addAll(SettlementDevelopmentLayout.fields(b,p.getInt("Lot")));
            }
        }else{var a=d.applications.get(h.id());if(a!=null&&Set.of("ARRIVED","STABLE").contains(a.getString("State")))fields.addAll(SettlementDevelopmentLayout.fields(b,a.getInt("Lot")));}
        return List.copyOf(fields);
    }
    public static boolean cultivation(NpcBase n,BlockPos position){
        var d=existing(n.getServer());var row=d==null?null:d.forPerson(n.getServer(),n.getCharacterId());if(row==null)return false;
        var b=village(row);var h=HouseholdData.get(n.getServer()).forMember(n.getCharacterId());
        if(h.id().equals(NaturalVillageLifeData.home(b))){
            if(NaturalVillageLayout.crops().stream().map(x->NaturalVillageLayout.at(b,x)).anyMatch(x->x.getX()==position.getX()&&x.getZ()==position.getZ()))return true;
            return d.projects(row).stream().anyMatch(project->!Set.of("ARRIVED","STABLE","WITHDRAWING","WITHDRAWN").contains(d.applications.get(project.getUUID("Household")).getString("State"))
                    &&SettlementDevelopmentLayout.field(b,project.getInt("Lot"),position));
        }
        var a=d.applications.get(h.id());return a!=null&&Set.of("ARRIVED","STABLE").contains(a.getString("State"))&&SettlementDevelopmentLayout.field(b,a.getInt("Lot"),position);
    }
    /** Empty approved cells still need tilling/planting even when meals are abundant. */
    public static boolean needsFieldPreparation(NpcBase n){
        if(!farmer(n))return false;
        for(var p:farmCells(n))if(loaded((ServerLevel)n.level(),p)
                &&(!n.level().getBlockState(p.below()).is(net.minecraft.world.level.block.Blocks.FARMLAND)
                    ||n.level().getBlockState(p).isAir()))return true;
        return false;
    }
    public static boolean harvest(NpcBase n){
        var b=residentVillage(n.getServer(),n.getCharacterId());if(b==null)return true;
        var h=HouseholdData.get(n.getServer()).forMember(n.getCharacterId());var c=capacity(n.getServer(),h);var needs=NpcLifeData.get(n.getServer()).needs(n.getCharacterId());
        var d=existing(n.getServer());var row=d.forPerson(n.getServer(),n.getCharacterId());var pending=d.current(row);
        boolean expanding=false;
        if(pending!=null&&Set.of("BUILDING","READY").contains(pending.getString("State"))){
            var a=d.applications.get(pending.getUUID("Household"));
            if(a!=null&&!a.contains("Departed")){
                int target=(int)Math.ceil(a.getList("People",Tag.TAG_COMPOUND).size()*SettlementPolicy.DAILY_MEALS_PER_PERSON*2);
                var delivery=pending.getCompound("Jobs").getCompound("FOOD");
                int carried=delivery.getBoolean("Cargo")?delivery.getInt("Quantity"):0;
                int needed=SettlementCapacity.replenishment(target,
                        count(box(level(n.getServer(),b),SettlementDevelopmentLayout.pantry(b,pending.getInt("Lot"))),Items.CARROT),carried);
                int raw=Math.max(0,count(NaturalPolityData.inventory(n.getServer(),b,0),Items.CARROT)-NaturalFarmRules.SEEDS
                        -(int)Math.ceil(c.dailyMeals())-RightsStateData.get(n.getServer()).reservedQuantity(NaturalVillageEconomyData.storeId(b,0),item(Items.CARROT),null,n.level().getGameTime()));
                expanding=needed>raw;
            }
        }
        if(c.living()==0||!c.known())return false;
        var key="Harvest:"+h.id();boolean before=row.contains(key)?row.getBoolean(key):true;
        boolean chosen=SettlementPolicy.harvest(before,c.food(),c.dailyMeals(),expanding||needs!=null&&needs.hunger()>=800);
        if(chosen!=before||!row.contains(key)){row.putBoolean(key,chosen);d.setDirty();}
        return chosen;
    }
    private void observe(MinecraftServer s,CompoundTag row,long now){
        var b=village(row);var l=level(s,b);var pos=NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC);if(!loaded(l,pos))return;
        for(var n:l.getEntitiesOfClass(NpcBase.class,new net.minecraft.world.phys.AABB(pos).inflate(96))){
            var h=HouseholdData.get(s).forMember(n.getCharacterId());if(h==null||!h.headCharacterId().equals(n.getCharacterId())||Objects.equals(h.settlementId(),b.settlementId()))continue;
            var hit=l.clip(new net.minecraft.world.level.ClipContext(n.getEyePosition(),net.minecraft.world.phys.Vec3.atCenterOf(pos.above()),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,n));
            if(hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS)KnowledgeStateData.get(s).observe(n.getCharacterId(),new FactKey(b.settlementId(),"housing_work_opportunity","v7"),
                    b.buildingKey(),PrecisionBand.EXACT,1,"实际看见邻近聚落公开住房与工作机会",now,72000,DisclosurePolicy.SHAREABLE);
        }
    }
    public String apply(MinecraftServer s,NaturalVillageBuilding from,NaturalVillageBuilding to,UUID head,long now){
        var row=sites.get(to.buildingKey());var home=HouseholdData.get(s).byId(NaturalVillageLifeData.home(from));
        if(row==null||home==null||!home.headCharacterId().equals(head)||!alive(s,head))return "UNAUTHORIZED_OR_UNKNOWN_HOME";
        if(applications.containsKey(home.id()))return "ALREADY_APPLIED";
        boolean known=KnowledgeStateData.get(s).beliefsAbout(head,to.settlementId()).stream().anyMatch(k->k.key().predicate().equals("housing_work_opportunity")&&!k.staleAt(now));
        if(!known)return "UNKNOWN_OPPORTUNITY";
        if(!from.dimension().equals(to.dimension())||from.settlementId().equals(to.settlementId())||from.buildingOrigin().distSqr(to.buildingOrigin())>512*512)return "OUTSIDE_BOUNDED_ROUTE";
        var c=capacity(s,home);if(!c.known())return "SOURCE_CONDITIONS_UNKNOWN";
        if(c.food()>=Math.ceil(c.dailyMeals()*SettlementPolicy.LOW_DAYS)&&c.usableBeds()>=c.living())return "NO_HOUSEHOLD_NEED";
        if(c.living()==0||home.members().size()>3)return "FIRST_VERSION_THREE_BED_CAPACITY";
        var t=new CompoundTag();t.putString("Id","development-move:"+home.id());t.putUUID("Household",home.id());
        t.put("Source",NaturalVillageLifeData.get(s).village(from.buildingKey()));t.put("Village",row.getCompound("Village").copy());
        t.putUUID("Applicant",head);t.putLong("Applied",now);t.putInt("SourceFood",c.food());t.putInt("SourceBeds",c.usableBeds());
        t.putUUID("OriginalSettlement",home.settlementId());t.putLong("OriginalStorage",home.sharedStorage().asLong());
        var people=new ListTag();var l=level(s,from);
        for(var m:home.members())if(alive(s,m.characterId())){
            var r=NpcLifeData.get(s).record(m.characterId());if(r==null||r.authority()!=NpcWorkAuthority.INSTITUTION)return "SOURCE_WORK_NOT_READY";
            NpcBase body=null;for(var n:l.getEntitiesOfClass(NpcBase.class,new net.minecraft.world.phys.AABB(from.buildingOrigin()).inflate(128)))if(n.getCharacterId().equals(m.characterId()))body=n;
            if(body==null)return "SOURCE_PERSON_UNLOADED";
            var person=new CompoundTag();person.putUUID("Person",m.characterId());person.putUUID("Body",body.getUUID());people.add(person);
        }
        t.put("People",people);applications.put(home.id(),t);state(t,"INTENT","家庭依据实际食物或住房缺口提出意向，尚未出发或入住",now);return "INTENT_RECORDED";
    }
    private void enroll(MinecraftServer s,long now){
        var life=NaturalVillageLifeData.existing(s);if(life==null)return;
        for(var v:life.villages()){
            var b=NaturalVillageLifeData.decode(v);if(!v.getBoolean("Ready")||!SettlementDevelopmentLayout.supports(b)||sites.containsKey(b.buildingKey())||!SettlementCoordinatorData.active(s,b.buildingKey()))continue;
            var row=new CompoundTag();row.put("Village",v.copy());row.putString("Id",b.buildingKey());row.putLong("Activated",now);row.put("Projects",new ListTag());
            row.putLong("Cycle",SettlementCapacity.REFERENCE_CYCLE);row.putString("State","OBSERVING");
            row.putUUID("FoodConsentBy",NaturalVillageLifeData.person(b,0));row.putString("FoodConsentRule","v7 founding owner consent;surplus-only;seed-and-home-protection");
            sites.put(b.buildingKey(),row);
            var l=level(s,b);if(l.getEntity(NaturalVillageLifeData.body(b,2)) instanceof NpcBase worker){
                var a=NaturalVillageLayout.at(b,new BlockPos(18,1,15));var z=NaturalVillageLayout.at(b,new BlockPos(24,3,38));
                worker.setWorkZone(new BlockPos(Math.min(a.getX(),z.getX()),1+ b.buildingOrigin().getY(),Math.min(a.getZ(),z.getZ())),
                        new BlockPos(Math.max(a.getX(),z.getX()),3+b.buildingOrigin().getY(),Math.max(a.getZ(),z.getZ())));
            }setDirty();
        }
    }
    private List<CompoundTag> projects(CompoundTag row){return row.getList("Projects",Tag.TAG_COMPOUND).stream().map(x->(CompoundTag)x).toList();}
    private CompoundTag current(CompoundTag row){return projects(row).stream().filter(p->!Set.of("STABLE","CANCELLED").contains(p.getString("State"))).findFirst().orElse(null);}
    private static boolean usableTool(MinecraftServer s,NpcBase n,Item tool){
        var rights=RightsStateData.get(s);var bag=ContainerIds.npcPersonal(n.getCharacterId());
        for(int i=0;i<n.getPersonalInventory().getContainerSize();i++){
            var stack=n.getPersonalInventory().getItem(i);if(!stack.is(tool)||stack.isDamageableItem()&&stack.getDamageValue()>=stack.getMaxDamage())continue;
            if(rights.context(bag)==null||new RightsService(rights).can(SubjectRef.character(n.getCharacterId()),AssetRight.USE,bag,i,ItemFingerprint.of(stack),s.overworld().getGameTime()).status()==RightsVerdict.Status.ALLOW)return true;
        }
        return false;
    }
    private boolean productionReady(MinecraftServer s,NaturalVillageBuilding b,CompoundTag a){
        boolean retainedFarmer=false,builder=false,incomingFarmer=false;
        var l=level(s,b);
        for(var home:HouseholdData.get(s).households())if(Objects.equals(home.settlementId(),b.settlementId()))for(var member:home.members()){
            var hint=CharacterRegistryData.get(s).bodyOf(member.characterId());
            if(hint==null||!(l.getEntity(hint.entityId()) instanceof NpcBase n)||!member.characterId().equals(n.getCharacterId())
                    ||!n.isAlive()||n.isBaby()||n.isNoAi()||n.getCommand()!=NpcCommand.WORK||n.getTarget()!=null)continue;
            var record=NpcLifeData.get(s).record(n.getCharacterId());
            if(record==null||record.authority()!=NpcWorkAuthority.INSTITUTION||!Objects.equals(record.activeWorkplaceSettlementId(),b.settlementId()))continue;
            retainedFarmer|=n.getProfession()==NpcProfession.FARMER&&usableTool(s,n,Items.IRON_HOE);
            builder|=Set.of(NpcProfession.WORKER,NpcProfession.COURIER).contains(n.getProfession());
        }
        for(var entry:a.getList("People",Tag.TAG_COMPOUND)){
            var who=(CompoundTag)entry;
            if(l.getEntity(who.getUUID("Body")) instanceof NpcBase n&&who.getUUID("Person").equals(n.getCharacterId())&&n.isAlive()&&!n.isBaby()&&!n.isNoAi()
                    &&n.getProfession()==NpcProfession.FARMER&&usableTool(s,n,Items.IRON_HOE))incomingFarmer=true;
        }
        return retainedFarmer&&builder&&incomingFarmer;
    }
    private boolean plotAvailable(MinecraftServer s,CompoundTag row,int lot){
        var b=village(row);var l=level(s,b);var protectedEdits=row.getCompound("ProtectedEdits");
        for(var piece:SettlementDevelopmentLayout.house(b,lot))for(var p:piece.positions())
            if(!loaded(l,p)||!l.getBlockState(p).isAir()||protectedEdits.contains(Long.toString(p.asLong()))
                    ||protectedEdits.contains(Long.toString(p.below().asLong())))return false;
        return true;
    }
    private void protectEdit(ServerLevel l,BlockPos at){
        for(var row:sites.values()){
            var b=village(row);if(!b.dimension().equals(l.dimension().location().toString()))continue;
            var p=local(b,at);boolean declared=false;
            for(int lot=0;lot<2;lot++){
                var base=SettlementDevelopmentLayout.localLot(lot);
                declared|=p.getY()>=0&&p.getY()<=4&&rectangle(p,base.getX(),base.getX()+SettlementDevelopmentLayout.width(b)-1,base.getZ()-1,base.getZ()+4);
                declared|=SettlementDevelopmentLayout.field(b,lot,at);
            }
            if(declared){var edits=row.getCompound("ProtectedEdits");edits.putLong(Long.toString(at.asLong()),l.getGameTime());row.put("ProtectedEdits",edits);setDirty();}
        }
    }
    @SubscribeEvent public static void playerPlaced(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent e){
        if(e.getEntity() instanceof net.minecraft.world.entity.player.Player&&e.getLevel() instanceof ServerLevel l){var d=existing(l.getServer());if(d!=null)d.protectEdit(l,e.getPos());}
    }
    @SubscribeEvent public static void playerBroke(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent e){
        if(e.getLevel() instanceof ServerLevel l){var d=existing(l.getServer());if(d!=null)d.protectEdit(l,e.getPos());}
    }
    private void consider(MinecraftServer s,CompoundTag row,long now){
        var b=village(row);if(row.getBoolean("Review")||current(row)!=null||projects(row).size()>=2)return;
        var prior=SettlementCoordinatorData.existing(s);var original=prior==null?null:prior.site(b.buildingKey());
        if(original==null||!original.getCompound("Jobs").getCompound("HOUSE").getString("State").equals("DONE"))return;
        var holder=PoliticalStateData.get(s).titleHolder(b.settlementId());if(!NaturalPolityData.get(s).authorized(s,b,holder))return;
        var homes=HouseholdData.get(s).households().stream().filter(h->Objects.equals(h.settlementId(),b.settlementId())).toList();
        if(homes.stream().anyMatch(h->!capacity(s,h).stableFloor())){row.putString("Reason","先保证每个已有家庭的实际口粮和床位");return;}
        var a=applications.values().stream().filter(x->village(x).buildingKey().equals(b.buildingKey())&&x.getString("State").equals("INTENT")).sorted(Comparator.comparingLong(x->x.getLong("Applied"))).findFirst().orElse(null);
        if(a==null)return;var founder=HouseholdData.get(s).byId(NaturalVillageLifeData.home(b));
        if(!capacity(s,founder).canSupport(a.getList("People",Tag.TAG_COMPOUND).size(),2)){row.putString("Reason","缺少合法过渡供给余量");return;}
        if(!productionReady(s,b,a)){row.putString("Reason","缺少保留农业、合法锄具、可分配非农劳动或迁入后的合格农人；不盲目开工");return;}
        int lot=projects(row).size();
        if(!plotAvailable(s,row,lot)){row.putString("Reason","已声明住宅地块未加载、有实际障碍或玩家编辑；不拆占或免费换地");return;}
        var p=new CompoundTag();p.put("Village",row.getCompound("Village").copy());p.putString("Id",b.buildingKey()+":development:"+lot);
        p.putString("ProjectKey",p.getString("Id"));p.putInt("Lot",lot);p.putUUID("Household",a.getUUID("Household"));p.putUUID("ApprovedBy",holder);p.putLong("Created",now);
        p.put("Jobs",new CompoundTag());p.putString("State","BUILDING");p.putString("Reason","先建设验收真实三床住宅，再确认出发");
        p.put("Parcel",original.getCompound("Parcel").copy());p.put("ObservedResources",original.getCompound("ObservedResources").copy());p.put("ProtectedEdits",original.getCompound("ProtectedEdits").copy());
        row.getList("Projects",Tag.TAG_COMPOUND).add(p);a.putInt("Lot",lot);a.putString("Project",p.getString("Id"));state(a,"BUILDING","迁居意向获扩容安排，未记入住",now);
        var farmer=level(s,b).getEntity(NaturalVillageLifeData.body(b,0));if(farmer instanceof NpcBase n){
            var fields=SettlementDevelopmentLayout.fields(b,lot);var min=NaturalVillageLayout.at(b,new BlockPos(14,1,4));var max=fields.getLast();
            n.setWorkZone(new BlockPos(Math.min(min.getX(),max.getX()),min.getY(),Math.min(min.getZ(),max.getZ())),
                    new BlockPos(Math.max(min.getX(),max.getX()),max.getY(),Math.max(min.getZ(),max.getZ())));
        }setDirty();
    }
    private CompoundTag projectFor(CompoundTag row,String id){return projects(row).stream().filter(p->p.getString("Id").equals(id)).findFirst().orElse(null);}
    private CompoundTag task(CompoundTag p,String kind){
        var jobs=p.getCompound("Jobs");if(!jobs.contains(kind)){
            var t=new CompoundTag();t.put("Village",p.getCompound("Village").copy());t.putString("ProjectKey",p.getString("Id")+":"+kind);t.putString("Id",t.getString("ProjectKey"));
            t.putString("Kind",kind);t.putString("State","WAITING");jobs.put(kind,t);p.put("Jobs",jobs);setDirty();
        }return jobs.getCompound(kind);
    }
    private boolean eligible(NpcBase n,CompoundTag row){
        var b=village(row);var h=HouseholdData.get(n.getServer()).forMember(n.getCharacterId());var life=NpcLifeData.get(n.getServer()).record(n.getCharacterId());
        return n.isAlive()&&n.getTarget()==null&&n.getCommand()==NpcCommand.WORK&&NpcSchedulePlanner.allowsWork(n)&&life!=null&&life.authority()==NpcWorkAuthority.INSTITUTION
                &&h!=null&&Objects.equals(h.settlementId(),b.settlementId())&&b.settlementId().equals(NpcWorkContext.from(n).workplaceSettlementId())
                &&NaturalPolityData.get(n.getServer()).authorized(n.getServer(),b,PoliticalStateData.get(n.getServer()).titleHolder(b.settlementId()))&&!NaturalPolityData.paused(n.getServer(),n.getCharacterId());
    }
    public Optional<NpcPlan> choose(NpcBase n,long now){
        var row=forPerson(n.getServer(),n.getCharacterId());if(row==null||row.getBoolean("Review")||!eligible(n,row)||moving(n.getServer(),n.getCharacterId()))return Optional.empty();
        if(n.getProfession()!=NpcProfession.WORKER&&n.getProfession()!=NpcProfession.COURIER)return Optional.empty();
        var p=current(row);if(p==null||!Set.of("BUILDING","READY","CANCEL_PENDING").contains(p.getString("State")))return Optional.empty();
        if(!p.getString("State").equals("CANCEL_PENDING")&&!capacity(n.getServer(),HouseholdData.get(n.getServer()).forMember(n.getCharacterId())).stableFloor())return Optional.empty();
        if(n.getProfession()==NpcProfession.WORKER&&materialDeficit(n.getServer(),village(row))>0&&SettlementResourceSurvey.needsSurvey(n,p)){
            var mine=SettlementResourceSurvey.mining(n,p,now);if(mine.isPresent())return mine;
            var survey=task(p,"SURVEY");if(!survey.hasUUID("Person")||survey.getUUID("Person").equals(n.getCharacterId())){
                var plan=SettlementCoordinatorTask.developmentPlan(n,p.getString("Id"),"SURVEY",now);
                survey.putUUID("Person",n.getCharacterId());survey.putUUID("Body",n.getUUID());survey.putUUID("Plan",plan.id());setDirty();return Optional.of(plan);
            }
        }
        String kind=next(n,row,p);if(kind==null)return Optional.empty();var t=task(p,kind);if(t.getBoolean("Review")||now<t.getLong("RetryAt"))return Optional.empty();
        if(t.hasUUID("Person")&&!t.getUUID("Person").equals(n.getCharacterId())){
            var old=HouseholdData.get(n.getServer()).forMember(t.getUUID("Person"));
            if(alive(n.getServer(),t.getUUID("Person"))&&old!=null&&Objects.equals(old.settlementId(),village(row).settlementId()))return Optional.empty();
            if(t.getBoolean("MaterialCarried")||t.getBoolean("Cargo")){t.putString("Reason","原责任人携货待核对，不重复发料");return Optional.empty();}
        }
        var plan=SettlementCoordinatorTask.developmentPlan(n,p.getString("Id"),kind,now);
        t.putUUID("Person",n.getCharacterId());t.putUUID("Body",n.getUUID());t.putUUID("Plan",plan.id());t.putString("State","CLAIMED");setDirty();return Optional.of(plan);
    }
    private String next(NpcBase n,CompoundTag row,CompoundTag p){
        var b=village(row);var s=n.getServer();var inv=NaturalPolityData.inventory(s,b,3);
        for(var e:p.getCompound("Jobs").getAllKeys()){
            var t=p.getCompound("Jobs").getCompound(e);
            if(t.hasUUID("Person")&&t.getUUID("Person").equals(n.getCharacterId())&&(t.getBoolean("MaterialCarried")||t.getBoolean("Cargo")))return e;
        }
        if(p.getString("State").equals("CANCEL_PENDING"))return null;
        if(p.getString("State").equals("READY")){
            var a=applications.get(p.getUUID("Household"));
            int target=(int)Math.ceil(a.getList("People",Tag.TAG_COMPOUND).size()*SettlementPolicy.DAILY_MEALS_PER_PERSON*2);
            return !a.contains("Departed")&&n.getProfession()==NpcProfession.COURIER&&free(n,p,"FOOD")
                    &&count(box(level(s,b),SettlementDevelopmentLayout.pantry(b,p.getInt("Lot"))),Items.CARROT)<target?"FOOD":null;
        }
        for(var e:p.getCompound("Jobs").getAllKeys()){
            var t=p.getCompound("Jobs").getCompound(e);if(t.hasUUID("CraftWorker")&&free(n,p,e))return e;
        }
        var build=task(p,"BUILD");int index=build.getInt("Placed");
        var pieces=SettlementDevelopmentLayout.house(b,p.getInt("Lot"));
        Item held=build.getBoolean("MaterialCarried")&&index<pieces.size()?pieces.get(index).material():Items.AIR;
        int beds=count(inv,Items.WHITE_BED)+(held==Items.WHITE_BED?1:0),planks=count(inv,Items.OAK_PLANKS);
        int stone=SettlementDevelopmentLayout.stoneCost(b);
        int needBeds=Math.max(0,stone+4-Math.max(stone+1,index));
        if(index>=SettlementDevelopmentLayout.house(b,p.getInt("Lot")).size())return "VERIFY";
        if(free(n,p,"BUILD")&&(index<stone&&count(inv,Items.COBBLESTONE)>0||index==stone&&count(inv,Items.OAK_DOOR)>0
                ||index>=stone+1&&index<=stone+3&&beds>0||index==stone+4&&count(inv,Items.BARREL)>0))return "BUILD";
        if(free(n,p,"BED")&&planks>=3&&beds<needBeds&&count(inv,Items.WHITE_WOOL)>=3)return "BED";
        boolean needDoor=index<=stone&&held!=Items.OAK_DOOR&&count(inv,Items.OAK_DOOR)==0;
        boolean needBarrel=index<=stone+4&&held!=Items.BARREL&&count(inv,Items.BARREL)==0;
        if(free(n,p,"DOOR")&&needDoor&&planks>=6)return "DOOR";
        if(free(n,p,"SLABS")&&needBarrel&&planks>=3&&count(inv,Items.OAK_SLAB)<2)return "SLABS";
        if(free(n,p,"BARREL")&&needBarrel&&planks>=6&&count(inv,Items.OAK_SLAB)>=2)return "BARREL";
        int requiredPlanks=Math.max(0,needBeds-beds)*3+(needDoor?6:0)
                +(needBarrel?6+(count(inv,Items.OAK_SLAB)<2?3:0):0);
        if(free(n,p,"PLANKS")&&count(inv,Items.OAK_LOG)>=3&&planks<requiredPlanks)return "PLANKS";
        if(free(n,p,"STONE")&&SettlementCoordinatorData.available(s,b,1,Items.COBBLESTONE)+SettlementCoordinatorData.available(s,b,2,Items.COBBLESTONE)>0
                &&stone-index>count(inv,Items.COBBLESTONE)+(task(p,"BUILD").getBoolean("MaterialCarried")?Math.max(1,task(p,"BUILD").getInt("MaterialsHeld")):0))return "STONE";
        if(free(n,p,"LOGS")&&requiredPlanks>planks&&SettlementCoordinatorData.available(s,b,1,Items.OAK_LOG)+SettlementCoordinatorData.available(s,b,2,Items.OAK_LOG)>=3&&count(inv,Items.OAK_LOG)<6)return "LOGS";
        p.putString("Reason","缺合法材料或部件，等待真实生产运输；其他工作不锁死");return null;
    }
    private boolean free(NpcBase n,CompoundTag p,String kind){
        var t=task(p,kind);return !t.getBoolean("Review")&&n.level().getGameTime()>=t.getLong("RetryAt")
                &&(!t.hasUUID("Person")||t.getUUID("Person").equals(n.getCharacterId()));
    }
    public String work(NpcBase n,String project,String kind,UUID plan,long now){
        var row=forPerson(n.getServer(),n.getCharacterId());var p=row==null?null:projectFor(row,project);if(p==null||!eligible(n,row))return "YIELD";
        var t=task(p,kind);if(!t.hasUUID("Plan")||!t.getUUID("Plan").equals(plan)||!t.getUUID("Person").equals(n.getCharacterId())||t.getLong("LastTick")==now)return "YIELD";
        if(!p.getString("State").equals("CANCEL_PENDING")&&!NaturalPolityData.get(n.getServer()).authorized(n.getServer(),village(row),p.getUUID("ApprovedBy"))){t.putString("Reason","原工程批准权失效，保留已领材料和原责任");setDirty();return "YIELD";}
        t.putLong("LastTick",now);t.putString("State","WORKING");
        int oldWork=t.getInt("Work"),oldPlaced=t.getInt("Placed"),oldCycle=t.getInt("Cycle");String oldTransfer=t.hasUUID("LastTransfer")?t.getUUID("LastTransfer").toString():"";
        setDirty();String result;
        if(p.getString("State").equals("CANCEL_PENDING"))result=returnUnspent(n,row,t,kind,now);
        else if(kind.equals("BUILD")){
            t.putBoolean("Development",true);t.putInt("Lot",p.getInt("Lot"));
            var pieces=SettlementDevelopmentLayout.house(village(row),p.getInt("Lot"));int index=t.getInt("Placed");
            var edits=row.getCompound("ProtectedEdits");
            if(index<pieces.size()&&pieces.get(index).positions().stream().anyMatch(at->edits.contains(Long.toString(at.asLong()))||edits.contains(Long.toString(at.below().asLong()))))
                result="原工地有玩家编辑，保留材料等待权利核对，不自动拆建";
            else result=index>=pieces.size()?"YIELD":SettlementGrowthData.get(n.getServer()).place(n,t,pieces.get(index),index);
        }else if(kind.equals("VERIFY"))result=verify(n.getServer(),row,p,now)?"YIELD":"施工实物不完整";
        else if(kind.equals("SURVEY"))result=SettlementResourceSurvey.work(n,p,t,now);
        else if(kind.equals("FOOD"))result=transitionFood(n,row,p,t,now);
        else if(kind.equals("LOGS")||kind.equals("STONE"))result=material(n,row,p,t,kind,now);
        else result=craft(n,row,t,kind,now);
        if(!result.equals("YIELD"))t.putString("Reason",result);t.putLong("LastAttempt",now);
        if(t.getInt("Work")!=oldWork||t.getInt("Placed")!=oldPlaced||t.getInt("Cycle")!=oldCycle
                ||t.hasUUID("LastTransfer")&&!t.getUUID("LastTransfer").toString().equals(oldTransfer))t.putLong("LastProgress",now);
        setDirty();return result;
    }
    public void yield(NpcBase n,String project,String kind,UUID plan,long now){
        this.yield(n,project,kind,plan,now,false);
    }
    public void yield(NpcBase n,String project,String kind,UUID plan,long now,boolean blocked){
        var row=forPerson(n.getServer(),n.getCharacterId());var p=row==null?null:projectFor(row,project);if(p==null)return;
        var t=task(p,kind);if(!t.hasUUID("Plan")||!t.getUUID("Plan").equals(plan))return;t.putString("State","WAITING");
        t.putLong("RetryAt",blocked?now+SettlementCapacity.RECHECK:now);t.putLong("LastYield",now);
        if(!t.getBoolean("MaterialCarried")&&!t.getBoolean("Cargo")){t.remove("Person");t.remove("Body");t.remove("Plan");}setDirty();
    }
    private String material(NpcBase n,CompoundTag row,CompoundTag p,CompoundTag t,String kind,long now){
        var b=village(row);var growth=SettlementGrowthData.get(n.getServer());Item type=kind.equals("LOGS")?Items.OAK_LOG:Items.COBBLESTONE;
        var character=SubjectRef.character(n.getCharacterId());UUID personal=ContainerIds.npcPersonal(n.getCharacterId());
        RightsStateData.get(n.getServer()).ensureContext(personal,"npc:"+n.getCharacterId()+":personal",character,character,AccessPolicy.PRIVATE);
        if(!t.getBoolean("Cargo")){
            int sourceIndex=SettlementCoordinatorData.available(n.getServer(),b,1,type)>0?1:2;
            if(!growth.near(n,NaturalVillageLayout.at(b,NaturalVillageLayout.stores(b).get(sourceIndex))))return "实际前往合法材料来源";
            int needed=type==Items.OAK_LOG?3:Math.min(16,SettlementDevelopmentLayout.stoneCost(b)-task(p,"BUILD").getInt("Placed")-count(NaturalPolityData.inventory(n.getServer(),b,3),type));
            int q=Math.min(needed,SettlementCoordinatorData.available(n.getServer(),b,sourceIndex,type));if(q<=0)return "YIELD";
            UUID from=NaturalVillageEconomyData.storeId(b,sourceIndex),txn=ContainerIds.named(t.getString("Id")+":pick:"+t.getInt("Cycle"));
            if(!growth.transfer(n,t,txn,SubjectRef.household(NaturalVillageLifeData.home(b)),TransferIntent.GIFT,List.of(new TransferLeg(from,personal,item(type),q)),
                    Map.of(from,NaturalPolityData.inventory(n.getServer(),b,sourceIndex),personal,n.getPersonalInventory())))return "YIELD";
            t.putInt("Quantity",q);t.putBoolean("Cargo",true);t.putUUID("ReturnContainer",from);
            t.putLong("ReturnPosition",NaturalVillageLayout.at(b,NaturalVillageLayout.stores(b).get(sourceIndex)).asLong());
            t.put("ReturnOwner",RightsStateData.get(n.getServer()).context(from).defaultBeneficialOwner().save());
            t.putString("CarriedItem",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(type).toString());
            setDirty();return "实际材料已进入原人背包";
        }
        if(!growth.near(n,NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC)))return "携原材料前往公共仓";
        UUID to=NaturalVillageEconomyData.storeId(b,3),txn=ContainerIds.named(t.getString("Id")+":drop:"+t.getInt("Cycle"));
        if(!growth.transfer(n,t,txn,character,TransferIntent.GIFT,List.of(new TransferLeg(personal,to,item(type),t.getInt("Quantity"))),
                Map.of(personal,n.getPersonalInventory(),to,NaturalPolityData.inventory(n.getServer(),b,3))))return "YIELD";
        t.remove("Cargo");t.putInt("Cycle",t.getInt("Cycle")+1);setDirty();return "YIELD";
    }
    /** A specific approved future home's stock, carried by an existing consenting courier.
     * Uses the original transfer service, not a stock counter or a remote teleport. */
    private String transitionFood(NpcBase n,CompoundTag row,CompoundTag p,CompoundTag t,long now){
        var b=village(row);var a=applications.get(p.getUUID("Household"));var s=n.getServer();var growth=SettlementGrowthData.get(s);
        if(a==null||a.contains("Departed")||!NaturalPolityData.get(s).authorized(s,b,p.getUUID("ApprovedBy")))return "YIELD";
        var pantry=SettlementDevelopmentLayout.pantry(b,p.getInt("Lot"));var dest=box(level(s,b),pantry);
        if(dest==null)return "未来住户的实物粮仓不可用，保留货物等待";
        var principal=SubjectRef.household(a.getUUID("Household"));var person=SubjectRef.character(n.getCharacterId());
        var bag=ContainerIds.npcPersonal(n.getCharacterId());var rights=RightsStateData.get(s);
        rights.ensureContext(bag,"npc:"+n.getCharacterId()+":personal",person,person,AccessPolicy.PRIVATE);
        int cycle=t.getInt("Cycle");
        if(!t.getBoolean("Cargo")){
            var from=NaturalVillageEconomyData.storeId(b,0);var point=SupplyFlowData.get(s).point(from);
            int target=(int)Math.ceil(a.getList("People",Tag.TAG_COMPOUND).size()*SettlementPolicy.DAILY_MEALS_PER_PERSON*2);
            int q=Math.min(target-count(dest,Items.CARROT),SupplyFlowService.available(s,point,principal,item(Items.CARROT),now));
            if(q<=0)return "等待真实余粮，保护原家庭口粮、种子与已承诺物资";
            if(!growth.near(n,point.position()))return "原运输员前往已获授权的实际余粮";
            if(!growth.transfer(n,t,ContainerIds.named(t.getString("Id")+":pick:"+cycle),principal,TransferIntent.GIFT,
                    List.of(new TransferLeg(from,bag,item(Items.CARROT),q)),Map.of(from,SupplyFlowService.container(s,point),bag,n.getPersonalInventory())))return "YIELD";
            t.putInt("Quantity",q);t.putBoolean("Cargo",true);t.putUUID("ReturnContainer",from);t.putLong("ReturnPosition",point.position().asLong());
            t.put("ReturnOwner",point.owner().save());t.putString("CarriedItem","minecraft:carrot");
            setDirty();return "原运输员携带真实过渡口粮";
        }
        if(!growth.near(n,pantry))return "携原口粮前往已批准新户的真实粮仓";
        var to=pantryId(b.dimension(),pantry);
        if(!growth.transfer(n,t,ContainerIds.named(t.getString("Id")+":drop:"+cycle),person,TransferIntent.GIFT,
                List.of(new TransferLeg(bag,to,item(Items.CARROT),t.getInt("Quantity"))),Map.of(bag,n.getPersonalInventory(),to,dest)))return "YIELD";
        t.remove("Cargo");t.putInt("Cycle",cycle+1);setDirty();return "YIELD";
    }
    /** Voluntary withdrawal before departure; existing property and completed work remain real. */
    public String withdraw(MinecraftServer s,UUID household,UUID person,long now){
        var home=HouseholdData.get(s).byId(household);var a=applications.get(household);
        if(home==null||a==null||!home.headCharacterId().equals(person)||!alive(s,person))return "UNAUTHORIZED";
        if(Set.of("WITHDRAWING","WITHDRAWN").contains(a.getString("State")))return a.getString("State");
        if(a.contains("Departed"))return "ALREADY_DEPARTED_KEEP_ORIGINAL_ROUTE";
        var row=sites.get(village(a).buildingKey());var p=a.contains("Project")?projectFor(row,a.getString("Project")):null;
        if(p==null){state(a,"WITHDRAWN","原家庭撤回未出发意向，没有迁移人口或返还虚构物资",now);return "WITHDRAWN";}
        state(a,"WITHDRAWING","原家庭在出发前撤回；停止新投入，核对原携料、已交付财产与施工实物",now);
        state(p,"CANCEL_PENDING","保留已施工部分和实际劳动；只退回原人尚未消耗的实物",now);
        RightsStateData.get(s).revoke(ContainerIds.named(p.getString("Id")+":transition-food"));
        for(var kind:p.getCompound("Jobs").getAllKeys()){
            var t=p.getCompound("Jobs").getCompound(kind);
            if(t.getBoolean("Submitting")){t.putBoolean("Review",true);t.putString("Reason","提交中的原事务必须先核对回执，不能取消成免费退款");continue;}
            if(t.hasUUID("CraftWorker")){
                ProductionService.cancel(s,ContainerIds.named(t.getString("Id")+":batch:"+t.getInt("Cycle")),now,"受益家庭撤回；只释放该批尚未消费的原料预留");
                t.remove("CraftWorker");t.putString("State","CANCELLED");
            }
            t.putLong("RetryAt",now);
        }
        finishWithdrawal(s,row,p,a,now);setDirty();return a.getString("State");
    }
    private String returnUnspent(NpcBase n,CompoundTag row,CompoundTag t,String kind,long now){
        if(!t.getBoolean("MaterialCarried")&&!t.getBoolean("Cargo"))return "YIELD";
        if(!t.hasUUID("ReturnContainer")||!t.contains("ReturnPosition")||!t.contains("ReturnOwner")||!t.contains("CarriedItem")){
            t.putBoolean("Review",true);return "旧携料缺少可核实来源，不猜测返还或释放原库存";
        }
        var b=village(row);var at=BlockPos.of(t.getLong("ReturnPosition"));var inv=box(level(n.getServer(),b),at);
        var to=t.getUUID("ReturnContainer");var expected=SubjectRef.load(t.getCompound("ReturnOwner"));var rights=RightsStateData.get(n.getServer());
        if(inv==null||rights.context(to)==null||!rights.context(to).defaultBeneficialOwner().equals(expected))return "原材料来源未加载或产权变化，保留原人原物等待";
        var growth=SettlementGrowthData.get(n.getServer());if(!growth.near(n,at))return "原携料责任人实际返回已核实的原来源";
        var material=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(t.getString("CarriedItem")));
        int quantity=t.getBoolean("MaterialCarried")?Math.max(1,t.getInt("MaterialsHeld")):t.getInt("Quantity");
        var bag=ContainerIds.npcPersonal(n.getCharacterId());var actor=kind.equals("BUILD")?SubjectRef.institution(b.settlementId()):SubjectRef.character(n.getCharacterId());
        if(!growth.transfer(n,t,ContainerIds.named(t.getString("Id")+":return:"+t.getInt("Cycle")+":"+t.getInt("Placed")),actor,
                kind.equals("BUILD")?TransferIntent.DELIVERY:TransferIntent.GIFT,List.of(new TransferLeg(bag,to,item(material),quantity)),
                Map.of(bag,n.getPersonalInventory(),to,inv)))return "YIELD";
        t.putInt("ReturnedQuantity",quantity);t.putUUID("ReturnReceipt",t.getUUID("LastTransfer"));
        t.remove("Cargo");t.remove("MaterialCarried");t.remove("MaterialsHeld");t.putString("State","CANCELLED");setDirty();return "YIELD";
    }
    private void finishWithdrawal(MinecraftServer s,CompoundTag row,CompoundTag p,CompoundTag a,long now){
        if(p.getCompound("Jobs").getAllKeys().stream().anyMatch(k->{var t=p.getCompound("Jobs").getCompound(k);return t.getBoolean("MaterialCarried")||t.getBoolean("Cargo")||t.getBoolean("Review")||t.getBoolean("Submitting");}))return;
        for(var k:p.getCompound("Jobs").getAllKeys()){var t=p.getCompound("Jobs").getCompound(k);t.putString("State","CANCELLED");t.putLong("Cancelled",now);}
        state(p,"CANCELLED","原未用携料已按回执返回；已施工部分、已耗材料、劳动及受益家庭已收财产均保留",now);
        state(a,"WITHDRAWN","原家庭未迁出；没有复生、搬走或自动收回已赠与的粮食",now);
    }
    private String craft(NpcBase n,CompoundTag row,CompoundTag t,String kind,long now){
        var b=village(row);var growth=SettlementGrowthData.get(n.getServer());if(!growth.near(n,NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC)))return "实际前往原公共制作点";
        var inv=NaturalPolityData.inventory(n.getServer(),b,3);if(inv==null)return "YIELD";
        List<ProductionMaterial> inputs=switch(kind){
            case "PLANKS"->List.of(new ProductionMaterial(item(Items.OAK_LOG),3));
            case "BED"->List.of(new ProductionMaterial(item(Items.OAK_PLANKS),3),new ProductionMaterial(item(Items.WHITE_WOOL),3));
            case "DOOR"->List.of(new ProductionMaterial(item(Items.OAK_PLANKS),6));
            case "SLABS"->List.of(new ProductionMaterial(item(Items.OAK_PLANKS),3));
            case "BARREL"->List.of(new ProductionMaterial(item(Items.OAK_PLANKS),6),new ProductionMaterial(item(Items.OAK_SLAB),2));
            default->throw new IllegalArgumentException(kind);
        };
        var output=switch(kind){case "PLANKS"->new ProductionMaterial(item(Items.OAK_PLANKS),12);case "BED"->new ProductionMaterial(item(Items.WHITE_BED),1);
            case "DOOR"->new ProductionMaterial(item(Items.OAK_DOOR),3);case "SLABS"->new ProductionMaterial(item(Items.OAK_SLAB),6);default->new ProductionMaterial(item(Items.BARREL),1);};
        int required=kind.equals("BED")?400:200;UUID batch=ContainerIds.named(t.getString("Id")+":batch:"+t.getInt("Cycle"));
        try{
            var owner=SubjectRef.institution(b.settlementId());var id=NaturalVillageEconomyData.storeId(b,3);
            var worker=t.hasUUID("CraftWorker")?t.getUUID("CraftWorker"):n.getCharacterId();
            ProductionService.begin(n.getServer(),batch,worker,owner,id,inv,"sagadyssey:development_"+kind.toLowerCase(),inputs,output,required,now);
            t.putUUID("CraftWorker",worker);
            int work=Math.min(required,t.getInt("Work")+1);t.putInt("Work",work);t.putInt("Labor",t.getInt("Labor")+1);setDirty();if(work<required)return "实际制作，原料由原生产账本预留";
            if(!ProductionService.canAcceptOutput(n.getServer(),id,inv,owner,output,now))return "YIELD";
            t.putBoolean("Submitting",true);setDirty();ProductionService.complete(n.getServer(),batch,inv,work,now);t.remove("Submitting");
            t.putInt("Cycle",t.getInt("Cycle")+1);t.putInt("Work",0);t.remove("CraftWorker");setDirty();return "YIELD";
        }catch(RuntimeException ex){
            if(t.getBoolean("Submitting"))t.putBoolean("Review",true);
            else if(ProductionBatchData.get(n.getServer()).batch(batch)==null&&t.getInt("Work")==0)t.remove("CraftWorker");
            t.putString("Reason",ex.getMessage()==null?"制作条件不足":ex.getMessage());setDirty();return "YIELD";
        }
    }
    private boolean verify(MinecraftServer s,CompoundTag row,CompoundTag p,long now){
        var b=village(row);var l=level(s,b);for(var piece:SettlementDevelopmentLayout.house(b,p.getInt("Lot")))for(int i=0;i<piece.positions().size();i++){
            var at=piece.positions().get(i);if(!loaded(l,at)||!l.getBlockState(at).is(piece.states().get(i).getBlock()))return false;
        }
        if(!NaturalPolityData.get(s).authorized(s,b,p.getUUID("ApprovedBy")))return false;
        SettlementRegistryData.get(s).registerConstructedBuilding(s,b.dimension(),SettlementDevelopmentLayout.at(b,SettlementDevelopmentLayout.localLot(p.getInt("Lot")).above()),
                SettlementDevelopmentLayout.wide(b)?"sagadyssey:development_three_bed_v2":"sagadyssey:development_three_bed_v1","house","small",b.settlementId(),now);
        p.putLong("Built",now);state(p,"READY","独立三床住宅与实物粮仓建成，尚未入住",now);return true;
    }
    public static boolean travel(NpcBase n){
        var d=existing(n.getServer());var a=d==null?null:d.moveFor(n.getServer(),n.getCharacterId());if(a==null||!moving(n.getServer(),n.getCharacterId()))return false;
        var b=village(a);var dest=SettlementDevelopmentLayout.entrance(b,a.getInt("Lot"));
        if(!n.isAlive()||n.getTarget()!=null||n.getCommand()!=NpcCommand.WORK)return true;
        if(n.blockPosition().distSqr(dest)<16){n.getNavigation().stop();return true;}
        if(n.tickCount%20==0&&!route(n,dest)&&!SettlementSurveyNavigation.move(n,dest))
            d.state(a,"WAIT_DESTINATION","道路或前方区域不可用，保留原位置与携物等待："+SettlementSurveyNavigation.status(n),n.level().getGameTime());
        return true;
    }
    private void prepareDeparture(MinecraftServer s,CompoundTag row,CompoundTag p,CompoundTag a,long now){
        if(a.contains("Departed")||!a.getString("State").equals("BUILDING"))return;
        var b=village(row);var l=level(s,b);var home=HouseholdData.get(s).byId(a.getUUID("Household"));if(home==null||!alive(s,home.headCharacterId()))return;
        var pantry=SettlementDevelopmentLayout.pantry(b,p.getInt("Lot"));var inv=box(l,pantry);if(inv==null)return;
        var principal=SubjectRef.household(home.id());var id=pantryId(b.dimension(),pantry);var rights=RightsStateData.get(s);
        rights.ensureContext(id,b.dimension()+":"+pantry.asLong(),principal,principal,AccessPolicy.HOUSEHOLD);
        var flows=SupplyFlowData.get(s);flows.register(new FieldworkStorage(b.dimension(),pantry,id,principal,AccessPolicy.HOUSEHOLD,TransferIntent.DELIVERY,"development-pantry"));flows.reveal(principal,id);
        var founder=HouseholdData.get(s).byId(NaturalVillageLifeData.home(b));if(founder==null||!founder.headCharacterId().equals(p.getUUID("ApprovedBy"))||!NaturalPolityData.get(s).authorized(s,b,p.getUUID("ApprovedBy")))return;
        UUID source=NaturalVillageEconomyData.storeId(b,0);var farmer=SubjectRef.character(NaturalVillageLifeData.person(b,0));var context=rights.context(source);
        if(context==null||!context.defaultBeneficialOwner().equals(farmer)||!row.hasUUID("FoodConsentBy")||!row.getUUID("FoodConsentBy").equals(farmer.id()))return;
        rights.grant(new RightGrant(ContainerIds.named(p.getString("Id")+":transition-food"),source,principal,Set.of(AssetRight.TRANSFER),
                "v7 原权利人明示同意供应已批准家庭真实余粮，种粮与原家庭生存底线另行保护",now,null,true));flows.reveal(principal,source);
        int target=(int)Math.ceil(a.getList("People",Tag.TAG_COMPOUND).size()*SettlementPolicy.DAILY_MEALS_PER_PERSON*2);
        // Before relocation this is a specific approved construction delivery,
        // not the household's current-address recurring stock policy.
        if(count(inv,Items.CARROT)<target){a.putString("Reason","等待原运输将真实过渡口粮送入新户粮仓");return;}
        for(var field:SettlementDevelopmentLayout.fields(b,p.getInt("Lot")))if(!loaded(l,field)
                ||!l.getBlockState(field.below()).is(net.minecraft.world.level.block.Blocks.FARMLAND)
                ||!l.getBlockState(field).is(net.minecraft.world.level.block.Blocks.CARROTS)){a.putString("Reason","等待原农人真实开垦播种，预计收成不计现有口粮");return;}
        if(!Objects.equals(home.settlementId(),a.getUUID("OriginalSettlement"))||!Objects.equals(home.sharedStorage(),BlockPos.of(a.getLong("OriginalStorage")))){
            state(a,"REVIEW","出发前原住址已变，不能改绑其他现场",now);return;
        }
        for(var x:a.getList("People",Tag.TAG_COMPOUND)){
            var who=(CompoundTag)x;if(!(l.getEntity(who.getUUID("Body")) instanceof NpcBase n)||!n.isAlive()||!n.getCharacterId().equals(who.getUUID("Person"))||n.getTarget()!=null||!n.getTransportInventory().isEmpty())return;
            var record=NpcLifeData.get(s).record(n.getCharacterId());
            if(record==null||record.authority()!=NpcWorkAuthority.INSTITUTION||!Objects.equals(record.activeWorkplaceSettlementId(),a.getUUID("OriginalSettlement"))){
                a.putString("Reason","来源工作安排已变化，不能重复离职或越过其他约定");return;
            }
            String obligation=ResidentWorkMigrationData.obligations(s,n.getCharacterId(),n.getUUID());
            if(obligation!=null){a.putString("Reason",obligation);return;}
            if(SupplyFlowData.get(s).routes().stream().anyMatch(r->!r.closed()&&who.getUUID("Person").equals(r.carrierCharacter()))
                    ||!FieldworkData.get(s).pendingCargo(n.getUUID()).isEmpty()||!FieldworkData.get(s).pendingDelivery(n.getUUID()).isEmpty())return;
        }
        for(var x:a.getList("People",Tag.TAG_COMPOUND)){var who=(CompoundTag)x;var n=(NpcBase)l.getEntity(who.getUUID("Body"));
            NpcBrainRuntime.cancel(n,PlanCancellationReason.AUTHORIZED_COMMAND,"双方确认，原义务检查完成，步行迁居");NpcLifeData.get(s).suspendForMigration(n,now);}
        a.putLong("Departed",now);a.putUUID("AcceptedBy",p.getUUID("ApprovedBy"));state(a,"WALKING","真实住房和过渡粮已就绪，原家庭出发，尚未入住",now);
    }
    private void arrive(MinecraftServer s,CompoundTag row,CompoundTag p,CompoundTag a,long now){
        var b=village(row);var l=level(s,b);var dest=SettlementDevelopmentLayout.entrance(b,p.getInt("Lot"));if(!loaded(l,dest))return;var people=new ArrayList<NpcBase>();
        for(var x:a.getList("People",Tag.TAG_COMPOUND)){var who=(CompoundTag)x;
            if(!alive(s,who.getUUID("Person"))){state(a,"REVIEW","原居民死亡，保留财物义务，不补造人物",now);return;}
            if(!(l.getEntity(who.getUUID("Body")) instanceof NpcBase n)||!n.getCharacterId().equals(who.getUUID("Person"))||n.blockPosition().distSqr(dest)>=16)return;people.add(n);}
        var beds=SettlementDevelopmentLayout.beds(b,p.getInt("Lot"));var pantry=SettlementDevelopmentLayout.pantry(b,p.getInt("Lot"));
        if(box(l,pantry)==null||beds.stream().anyMatch(x->!PublicHousingConditions.validBed(l,x))){state(a,"WAIT_DESTINATION","住处失效，保留原人原物等待修复",now);return;}
        var home=HouseholdData.get(s).byId(a.getUUID("Household"));var from=NaturalVillageLifeData.decode(a.getCompound("Source"));
        for(var n:people){var record=NpcLifeData.get(s).record(n.getCharacterId());
            if(record==null||!(record.authority()==NpcWorkAuthority.NONE||record.authority()==NpcWorkAuthority.INSTITUTION&&Objects.equals(record.activeWorkplaceSettlementId(),b.settlementId()))){
                state(a,"REVIEW","途中工作权威改变，保留原人及旧义务",now);return;
            }
        }
        state(a,"ARRIVING","原人已抵达，比较原住址后提交同一家庭，未完成前不算稳定",now);
        if(!Objects.equals(home.settlementId(),b.settlementId())&&!HouseholdData.get(s).relocate(home.id(),a.getUUID("OriginalSettlement"),BlockPos.of(a.getLong("OriginalStorage")),b.dimension(),b.settlementId(),pantry,pantry)){
            state(a,"REVIEW","原户籍或目标粮仓冲突，保留双方状态",now);return;}
        var life=NpcLifeData.get(s);int i=0;for(var n:people){
            if(life.record(n.getCharacterId()).authority()==NpcWorkAuthority.NONE){
                var fields=SettlementDevelopmentLayout.fields(b,p.getInt("Lot"));var min=n.getProfession()==NpcProfession.FARMER?fields.getFirst():NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC);
                var max=n.getProfession()==NpcProfession.FARMER?fields.getLast():min;life.commitWorkerMigration(n,b.settlementId(),beds.get(i),min,max,now);
            }
            if(!life.assignBed(n.getCharacterId(),b.dimension(),beds.get(i),"正式接纳新家庭住宅",now)){state(a,"REVIEW","床位交接冲突，不复制人物",now);return;}i++;
        }
        SupplyFlowData.get(s).removePolicy("natural-diet:"+home.id());state(a,"ARRIVED","同一家庭抵达，床位和工作正式交接，旧财产留原主",now);
        state(p,"OCCUPIED","已入住，进行逐户生活和生产稳定观察",now);
        a.putLong("Arrived",now);p.putLong("StableTicks",0);p.putLong("LastObserved",now);setDirty();
    }
    /** Copies existing life facts. This observer never feeds, sleeps, binds or creates a person. */
    private void observeHomes(MinecraftServer s,CompoundTag row,long now){
        var b=village(row);var observations=row.getCompound("LifeObservations");var meals=row.getList("ObservedMeals",Tag.TAG_COMPOUND);
        for(var home:HouseholdData.get(s).households())if(Objects.equals(home.settlementId(),b.settlementId()))for(var member:home.members()){
            var person=member.characterId();var needs=NpcLifeData.get(s).needs(person);if(needs==null||!alive(s,person))continue;
            var facts=observations.getCompound(person.toString());
            if(needs.lastAteAt()>facts.getLong("LastMeal")&&needs.lastAteAt()>row.getLong("Activated")){
                if(meals.size()>=8192){row.putBoolean("Review",true);row.putString("Reason","生活证据记录达到上限，保留历史等待归档");continue;}
                var meal=new CompoundTag();meal.putUUID("Person",person);meal.putUUID("Household",home.id());
                meal.putLong("At",needs.lastAteAt());meal.putLong("ObservedAt",now);meal.putString("Source",needs.lastFoodSource());meals.add(meal);
                facts.putLong("LastMeal",needs.lastAteAt());
            }
            var hint=CharacterRegistryData.get(s).bodyOf(person);var l=level(s,b);
            if(hint!=null&&hint.dimensionId().equals(b.dimension())&&l.getEntity(hint.entityId()) instanceof NpcBase n&&person.equals(n.getCharacterId())&&n.isSleeping())
                facts.putLong("LastSleep",now);
            if(needs.hunger()>=800){if(!facts.contains("HungrySince"))facts.putLong("HungrySince",now);}
            else facts.remove("HungrySince");
            facts.putInt("Hunger",needs.hunger());facts.putLong("ObservedAt",now);observations.put(person.toString(),facts);
        }
        row.put("LifeObservations",observations);row.put("ObservedMeals",meals);setDirty();
    }
    /** Three completed, possibly overlapping production pipelines. Each uses a
     * distinct harvest/replant, actual delivery and subsequently observed meal. */
    private int completedFoodCycles(MinecraftServer s,CompoundTag row,CompoundTag a){
        var f=FieldworkData.get(s);long after=a.getLong("Arrived");var b=village(a);
        var meals=row.getList("ObservedMeals",Tag.TAG_COMPOUND).stream().map(x->(CompoundTag)x)
                .filter(x->x.getUUID("Household").equals(a.getUUID("Household"))&&x.getLong("At")>after)
                .sorted(Comparator.comparingLong(x->x.getLong("At"))).toList();
        var usedMeals=new HashSet<String>();var usedDeliveries=new HashSet<UUID>();int count=0;
        for(var personTag:a.getList("People",Tag.TAG_COMPOUND)){
            var who=(CompoundTag)personTag;var receipts=f.receiptsFor(who.getUUID("Body"));var deliveries=f.deliveriesFor(who.getUUID("Body"));
            for(var harvest:receipts){
                if(harvest.committedAt()<=after||harvest.action().operation()!=FieldworkAction.Operation.HARVEST
                        ||!SettlementDevelopmentLayout.field(b,a.getInt("Lot"),harvest.action().target())
                        ||harvest.inventoryDeltas().stream().noneMatch(d->d.fingerprint().equals("minecraft:carrot#0")&&d.quantity()>0))continue;
                var plant=receipts.stream().filter(x->x.action().planId().equals(harvest.action().planId())&&x.action().target().equals(harvest.action().target())
                        &&x.action().operation()==FieldworkAction.Operation.PLANT&&x.committedAt()>harvest.committedAt()).findFirst().orElse(null);
                if(plant==null)continue;
                var destination=pantryId(b.dimension(),SettlementDevelopmentLayout.pantry(b,a.getInt("Lot")));
                var delivery=deliveries.stream().filter(x->x.fingerprint().equals("minecraft:carrot#0")&&x.committedAt()>=plant.committedAt()&&!usedDeliveries.contains(x.transferId()))
                        .filter(x->{var receipt=TransferLedgerData.get(s).receipt(x.transferId());return receipt!=null&&receipt.successful()&&receipt.lawful()&&receipt.finalRevisions().containsKey(destination);}).findFirst().orElse(null);
                if(delivery==null)continue;
                var meal=meals.stream().filter(x->x.getLong("At")>=delivery.committedAt()&&!usedMeals.contains(x.getUUID("Person")+":"+x.getLong("At"))).findFirst().orElse(null);
                if(meal==null)continue;
                usedDeliveries.add(delivery.transferId());usedMeals.add(meal.getUUID("Person")+":"+meal.getLong("At"));if(++count>=3)return count;
            }
        }
        return count;
    }
    private void stability(MinecraftServer s,CompoundTag row,CompoundTag p,CompoundTag a,long now){
        var b=village(row);var homes=HouseholdData.get(s).households().stream().filter(h->Objects.equals(h.settlementId(),b.settlementId())).toList();
        long delta=Math.min(SettlementCapacity.RECHECK,Math.max(0,now-p.getLong("LastObserved")));p.putLong("LastObserved",now);
        if(homes.stream().anyMatch(h->!capacity(s,h).stableFloor())){p.putLong("StableTicks",0);p.putString("Reason","至少一户口粮或床位不足，等待真实恢复");setDirty();return;}
        p.putLong("StableTicks",p.getLong("StableTicks")+delta);
        int cycles=completedFoodCycles(s,row,a);p.putInt("CompletedFoodCycles",cycles);
        boolean lived=homes.stream().flatMap(h->h.members().stream()).filter(m->alive(s,m.characterId())).allMatch(m->{
            var facts=row.getCompound("LifeObservations").getCompound(m.characterId().toString());
            return facts.getLong("LastMeal")>a.getLong("Arrived")&&facts.getLong("LastSleep")>a.getLong("Arrived")
                    &&(!facts.contains("HungrySince")||now-facts.getLong("HungrySince")<=2400);
        });
        if(p.getLong("StableTicks")>=SettlementCapacity.stableWindow(row.getLong("Cycle"))&&cycles>=3&&lived&&!row.getBoolean("Review")){
            state(a,"STABLE","逐户通过生活与劳动观察",now);state(p,"STABLE","本轮完成，保留历史并允许下一轮",now);row.putInt("CompletedRounds",row.getInt("CompletedRounds")+1);
        }setDirty();
    }
    private void progress(MinecraftServer s,CompoundTag row,long now){
        var p=current(row);if(p==null)return;var a=applications.get(p.getUUID("Household"));if(a==null)throw new IllegalStateException("Lost project application");
        if(p.getString("State").equals("CANCEL_PENDING")){finishWithdrawal(s,row,p,a,now);return;}
        if(p.getString("State").equals("READY"))prepareDeparture(s,row,p,a,now);
        if(Set.of("WALKING","WAIT_DESTINATION","ARRIVING").contains(a.getString("State")))arrive(s,row,p,a,now);
        if(a.getString("State").equals("ARRIVED"))stability(s,row,p,a,now);
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        var s=event.getServer();long now=s.overworld().getGameTime();if(now%SettlementCapacity.RECHECK!=0)return;
        var data=existing(s);var life=NaturalVillageLifeData.existing(s);if(life==null)return;
        if(data==null&&life.villages().stream().noneMatch(v->SettlementDevelopmentLayout.supports(NaturalVillageLifeData.decode(v))))return;
        if(data==null)data=get(s);data.enroll(s,now);
        for(var row:data.sites.values()){var b=village(row);if(!loaded(level(s,b),NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC)))continue;
            var economy=NaturalVillageEconomyData.existing(s);var region=economy==null?null:economy.row(b.buildingKey());
            if(region==null||!region.getString("Phase").equals("NEAR"))continue;
            data.doors(s,row,now);
            data.observeHomes(s,row,now);
            data.observe(s,row,now);
            for(var v:life.villages())if(v.getBoolean("Ready")){
                var from=NaturalVillageLifeData.decode(v);if(from.settlementId().equals(b.settlementId()))continue;var h=HouseholdData.get(s).byId(NaturalVillageLifeData.home(from));
                if(h!=null)data.apply(s,from,b,h.headCharacterId(),now);
            }data.consider(s,row,now);data.progress(s,row,now);
        }
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup){
        tag.putInt("Schema",1);var list=new ListTag();sites.values().forEach(x->list.add(x.copy()));tag.put("Sites",list);
        var moves=new ListTag();applications.values().forEach(x->moves.add(x.copy()));tag.put("Applications",moves);return tag;
    }
    private static SettlementDevelopmentData load(CompoundTag tag,HolderLookup.Provider lookup){
        if(tag.getInt("Schema")!=1)throw new IllegalArgumentException("Unknown development schema");var d=new SettlementDevelopmentData();
        for(var x:tag.getList("Sites",Tag.TAG_COMPOUND)){
            var row=((CompoundTag)x).copy();if(!SettlementDevelopmentLayout.supports(village(row))||d.sites.putIfAbsent(village(row).buildingKey(),row)!=null)throw new IllegalArgumentException("Invalid development site");
            for(var p:d.projects(row))for(var key:p.getCompound("Jobs").getAllKeys()){
                var job=p.getCompound("Jobs").getCompound(key);if(job.getBoolean("Submitting")){job.putBoolean("Review",true);job.putString("Reason","提交中断，保留原回执和物资核对，不盲目重放");}
            }
        }
        for(var x:tag.getList("Applications",Tag.TAG_COMPOUND)){var a=((CompoundTag)x).copy();if(!a.hasUUID("Household")||d.applications.putIfAbsent(a.getUUID("Household"),a)!=null)throw new IllegalArgumentException("Duplicate development application");}return d;
    }
}
