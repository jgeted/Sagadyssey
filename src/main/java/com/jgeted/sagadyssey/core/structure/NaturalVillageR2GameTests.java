package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.lod.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("sagadyssey_society_r2") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class NaturalVillageR2GameTests {
    private static final String MARKER="society-r2-away.nbt";
    private static CompoundTag previous;
    private static String phase(){return System.getProperty("sagadyssey.society.phase","");}
    @net.neoforged.bus.api.SubscribeEvent public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!(e.getServer() instanceof GameTestServer))return;var l=e.getServer().overworld();try{
            l.getStructureManager().getOrCreate(ResourceLocation.parse("sagadyssey_society_r2:empty")).load(l.holderLookup(Registries.BLOCK),TagParser.parseTag("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
            previous=null;if(phase().startsWith("r2-compare-")&&!phase().equals("r2-compare-baseline")){
                previous=NbtIo.readCompressed(e.getServer().getWorldPath(LevelResource.ROOT).resolve("society-r2-comparison.nbt"),NbtAccounter.unlimitedHeap());
                var world=NbtIo.readCompressed(e.getServer().getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompound("Data");
                e.getServer().getWorldData().overworldData().setGameTime(Math.max(world.getLong("Time"),previous.getLong("Time"))+1);e.getServer().getWorldData().overworldData().setDayTime(world.getLong("DayTime")+1);
            }
            if(phase().equals("r2-away-restart")||phase().equals("r2-exchange-restart")){
                previous=NbtIo.readCompressed(e.getServer().getWorldPath(LevelResource.ROOT).resolve(phase().equals("r2-exchange-restart")?"regional-exchange-checkpoint.nbt":MARKER),NbtAccounter.unlimitedHeap());
                var world=NbtIo.readCompressed(e.getServer().getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompound("Data");
                e.getServer().getWorldData().overworldData().setGameTime(Math.max(world.getLong("Time"),previous.getLong("Time"))+1);e.getServer().getWorldData().overworldData().setDayTime(world.getLong("DayTime")+1);
            }
        }catch(Exception x){throw new IllegalStateException(x);}
        if(phase().startsWith("r2-")){
            l.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING).set(3,e.getServer());
            l.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(true,e.getServer());
            l.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,e.getServer());
        }
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){return switch(phase()){
        case "r2-handoff"->List.of(test("two_real_villages_unload_produce_and_return_once",h->handoff(h,false,false),300000));
        case "r2-away-save"->List.of(test("save_while_original_villages_remain_unloaded",h->handoff(h,true,false),300000));
        case "r2-away-restart"->List.of(test("resume_remote_authority_from_independent_jvm",h->handoff(h,false,true),300000));
        case "r2-review"->List.of(test("conflicting_physical_mirror_is_not_overwritten",NaturalVillageR2GameTests::review,20000));
        case "r2-compare-baseline"->List.of(test("save_one_shared_starting_snapshot",NaturalVillageR2GameTests::comparisonBaseline,20000));
        case "r2-compare-near","r2-compare-far","r2-compare-far-save","r2-compare-far-restart"->List.of(test(phase(),NaturalVillageR2GameTests::comparison,300000));
        case "r2-cargo"->List.of(test("original_pickup_cargo_and_delivery_survive_each_boundary",NaturalVillageBoundaryGameTests::cargo,80000));
        case "r2-crop"->List.of(test("pre_and_post_harvest_have_one_authoritative_commit",NaturalVillageBoundaryGameTests::crop,80000));
        case "r2-interrupt"->List.of(test("unknown_interrupted_transfer_is_not_replayed",NaturalVillageBoundaryGameTests::interrupted,80000));
        case "r2-exchange"->List.of(test("actual_prepaid_barter_with_one_original_courier",h->RegionalExchangeGameTests.run(h,false),60000));
        case "r2-exchange-block"->List.of(test("blocked_paid_food_shipment_causes_hunger_then_recovers",h->RegionalExchangeGameTests.run(h,true),60000));
        case "r2-exchange-save"->List.of(test("save_actual_paid_food_cargo",h->RegionalExchangeGameTests.run(h,false),60000));
        case "r2-exchange-restart"->List.of(test("original_paid_cargo_after_independent_restart",RegionalExchangeGameTests::restart,60000));
        case "r2-migration"->List.of(test("original_household_walks_and_waits_for_valid_destination",HouseholdCaravanGameTests::run,60000));
        case "r2-exchange-destination"->List.of(test("changed_receiver_preserves_original_cargo_and_unpaid_debt",h->RegionalExchangeGameTests.run(h,false),60000));
        default->List.of();};}
    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> body,int ticks){return new TestFunction("r2",name,"sagadyssey_society_r2:empty",ticks,0,true,body);}
    private static boolean absent(ServerLevel l,NaturalVillageBuilding b){return l.getChunkSource().getChunkNow(b.buildingOrigin().getX()>>4,b.buildingOrigin().getZ()>>4)==null;}
    private static int count(Container c){int sum=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(Items.CARROT))sum+=c.getItem(i).getCount();return sum;}
    private static int virtual(CompoundTag r,ServerLevel l){int sum=0;for(var e:r.getList("Stores",Tag.TAG_COMPOUND))sum+=count(NaturalVillageEconomyData.inventory(((CompoundTag)e).getList("Items",Tag.TAG_COMPOUND),l.registryAccess()));return sum;}
    private static void balance(GameTestHelper h,NaturalVillageBuilding b,CompoundTag r){var clock=RemoteCropClock.load(r.getCompound("Clock"));h.assertTrue(clock.produced()-clock.planted()==virtual(r,h.getLevel())+r.getLong("Meals"),"all remote carrots reconcile outputs="+clock.produced()+" seeds="+clock.planted()+" stores="+virtual(r,h.getLevel())+" meals="+r.getLong("Meals"));}
    private static void marker(GameTestHelper h,List<NaturalVillageBuilding> villages){var t=new CompoundTag();var list=new ListTag();var d=NaturalVillageEconomyData.get(h.getLevel().getServer());for(var b:villages)list.add(d.row(b.buildingKey()).getCompound("Village").copy());t.put("Villages",list);t.putLong("Time",h.getLevel().getGameTime());
        try{NbtIo.writeCompressed(t,h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve(MARKER));h.getLevel().getServer().saveEverything(true,true,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();}catch(Exception e){throw new IllegalStateException(e);}}
    private static void handoff(GameTestHelper h,boolean saveAway,boolean restart){
        var l=h.getLevel();var server=l.getServer();var villages=new ArrayList<NaturalVillageBuilding>();int[] state={0};long[] target={0};
        if(restart){h.assertTrue(previous!=null,"original remote checkpoint");for(var e:previous.getList("Villages",Tag.TAG_COMPOUND))villages.add(NaturalVillageLifeData.decode((CompoundTag)e));state[0]=2;target[0]=l.getGameTime()+60000;server.getWorldData().overworldData().setGameTime(target[0]);l.setDayTime(l.getDayTime()+60000);}
        else{villages.add(NaturalVillageR1GameTests.fixture(h,0));villages.add(NaturalVillageR1GameTests.fixture(h,256));}
        h.onEachTick(()->{var d=NaturalVillageEconomyData.get(server);if(h.getTick()%1200==0)Sagadyssey.LOGGER.info("R2_PROGRESS phase={} state={} backlog={} regions={}",phase(),state[0],d.backlog(l.getGameTime()),d.rows().stream().map(r->r.getString("Phase")+":"+r.getString("Reason")).toList());
            if(state[0]==0&&h.getTick()>=300){for(var b:villages){var r=d.row(b.buildingKey());h.assertTrue(r!=null&&r.getString("Phase").equals("NEAR"),"same two supported villages enrolled");NaturalVillageR1GameTests.visiting(l,b,false);}state[0]=1;}
            if(state[0]==1){if(villages.stream().anyMatch(b->!absent(l,b)||NaturalVillageEconomyData.get(server).row(b.buildingKey()).getString("Phase").equals("NEAR")))return;
                for(var b:villages)for(int i=0;i<3;i++)if(l.getEntity(NaturalVillageLifeData.body(b,i))!=null)return;
                for(var b:villages){var r=d.row(b.buildingKey());h.assertTrue(r.getString("Phase").equals("REMOTE")&&r.getBoolean("Farmer"),"real unload captured labor and exclusive remote ownership: "+r);for(int i=0;i<3;i++)h.assertTrue(l.getEntity(NaturalVillageLifeData.body(b,i))==null,"original bodies actually unloaded");}
                target[0]=l.getGameTime()+(saveAway?60000:120000);server.getWorldData().overworldData().setGameTime(target[0]);l.setDayTime(l.getDayTime()+(saveAway?60000:120000));state[0]=2;
            }
            if(state[0]==2){if(d.backlog(l.getGameTime())>0)return;
                for(var b:villages){var r=d.row(b.buildingKey());balance(h,b,r);h.assertTrue(RemoteCropClock.load(r.getCompound("Clock")).produced()>0&&r.getLong("Meals")>0,"registered land/labor produces and feeds from real stores while bodies stay unloaded");h.assertTrue(absent(l,b),"no hidden force-loading");h.assertTrue(d.advance(server,b.buildingKey(),RemoteCropClock.load(r.getCompound("Clock")).settledAt(),2)==0,"repeating settled time cannot repeat production");}
                if(saveAway){marker(h,villages);Sagadyssey.LOGGER.info("R2_AWAY_SAVE_ACCEPT backlog={} rows={}",d.backlog(l.getGameTime()),d.rows());h.succeed();state[0]=4;return;}
                // Re-read actual SavedData before visiting; inventory authority never depends on a static cache.
                server.overworld().getDataStorage().save();net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
                try{var tag=server.overworld().getDataStorage().readTagFromDisk(NaturalVillageEconomyData.NAME,null,net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());server.overworld().getDataStorage().set(NaturalVillageEconomyData.NAME,NaturalVillageEconomyData.load(tag.getCompound("data"),server.registryAccess()));}catch(Exception e){throw new IllegalStateException(e);}
                for(var b:villages)NaturalVillageR1GameTests.visiting(l,b,true);state[0]=3;
            }
            if(state[0]==3){d=NaturalVillageEconomyData.get(server);for(var b:villages){var r=d.row(b.buildingKey());h.assertTrue(!r.getString("Phase").equals("REVIEW"),"projection conflict: "+r.getString("Reason"));if(!r.getString("Phase").equals("NEAR"))return;for(int i=0;i<3;i++){var n=NaturalVillageR1GameTests.actor(l,b,i);if(n==null)return;n.setNoAi(true);h.assertTrue(n.getCharacterId().equals(NaturalVillageLifeData.person(b,i)),"same original persons after return");}}
                for(var b:villages){var r=d.row(b.buildingKey());balance(h,b,r);int actual=0;for(var p:List.of(NaturalVillageLayout.RAW,NaturalVillageLayout.PANTRY,NaturalVillageLayout.WOOD,NaturalVillageLayout.PUBLIC))actual+=count((Container)l.getBlockEntity(NaturalVillageLayout.at(b,p)));
                    h.assertTrue(actual==virtual(r,l),"projected original containers equal authoritative remote stock");h.assertTrue(!d.resume(server,b.buildingKey()),"second return cannot reapply an epoch");for(int i=0;i<3;i++)h.assertTrue(NpcLodRuntime.loaded(NaturalVillageR1GameTests.actor(l,b,i)),"existing actor checkpoint remains valid");}
                for(var b:villages)for(int i=0;i<3;i++)NaturalVillageR1GameTests.actor(l,b,i).setNoAi(false);
                Sagadyssey.LOGGER.info("R2_HANDOFF_ACCEPT restart={} backlog={} modelMetrics={} transitionMetrics={}",restart,d.backlog(l.getGameTime()),LodMetrics.snapshot(server,LodMetrics.Kind.REGION_ECONOMY),LodMetrics.snapshot(server,LodMetrics.Kind.REGION_HANDOFF));h.succeed();state[0]=4;
            }
        });
    }
    private static void review(GameTestHelper h){var b=NaturalVillageR1GameTests.fixture(h,0);var l=h.getLevel();int[] stage={0};
        h.onEachTick(()->{var d=NaturalVillageEconomyData.get(l.getServer());if(stage[0]==0&&h.getTick()>=300){NaturalVillageR1GameTests.visiting(l,b,false);stage[0]=1;}
            if(stage[0]==1&&absent(l,b)&&d.row(b.buildingKey()).getString("Phase").equals("REMOTE")){NaturalVillageR1GameTests.visiting(l,b,true);var raw=(Container)l.getBlockEntity(NaturalVillageLayout.at(b,NaturalVillageLayout.RAW));raw.setItem(26,new net.minecraft.world.item.ItemStack(Items.DIAMOND));stage[0]=2;}
            if(stage[0]==2){var r=d.row(b.buildingKey());if(!r.getString("Phase").equals("REVIEW"))return;h.assertTrue(((Container)l.getBlockEntity(NaturalVillageLayout.at(b,NaturalVillageLayout.RAW))).getItem(26).is(Items.DIAMOND),"unknown physical asset is retained rather than erased");h.assertTrue(NaturalVillageEconomyData.controls(l.getServer(),NaturalVillageLifeData.person(b,0)),"conflict pauses execution without manufacturing replacement assets");Sagadyssey.LOGGER.info("R2_REVIEW_ACCEPT {}",r.getString("Reason"));h.succeed();stage[0]=3;}
        });
    }
    private static void comparisonMarker(GameTestHelper h,List<NaturalVillageBuilding> villages,CompoundTag original){
        var t=original==null?new CompoundTag():original.copy();var d=NaturalVillageEconomyData.get(h.getLevel().getServer());
        if(original==null){var list=new ListTag();for(var b:villages)list.add(d.row(b.buildingKey()).getCompound("Village").copy());t.put("Villages",list);t.putLong("BaselineTime",h.getLevel().getGameTime());}
        t.putLong("Time",h.getLevel().getGameTime());
        try{NbtIo.writeCompressed(t,h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("society-r2-comparison.nbt"));h.getLevel().getServer().saveEverything(true,true,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();}catch(Exception e){throw new IllegalStateException(e);}
    }
    private static void comparisonBaseline(GameTestHelper h){var l=h.getLevel();var villages=List.of(NaturalVillageR1GameTests.fixture(h,0),NaturalVillageR1GameTests.fixture(h,256));int[] stage={0};
        h.onEachTick(()->{var d=NaturalVillageEconomyData.get(l.getServer());if(stage[0]==0&&h.getTick()>=300){for(var b:villages)NaturalVillageR1GameTests.visiting(l,b,false);stage[0]=1;}
            if(stage[0]==1){for(var b:villages){var r=d.row(b.buildingKey());if(r==null||!r.getString("Phase").equals("REMOTE")||!absent(l,b))return;for(int i=0;i<3;i++)if(l.getEntity(NaturalVillageLifeData.body(b,i))!=null)return;h.assertTrue(r.getBoolean("Farmer")&&RemoteCropClock.load(r.getCompound("Clock")).produced()==0,"baseline captured before first harvest");}
                comparisonMarker(h,villages,null);Sagadyssey.LOGGER.info("R2_COMPARISON_BASELINE_ACCEPT time={} people=6 villages=2",l.getGameTime());h.succeed();stage[0]=2;}
        });
    }
    private static void comparison(GameTestHelper h){
        h.assertTrue(previous!=null,"identical starting world copy required");h.assertTrue(h.getLevel().getGameRules().getInt(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING)==3&&h.getLevel().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT),"frozen natural growth configuration survives GameTest restart");var l=h.getLevel();var server=l.getServer();var villages=new ArrayList<NaturalVillageBuilding>();for(var e:previous.getList("Villages",Tag.TAG_COMPOUND))villages.add(NaturalVillageLifeData.decode((CompoundTag)e));
        boolean near=phase().equals("r2-compare-near"),save=phase().equals("r2-compare-far-save");long target=previous.getLong("BaselineTime")+(save?120000:240000);int[] stage={0},calls={0},lastServerTick={-1};Set<String> meals=new HashSet<>();
        boolean[] jumped={false};var asOf=new HashMap<String,CompoundTag>();var asOfNeeds=new TreeMap<String,String>();
        if(near){for(var b:villages)NaturalVillageR1GameTests.visiting(l,b,true);stage[0]=1;}
        h.onEachTick(()->{if(lastServerTick[0]==server.getTickCount())return;lastServerTick[0]=server.getTickCount();var d=NaturalVillageEconomyData.get(server);if(++calls[0]>260000)throw new GameTestAssertException("comparison exhausted actual tick budget");
            if(!near&&!jumped[0]){server.getWorldData().overworldData().setGameTime(Math.max(target,l.getGameTime()));l.setDayTime(Math.max(target,l.getDayTime()));jumped[0]=true;}
            if(!near)for(var b:villages)if(!asOf.containsKey(b.buildingKey())){
                var r=d.row(b.buildingKey());var clock=RemoteCropClock.load(r.getCompound("Clock"));
                if(clock.backlog(target)==0){h.assertTrue(clock.settledAt()<=target,"as-of snapshot must precede the next unsettled period");asOf.put(b.buildingKey(),r.copy());for(int i=0;i<3;i++){var person=NaturalVillageLifeData.person(b,i);asOfNeeds.put(person.toString(),com.jgeted.sagadyssey.social.life.NpcLifeData.get(server).needs(person).save().toString());}}
            }
            if(calls[0]%12000==0)Sagadyssey.LOGGER.info("R2_COMPARE_PROGRESS mode={} time={} target={} calls={} backlog={}",phase(),l.getGameTime(),target,calls[0],d.backlog(l.getGameTime()));
            if(near){for(var b:villages)NaturalVillageR1GameTests.observe(l,b,meals);if(l.getGameTime()<target)return;}
            else if(stage[0]==0){if(asOf.size()!=2||d.backlog(l.getGameTime())>0)return;for(var b:villages)balance(h,b,d.row(b.buildingKey()));
                if(save){comparisonMarker(h,villages,previous);Sagadyssey.LOGGER.info("R2_COMPARE_MID_SAVE_ACCEPT time={} baseline={}",l.getGameTime(),previous.getLong("BaselineTime"));h.succeed();stage[0]=3;return;}
                for(var b:villages)NaturalVillageR1GameTests.visiting(l,b,true);stage[0]=1;return;
            }
            if(stage[0]!=1)return;
            for(var b:villages){var r=d.row(b.buildingKey());h.assertTrue(!r.getString("Phase").equals("REVIEW"),"comparison projection: "+r.getString("Reason"));if(!r.getString("Phase").equals("NEAR"))return;for(int i=0;i<3;i++)if(NaturalVillageR1GameTests.actor(l,b,i)==null)return;}
            for(var b:villages)for(int i=0;i<3;i++)NaturalVillageR1GameTests.actor(l,b,i).setNoAi(true);
            for(var b:villages)NaturalVillageR1GameTests.observe(l,b,meals);
            var result=new LinkedHashMap<String,Object>();result.put("mode",phase());result.put("baselineTime",previous.getLong("BaselineTime"));result.put("horizon",240000);int physical=0,nearProduced=0,nearInputs=0;long remoteProduced=0,remoteInputs=0,remoteMeals=0;
            var first=new TreeMap<String,Long>();var clocks=new TreeMap<String,String>();
            for(var b:villages){var r=near?d.row(b.buildingKey()):asOf.get(b.buildingKey());var clock=RemoteCropClock.load(r.getCompound("Clock"));remoteProduced+=clock.produced();remoteInputs+=clock.planted();remoteMeals+=r.getLong("Meals");clocks.put(b.settlementId().toString(),clock.save().toString());
                long firstAt=Long.MAX_VALUE;for(var e:r.getList("Receipts",Tag.TAG_COMPOUND)){var t=(CompoundTag)e;if(t.getString("Kind").equals("HARVEST_REPLANT"))firstAt=Math.min(firstAt,t.getLong("At"));}
                for(var receipt:com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData.get(server).receiptsFor(NaturalVillageLifeData.body(b,0))){
                    for(var delta:receipt.inventoryDeltas())if(delta.fingerprint().equals("minecraft:carrot#0")){if(delta.quantity()>0)nearProduced+=delta.quantity();else nearInputs-=delta.quantity();}
                    if(receipt.action().operation()==com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkAction.Operation.HARVEST)firstAt=Math.min(firstAt,receipt.committedAt());
                }
                first.put(b.settlementId().toString(),firstAt);
                for(var p:List.of(NaturalVillageLayout.RAW,NaturalVillageLayout.PANTRY,NaturalVillageLayout.WOOD,NaturalVillageLayout.PUBLIC))physical+=count((Container)l.getBlockEntity(NaturalVillageLayout.at(b,p)));
                for(int i=0;i<3;i++){var n=NaturalVillageR1GameTests.actor(l,b,i);h.assertTrue(n.getCharacterId().equals(NaturalVillageLifeData.person(b,i))&&NpcLodRuntime.loaded(n),"same six original persons and valid checkpoint");physical+=count(n.getPersonalInventory())+count(n.getTransportInventory());}
            }
            int returnedPhysical=physical;
            if(!near){long liveProduced=0,liveInputs=0,liveMeals=0;for(var b:villages){var current=d.row(b.buildingKey());var c=RemoteCropClock.load(current.getCompound("Clock"));liveProduced+=c.produced();liveInputs+=c.planted();liveMeals+=current.getLong("Meals");}
                h.assertTrue(32+nearProduced+liveProduced-nearInputs-liveInputs==returnedPhysical+meals.size()+liveMeals,"actual return also conserves all current physical stock");
                physical=32;for(var b:villages)physical+=virtual(asOf.get(b.buildingKey()),l);
            }
            h.assertTrue(32+nearProduced+remoteProduced-nearInputs-remoteInputs==physical+meals.size()+remoteMeals,"comparison exact regional carrot balance produced="+(nearProduced+remoteProduced)+" planted="+(nearInputs+remoteInputs)+" stock="+physical+" meals="+(meals.size()+remoteMeals));
            var needs=new TreeMap<String,String>();for(var b:villages)for(int i=0;i<3;i++){var person=NaturalVillageLifeData.person(b,i);needs.put(person.toString(),com.jgeted.sagadyssey.social.life.NpcLifeData.get(server).needs(person).save().toString());}result.put("needs",near?needs:asOfNeeds);result.put("metricsAsOf",target);result.put("actualReturnTime",l.getGameTime());result.put("actualReturnStock",returnedPhysical);
            result.put("population",6);result.put("grossHarvest",nearProduced+remoteProduced);result.put("seedInputs",nearInputs+remoteInputs);result.put("stock",physical);result.put("meals",meals.size()+remoteMeals);result.put("firstHarvestAt",first);result.put("remoteClocks",clocks);result.put("backlog",d.backlog(l.getGameTime()));result.put("modelMetrics",LodMetrics.snapshot(server,LodMetrics.Kind.REGION_ECONOMY));
            try{var dir=server.getWorldPath(LevelResource.ROOT).getParent();java.nio.file.Files.writeString(dir.resolve(phase()+"-result.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));}
            catch(Exception e){throw new IllegalStateException(e);}
            for(var b:villages)for(int i=0;i<3;i++)NaturalVillageR1GameTests.actor(l,b,i).setNoAi(false);
            Sagadyssey.LOGGER.info("R2_COMPARE_ACCEPT {}",result);h.succeed();stage[0]=2;
        });
    }

}
