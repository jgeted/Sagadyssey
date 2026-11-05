package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** A single causal world crosses three real unload/process-restart checkpoints.
 * A checkpoint test passing is only preparation, never the business acceptance. */
@GameTestHolder("sagadyssey_development")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class SettlementDevelopmentContinuityGameTests {
    private static final String FILE="development-checkpoint.nbt";
    private static CompoundTag restored;
    private static final Set<UUID> admitted=new HashSet<>();
    private static boolean enabled(){return System.getProperty("sagadyssey.development.phase","").startsWith("formal-");}
    private static void check(boolean value,String why){if(!value)throw new GameTestAssertException(why);}
    private static CompoundTag links(ServerLevel l){
        var t=LodContinuityP8GameTests.associatedState(l);var s=l.getServer();
        var development=SettlementDevelopmentData.existing(s);t.put("Development",development==null?new CompoundTag():development.save(new CompoundTag(),s.registryAccess()));
        var coordination=SettlementCoordinatorData.existing(s);t.put("Coordination",coordination==null?new CompoundTag():coordination.save(new CompoundTag(),s.registryAccess()));
        var growth=SettlementGrowthData.existing(s);t.put("Growth",growth==null?new CompoundTag():growth.save(new CompoundTag(),s.registryAccess()));
        var safety=SettlementSafetyData.existing(s);t.put("Safety",safety==null?new CompoundTag():safety.save(new CompoundTag(),s.registryAccess()));
        t.put("Batches",ProductionBatchData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Orders",ProductionOrderData.get(s).save(new CompoundTag(),s.registryAccess()));
        return t;
    }
    @net.neoforged.bus.api.SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGH)
    public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!enabled()||!(e.getServer() instanceof GameTestServer))return;restored=null;admitted.clear();
        if(!System.getProperty("sagadyssey.development.phase","").equals("formal-resume"))return;
        var s=e.getServer();var l=s.overworld();
        try{
            restored=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve(FILE),NbtAccounter.unlimitedHeap());
            var actual=links(l);var expected=restored.getCompound("Links").copy();
            for(var v:expected.getCompound("Economy").getList("Regions",Tag.TAG_COMPOUND)){
                var row=(CompoundTag)v;row.putBoolean("ObservedAway",true);row.putBoolean("ReturnRequested",false);
            }
            NbtIo.writeCompressed(actual,s.getWorldPath(LevelResource.ROOT).resolve("development-before-first-tick.nbt"));
            check(LodContinuityP8GameTests.comparableState(expected).equals(LodContinuityP8GameTests.comparableState(actual)),
                    "All associated ledgers must match before first tick; no partial-object replacement");
            var level=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompound("Data");
            check(level.getLong("Time")==restored.getLong("SavedTime"),"exact full-checkpoint time");
            l.getGameRules().assignFrom(new GameRules(new com.mojang.serialization.Dynamic<>(NbtOps.INSTANCE,level.getCompound("GameRules"))),s);
            s.getWorldData().overworldData().setGameTime(level.getLong("Time")+1);
            s.getWorldData().overworldData().setDayTime(level.getLong("DayTime")+1);
            Sagadyssey.LOGGER.info("GROWTH_RESTART checkpoint={} fullAssociatedLedgers=true beforeFirstTick=true savedTime={} wallClockCatchup=false",
                    restored.getInt("NextCheckpoint"),level.getLong("Time"));
        }catch(Exception x){throw new IllegalStateException("Exact full growth checkpoint required",x);}
    }
    @net.neoforged.bus.api.SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGH)
    public static void joined(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e){
        if(!enabled()||restored==null||!(e.getEntity() instanceof NpcBase n)||!(e.getLevel() instanceof ServerLevel l)||!admitted.add(n.getUUID()))return;
        var saved=restored.getList("Bodies",Tag.TAG_COMPOUND).stream().map(x->(CompoundTag)x).filter(x->x.getUUID("Body").equals(n.getUUID())).findFirst().orElseThrow();
        check(saved.getUUID("Person").equals(n.getCharacterId()),"same original stable person at body load");
        check(saved.get("Personal").equals(NaturalVillageEconomyData.items(n.getPersonalInventory(),l.registryAccess())),"same actual personal goods before work");
        check(saved.get("Cargo").equals(NaturalVillageEconomyData.items(n.getTransportInventory(),l.registryAccess())),"same actual transport cargo before work");
        Sagadyssey.LOGGER.info("GROWTH_BODY_RELOAD body={} person={} originalPersonal=true originalCargo=true",n.getUUID(),n.getCharacterId());
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){
        if(!enabled())return List.of();
        return List.of(new TestFunction("development-formal","bounded_two_rounds_with_real_checkpoints","sagadyssey_development:empty",
                360000,0,true,h->new Run(h).start()));
    }
    private static final class Run {
        final GameTestHelper h;final ServerLevel l;final List<NaturalVillageBuilding> villages;
        final List<NpcBase> leaving=new ArrayList<>();final Set<UUID> people=new HashSet<>();
        final CompoundTag metadata;int next,stage;long since;UUID carryingRoute;boolean initialized;
        long auditAt=Long.MIN_VALUE;CompoundTag auditSite,auditCoordinator;
        void refreshAudit(long now){
            if(auditAt==now)return;auditAt=now;var s=l.getServer();
            auditSite=SettlementDevelopmentData.get(s).site(target().buildingKey());
            var coordinator=SettlementCoordinatorData.existing(s);auditCoordinator=coordinator==null?null:coordinator.site(target().buildingKey());
        }
        Run(GameTestHelper helper){
            h=helper;l=h.getLevel();
            if(restored==null){
                villages=List.of(NaturalVillageR1GameTests.fixture(h,0,SettlementDevelopmentLayout.TEMPLATE),
                        NaturalVillageR1GameTests.fixture(h,256,NaturalVillageLayout.FARM_V3),
                        NaturalVillageR1GameTests.fixture(h,512,NaturalVillageLayout.FARM_V3));
                metadata=new CompoundTag();
            }else{
                villages=restored.getList("Villages",Tag.TAG_COMPOUND).stream().map(x->NaturalVillageLifeData.decode((CompoundTag)x)).toList();
                metadata=restored.getCompound("Metadata").copy();next=restored.getInt("NextCheckpoint");
                if(restored.hasUUID("CarryingRoute"))carryingRoute=restored.getUUID("CarryingRoute");
                initialized=true;stage=3;since=l.getGameTime();
                check(restored.getBoolean("ActuallyUnloaded"),"original checkpoint already proved actual absence before process exit");
                // Restart may naturally load a saved ticket before this test callback. The
                // EntityJoin proof still verifies every original body and item before work.
                // Clear only this isolated test's observer flags, then request its normal return.
                SettlementDevelopmentGameTests.releaseObservation(l,villages);
                SettlementDevelopmentGameTests.observeRoute(l,villages);
            }
            for(var b:villages)for(int i=0;i<3;i++)people.add(NaturalVillageLifeData.person(b,i));
        }
        void start(){
            l.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
            l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,l.getServer());
            l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true,l.getServer());
            l.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(3,l.getServer());
            l.getServer().tickRateManager().requestGameToSprint(360000);
            h.onEachTick(this::tick);
        }
        NaturalVillageBuilding target(){return villages.getFirst();}
        List<NpcBase> bodies(){
            var list=new ArrayList<NpcBase>();
            for(var b:villages)for(int i=0;i<3;i++){var n=NaturalVillageR1GameTests.actor(l,b,i);if(n!=null)list.add(n);}
            return list;
        }
        boolean ready(){
            var natural=NaturalVillageLifeData.existing(l.getServer());var d=SettlementDevelopmentData.existing(l.getServer());
            return natural!=null&&d!=null&&d.site(target().buildingKey())!=null&&villages.stream().allMatch(b->{var v=natural.village(b.buildingKey());return v!=null&&v.getBoolean("Ready");});
        }
        void observeMeals(){
            var seen=metadata.getCompound("LastMeal");var meals=metadata.getList("Meals",Tag.TAG_COMPOUND);
            for(var p:people){var needs=NpcLifeData.get(l.getServer()).needs(p);if(needs==null)continue;
                String k=p.toString();long before=seen.contains(k)?seen.getLong(k):-1;
                if(needs.lastAteAt()>before){
                    if(before>=-1&&needs.lastAteAt()>=metadata.getLong("Started")){
                        var t=new CompoundTag();t.putUUID("Person",p);t.putLong("At",needs.lastAteAt());t.putString("Source",needs.lastFoodSource());meals.add(t);
                    }seen.putLong(k,needs.lastAteAt());
                }
            }metadata.put("LastMeal",seen);metadata.put("Meals",meals);
        }
        void observeHunger(){
            var s=l.getServer();var records=metadata.getCompound("HungerHistory");
            for(var n:bodies()){
                var need=NpcLifeData.get(s).needs(n.getCharacterId());if(need==null)continue;
                var key=n.getCharacterId().toString();var r=records.getCompound(key);boolean severe=need.hunger()>=800;
                long loaded=severe?r.getLong("CurrentLoadedSevereTicks")+1:0;
                r.putLong("CurrentLoadedSevereTicks",loaded);r.putLong("MaxLoadedSevereTicks",Math.max(loaded,r.getLong("MaxLoadedSevereTicks")));
                var home=HouseholdData.get(s).forMember(n.getCharacterId());
                boolean targetResident=home!=null&&target().settlementId().equals(home.settlementId())&&!SettlementDevelopmentData.moving(s,n.getCharacterId());
                boolean supplied=severe&&targetResident&&SettlementDevelopmentData.capacity(s,home).food()>0;
                boolean declaredBarrier=home!=null&&home.id().equals(NaturalVillageLifeData.home(target()))&&!metadata.getList("TemporaryBarrier",Tag.TAG_COMPOUND).isEmpty();
                long avoidable=supplied&&!declaredBarrier?r.getLong("CurrentSuppliedSevereTicks")+1:0;
                r.putLong("CurrentSuppliedSevereTicks",avoidable);r.putLong("MaxSuppliedSevereTicks",Math.max(avoidable,r.getLong("MaxSuppliedSevereTicks")));
                r.putInt("LastHunger",need.hunger());r.putLong("At",l.getGameTime());records.put(key,r);
                check(avoidable<=2400,"frozen severe-hunger waiting limit with a legal stocked target pantry; declared barrier recorded separately");
            }metadata.put("HungerHistory",records);
        }
        void identityAudit(){
            var s=l.getServer();var seen=new HashSet<UUID>();
            for(var n:bodies())check(seen.add(n.getCharacterId())&&people.contains(n.getCharacterId()),"no duplicate or replaced loaded body");
            for(var p:people){
                var c=CharacterRegistryData.get(s).byId(p);check(c!=null&&c.alive(),"original live person, never respawned to fill a vacancy");
                check(HouseholdData.get(s).households().stream().filter(home->home.contains(p)).count()==1,"one effective household per stable identity");
            }
        }
        int carrots(Container c){return SettlementDevelopmentGameTests.count(c,Items.CARROT);}
        ListTag physicalInventories(){
            var result=new ListTag();var s=l.getServer();var rights=RightsStateData.get(s);
            for(var b:villages)for(int i=0;i<NaturalVillageLayout.stores(b).size();i++){
                var at=NaturalVillageLayout.at(b,NaturalVillageLayout.stores(b).get(i));
                if(l.getBlockEntity(at) instanceof Container c)inventoryProof(result,"original-store:"+b.buildingKey()+":"+i,at,NaturalVillageEconomyData.storeId(b,i),c,rights);
            }
            for(int lot=0;lot<2;lot++){var at=SettlementDevelopmentLayout.pantry(target(),lot);if(l.getBlockEntity(at) instanceof Container c)
                inventoryProof(result,"new-home-pantry:"+lot,at,com.jgeted.sagadyssey.worldsim.transfer.ContainerIds.block("household_storage",target().dimension(),at),c,rights);}
            for(var n:bodies()){
                inventoryProof(result,"body:"+n.getCharacterId()+":personal",n.blockPosition(),com.jgeted.sagadyssey.worldsim.transfer.ContainerIds.npcPersonal(n.getCharacterId()),n.getPersonalInventory(),rights);
                inventoryProof(result,"body:"+n.getCharacterId()+":transport",n.blockPosition(),null,n.getTransportInventory(),rights);
            }return result;
        }
        void inventoryProof(ListTag result,String role,BlockPos at,UUID id,Container c,RightsStateData rights){
            var t=new CompoundTag();t.putString("Role",role);t.putLong("Position",at.asLong());t.put("Items",NaturalVillageEconomyData.items(c,l.registryAccess()));
            if(id!=null){t.putUUID("Container",id);var context=rights.context(id);if(context!=null){t.put("BeneficialOwner",context.defaultBeneficialOwner().save());t.put("Custodian",context.custodian().save());t.putLong("Revision",context.revision());}}
            result.add(t);
        }
        void timingAudit(long now){
            refreshAudit(now);var s=l.getServer();var row=auditSite;
            var old=auditCoordinator.getCompound("Jobs").getCompound("HOUSE");
            check(old.getString("State").equals("DONE")||now-metadata.getLong("Started")<=48000,"frozen founder-housing deadline");
            for(var tag:row.getList("Projects",Tag.TAG_COMPOUND)){var p=(CompoundTag)tag;
                if(!Set.of("CANCELLED","CANCEL_PENDING").contains(p.getString("State")))
                    check((p.contains("Built")?p.getLong("Built"):now)-p.getLong("Created")<=48000,"frozen each-construction deadline, no later success erases an overrun");
            }
            for(var a:SettlementDevelopmentData.get(s).applications())if(a.contains("Departed"))
                check((a.contains("Arrived")?a.getLong("Arrived"):now)-a.getLong("Departed")<=36000,"frozen real migration deadline");
        }
        void laborAudit(long now){
            refreshAudit(now);
            var s=l.getServer();var totals=metadata.getCompound("ObservedLabor");var actors=metadata.getCompound("ObservedLaborActors");var byPerson=new HashMap<UUID,Integer>();boolean priming=!metadata.getBoolean("LaborAuditInitialized");
            var rows=new ArrayList<CompoundTag>();
            if(auditCoordinator!=null)rows.add(auditCoordinator);
            for(var tag:auditSite.getList("Projects",Tag.TAG_COMPOUND))rows.add((CompoundTag)tag);
            for(var row:rows)if(row!=null)for(var key:row.getCompound("Jobs").getAllKeys()){
                var job=row.getCompound("Jobs").getCompound(key);if(!job.contains("Labor"))continue;
                String id=job.getString("Id");if(job.hasUUID("Person"))actors.putUUID(id,job.getUUID("Person"));
                int count=job.getInt("Labor"),before=totals.getInt(id);if(!priming){check(count>=before&&count-before<=1,"recorded labor does not advance by an unperformed batch");
                    if(count>before){check(actors.hasUUID(id),"performed labor retains its original actor");byPerson.merge(actors.getUUID(id),count-before,Integer::sum);}}totals.putInt(id,count);
            }
            metadata.put("ObservedLabor",totals);metadata.put("ObservedLaborActors",actors);metadata.putBoolean("LaborAuditInitialized",true);if(priming)return;
            for(var n:bodies())for(var r:FieldworkData.get(s).receiptsFor(n.getUUID()))if(r.committedAt()==now)byPerson.merge(n.getCharacterId(),1,Integer::sum);
            check(byPerson.values().stream().allMatch(v->v<=1),"one original resident cannot perform two recorded labor actions in one tick");
        }
        int physicalFood(){
            int total=0;var economy=NaturalVillageEconomyData.existing(l.getServer());
            for(var b:villages){
                var row=economy.row(b.buildingKey());
                if(row!=null&&!row.getString("Phase").equals("NEAR")){
                    for(var entry:row.getList("Stores",Tag.TAG_COMPOUND))total+=carrots(NaturalVillageEconomyData.inventory(((CompoundTag)entry).getList("Items",Tag.TAG_COMPOUND),l.registryAccess()));
                }else for(var p:NaturalVillageLayout.stores(b)){
                    var at=NaturalVillageLayout.at(b,p);if(!(l.getBlockEntity(at) instanceof Container c))return -1;total+=carrots(c);
                }
            }
            for(int lot=0;lot<2;lot++){var at=SettlementDevelopmentLayout.pantry(target(),lot);if(l.getBlockEntity(at) instanceof Container c)total+=carrots(c);}
            var loaded=bodies();if(loaded.size()!=9)return -1;
            for(var n:loaded)total+=carrots(n.getPersonalInventory())+carrots(n.getTransportInventory());
            return total;
        }
        void foodAudit(){
            int stock=physicalFood();if(stock<0)return;
            var initialKeys=metadata.getCompound("InitialFieldworkKeys");int delta=0;
            for(var n:bodies())for(var r:FieldworkData.get(l.getServer()).receiptsFor(n.getUUID()))if(!initialKeys.contains(r.action().key()))
                for(var item:r.inventoryDeltas())if(item.fingerprint().equals("minecraft:carrot#0"))delta+=item.quantity();
            int feed=SettlementGrowthData.existing(l.getServer()).sites().stream().mapToInt(r->r.getInt("ConsumedFeed")).sum()-metadata.getInt("InitialFeed");
            int meals=metadata.getList("Meals",Tag.TAG_COMPOUND).size();
            int expected=metadata.getInt("InitialFood")+delta-meals-feed;
            check(expected==stock,"exact carrot conservation: expected="+expected+" stock="+stock+" delta="+delta+" meals="+meals+" feed="+feed);
        }
        void tick(){
            long now=l.getGameTime();var s=l.getServer();
            if(Files.exists(s.getServerDirectory().resolve("stop-calibration.flag"))){h.fail("操作者停止并保留完整世界，不计正式验收通过");return;}
            if(!initialized){
                if(!ready())return;
                SettlementDevelopmentGameTests.observeRoute(l,villages);
                SettlementDevelopmentGameTests.configureInitial(l,target(),villages.get(1),villages.get(2));
                metadata.putLongArray("ObservedChunks",l.getForcedChunks().toLongArray());
                metadata.putLong("Started",now);metadata.putInt("InitialFood",physicalFood());
                var initialKeys=new CompoundTag();for(var n:bodies())for(var r:FieldworkData.get(s).receiptsFor(n.getUUID()))initialKeys.putBoolean(r.action().key(),true);
                metadata.put("InitialFieldworkKeys",initialKeys);
                metadata.putInt("InitialFeed",SettlementGrowthData.existing(s).sites().stream().mapToInt(r->r.getInt("ConsumedFeed")).sum());
                check(metadata.getInt("InitialFood")>=0,"all initial physical stores and bodies observed before freezing stock");
                var baselineMeals=new CompoundTag();for(var p:people){var needs=NpcLifeData.get(s).needs(p);baselineMeals.putLong(p.toString(),needs==null?-1:needs.lastAteAt());}
                metadata.put("LastMeal",baselineMeals);metadata.put("Meals",new ListTag());laborAudit(now);write("development-initial.nbt",snapshot(false));
                Sagadyssey.LOGGER.info("GROWTH_FORMAL_INITIAL people=9 initialFood={} targetPantry=64 sourcePantries=64each sourceBeds=1each publicStone=165 publicWool=21 C=24000 W=72000 fullDeadline=360000",metadata.getInt("InitialFood"));
                initialized=true;return;
            }
            observeMeals();observeHunger();identityAudit();timingAudit(now);laborAudit(now);
            check(now-metadata.getLong("Started")<=360000,"frozen two-round total deadline");
            if(stage==3){
                check(now-since<=2400,"frozen body/region return deadline");
                var row=NaturalVillageEconomyData.get(s).row(target().buildingKey());
                if(bodies().size()!=9||row==null||!row.getString("Phase").equals("NEAR"))return;
                if(restored!=null)check(admitted.size()==9,"all nine exact original body proofs verified");
                restoreBarrier();
                Sagadyssey.LOGGER.info("GROWTH_RETURN checkpoint={} originalBodies=9 originalIds=9 nextBusinessMustRun=true",next);
                stage=0;
            }
            if(stage==1){
                check(now-since<=2400,"actual chunk/entity unload deadline; no permanently loaded substitute");
                if(!away())return;
                check(leaving.stream().allMatch(n->n.isRemoved()&&n.getRemovalReason()==Entity.RemovalReason.UNLOADED_TO_CHUNK),"unload reason is not death");
                since=now;stage=2;
                Sagadyssey.LOGGER.info("GROWTH_ACTUALLY_UNLOADED checkpoint={} bodies=9 allForcedChunksAbsent=true",next);
                return;
            }
            if(stage==2){
                check(away(),"region must stay truly absent during pause");
                if(now-since<200)return;
                var current=SettlementDevelopmentData.get(s).site(target().buildingKey());
                if(next==0)check(current.getList("Projects",Tag.TAG_COMPOUND).stream().map(x->(CompoundTag)x).anyMatch(p->p.getString("State").equals("BUILDING")&&p.getCompound("Jobs").getCompound("BUILD").getInt("Placed")>0&&p.getCompound("Jobs").getCompound("BUILD").getInt("Placed")<SettlementDevelopmentLayout.house(target(),p.getInt("Lot")).size()),"construction is still genuinely unfinished at saved pause");
                if(next==1)check(carryingRoute!=null&&SupplyFlowData.get(s).route(carryingRoute).stage()==SupplyFlowData.Stage.CARRYING&&leaving.stream().anyMatch(n->carrots(n.getTransportInventory())>0),"saved original route remains carrying real cargo, not a completed substitute");
                if(next==2)check(SettlementDevelopmentData.get(s).applications().stream().anyMatch(a->Set.of("WALKING","WAIT_DESTINATION").contains(a.getString("State"))),"saved migration remains genuinely underway");
                var proof=snapshot(true);proof.putInt("NextCheckpoint",next+1);
                write(FILE,proof);check(NbtIoSafeRead().equals(proof),"full associated NBT save/read round trip");
                fullCheckpoint(proof);h.succeed();return;
            }
            if(now%200==0)foodAudit();
            var d=SettlementDevelopmentData.get(s);var row=d.site(target().buildingKey());
            if(now%2000==0)Sagadyssey.LOGGER.info("GROWTH_FORMAL_PROGRESS elapsed={} checkpoints={} rounds={} projects={}",now-metadata.getLong("Started"),next,row.getInt("CompletedRounds"),row.get("Projects"));
            if(next==0&&row.getList("Projects",Tag.TAG_COMPOUND).stream().map(x->(CompoundTag)x).anyMatch(p->p.getString("State").equals("BUILDING")&&p.getCompound("Jobs").getCompound("BUILD").getInt("Placed")>0&&p.getCompound("Jobs").getCompound("BUILD").getInt("Placed")<10)){
                release("construction_pause");return;
            }
            if(next==1){
                var route=SupplyFlowData.get(s).routes().stream().filter(r->r.stage()==SupplyFlowData.Stage.CARRYING&&r.principal().equals(SubjectRef.household(NaturalVillageLifeData.home(target())))
                        &&r.destination().equals(NaturalVillageEconomyData.storeId(target(),1))).findFirst().orElse(null);
                if(route!=null&&blockPantry()){
                    carryingRoute=route.id();release("actual_carrying");return;
                }
            }
            if(next==2&&d.applications().stream().filter(a->Set.of("WALKING","WAIT_DESTINATION").contains(a.getString("State"))).anyMatch(a->{
                var from=NaturalVillageLifeData.decode(a.getCompound("Source"));var at=SettlementDevelopmentLayout.entrance(target(),a.getInt("Lot"));
                return a.getList("People",Tag.TAG_COMPOUND).stream().map(x->(CompoundTag)x).allMatch(who->l.getEntity(who.getUUID("Body")) instanceof NpcBase n&&n.blockPosition().distSqr(from.buildingOrigin())>32*32&&n.blockPosition().distSqr(at)>128*128);
            })){release("migration_underway");return;}
            if(row.getInt("CompletedRounds")>=2){
                check(next==3,"all three real unload/full-process checkpoints are required");
                if(carryingRoute!=null)check(SupplyFlowData.get(s).route(carryingRoute).stage()==SupplyFlowData.Stage.DONE,"original carrying route really delivered after restart");
                for(var a:d.applications())check(a.getString("State").equals("STABLE"),"both original households stable");
                var homes=HouseholdData.get(s).households().stream().filter(home->target().settlementId().equals(home.settlementId())).toList();
                check(homes.size()==3&&homes.stream().mapToInt(home->home.members().size()).sum()==9&&homes.stream().allMatch(home->SettlementDevelopmentData.capacity(s,home).stableFloor()),"nine original residents in three actual stable homes");
                foodAudit();write("development-final.nbt",snapshot(false));
                Sagadyssey.LOGGER.info("GROWTH_FINAL_ACCEPT elapsed={} rounds=2 checkpoints=3 originalPeople=9 exactCarrotBalance=true",now-metadata.getLong("Started"));h.succeed();
            }
        }
        boolean blockPantry(){
            var at=NaturalVillageLayout.at(target(),NaturalVillageLayout.PANTRY);var blocks=new ListTag();
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dz!=0)for(int y=0;y<3;y++){
                var p=at.offset(dx,y,dz);
                if(!l.getBlockState(p).isAir()||!l.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new net.minecraft.world.phys.AABB(p)).isEmpty())return false;
                var t=new CompoundTag();t.putLong("Position",p.asLong());t.put("Before",NbtUtils.writeBlockState(l.getBlockState(p)));blocks.add(t);
            }
            for(var e:blocks)l.setBlock(BlockPos.of(((CompoundTag)e).getLong("Position")),Blocks.GLASS.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            metadata.put("TemporaryBarrier",blocks);
            Sagadyssey.LOGGER.info("GROWTH_DECLARED_INTERVENTION temporaryGlass=24 destinationBlocked=true noInventoryChanges=true");
            return true;
        }
        void restoreBarrier(){
            var blocks=metadata.getList("TemporaryBarrier",Tag.TAG_COMPOUND);
            for(var e:blocks){var t=(CompoundTag)e;var p=BlockPos.of(t.getLong("Position"));check(l.getBlockState(p).is(Blocks.GLASS),"only exact test-owned barrier may be restored");
                l.setBlock(p,NbtUtils.readBlockState(l.holderLookup(net.minecraft.core.registries.Registries.BLOCK),t.getCompound("Before")),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            }
            if(!blocks.isEmpty()){metadata.remove("TemporaryBarrier");Sagadyssey.LOGGER.info("GROWTH_DECLARED_INTERVENTION temporaryGlassRemoved=24 originalCargoAndObligationKept=true");}
        }
        void release(String boundary){
            leaving.clear();leaving.addAll(bodies());check(leaving.size()==9,"all original bodies present before release");
            metadata.putString("Boundary",boundary);metadata.putLong("ReleaseAt",l.getGameTime());
            SettlementDevelopmentGameTests.releaseObservation(l,villages);
            l.getServer().getCommands().performPrefixedCommand(l.getServer().createCommandSourceStack().withSuppressedOutput(),"tick sprint stop");
            l.getServer().getCommands().performPrefixedCommand(l.getServer().createCommandSourceStack().withSuppressedOutput(),"tick rate 200");
            stage=1;since=l.getGameTime();
            Sagadyssey.LOGGER.info("GROWTH_RELEASE checkpoint={} boundary={} actualUnloadingNotYetProven=true",next,boundary);
        }
        boolean away(){
            if(!bodies().isEmpty())return false;
            var economy=NaturalVillageEconomyData.existing(l.getServer());var row=economy==null?null:economy.row(target().buildingKey());
            if(row==null||!row.getString("Phase").equals("REMOTE")||!row.getBoolean("ObservedAway"))return false;
            for(long key:metadata.getLongArray("ObservedChunks")){
                var p=new net.minecraft.world.level.ChunkPos(key);if(l.getChunkSource().getChunkNow(p.x,p.z)!=null
                        ||((NaturalVillageEconomyData.RetainedChunks)(Object)l.getChunkSource().chunkMap).sagadyssey$retained(key)!=null)return false;
            }
            return true;
        }
        CompoundTag snapshot(boolean unloaded){
            var t=new CompoundTag();var buildings=new ListTag();var natural=NaturalVillageLifeData.get(l.getServer());
            villages.forEach(b->buildings.add(natural.village(b.buildingKey())));t.put("Villages",buildings);t.put("Metadata",metadata.copy());t.put("Links",links(l));
            var proofs=new ListTag();var originals=unloaded?leaving:bodies();
            for(var n:originals){var p=new CompoundTag();p.putUUID("Body",n.getUUID());p.putUUID("Person",n.getCharacterId());
                p.put("Personal",NaturalVillageEconomyData.items(n.getPersonalInventory(),l.registryAccess()));p.put("Cargo",NaturalVillageEconomyData.items(n.getTransportInventory(),l.registryAccess()));p.put("BodyTag",n.saveWithoutId(new CompoundTag()));proofs.add(p);}
            t.put("Bodies",proofs);if(!unloaded)t.put("PhysicalInventories",physicalInventories());t.putLong("SavedTime",l.getGameTime());t.putInt("NextCheckpoint",next);t.putBoolean("ActuallyUnloaded",unloaded);if(carryingRoute!=null)t.putUUID("CarryingRoute",carryingRoute);return t;
        }
        void write(String name,CompoundTag tag){try{NbtIo.writeCompressed(tag,l.getServer().getWorldPath(LevelResource.ROOT).resolve(name));}catch(Exception x){throw new IllegalStateException(x);}}
        CompoundTag NbtIoSafeRead(){try{return NbtIo.readCompressed(l.getServer().getWorldPath(LevelResource.ROOT).resolve(FILE),NbtAccounter.unlimitedHeap());}catch(Exception x){throw new IllegalStateException(x);}}
        void fullCheckpoint(CompoundTag proof){
            try{
                l.getServer().saveEverything(true,true,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
                var forcedFile=l.getServer().getWorldPath(LevelResource.ROOT).resolve("data/chunks.dat");
                check(l.getForcedChunks().isEmpty(),"all isolated observer flags removed in memory");
                check(Files.exists(forcedFile)&&NbtIo.readCompressed(forcedFile,NbtAccounter.unlimitedHeap()).getCompound("data").getLongArray("Forced").length==0,"saved observer flags empty too, never reuse stale forcing metadata");
                var source=l.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                var target=source.resolveSibling("world-growth-checkpoint-"+proof.getInt("NextCheckpoint"));check(Files.notExists(target),"never overwrite a complete checkpoint");
                try(var paths=Files.walk(source)){for(var p:paths.filter(Files::isRegularFile).toList()){
                    if(p.getFileName().toString().equals("session.lock"))continue;
                    var to=target.resolve(source.relativize(p));Files.createDirectories(to.getParent());Files.copy(p,to);
                }}
                Sagadyssey.LOGGER.info("GROWTH_FULL_CHECKPOINT next={} actualUnload=true completeWorld=true snapshot={} checkpointOnlyNotFinalAcceptance=true",proof.getInt("NextCheckpoint"),target);
            }catch(Exception x){throw new IllegalStateException(x);}
        }
    }
}
