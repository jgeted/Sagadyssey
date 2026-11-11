package com.jgeted.sagadyssey.worldsim;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.google.gson.*;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.config.SagadysseyConfig;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.mojang.serialization.Dynamic;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

/** Passive stopped-FARM continuation. Observation completion is not a performance PASS. */
@GameTestHolder("sagadyssey_playability_fix")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid="sagadyssey")
public final class SettlementReliabilityProfileGameTests {
    private static final String PHASE="reliability-farm-profile";
    private static final int TOTAL=6000,WARMUP=400;
    private static final long PERIOD_NANOS=50_000_000L;
    private static final long WAKE_GUARD_NANOS=17_000_000L;
    private static final UUID[] PEOPLE={UUID.fromString("f970b5a1-d1b4-3617-a41b-5bcaa552e110"),UUID.fromString("4af4ad91-1174-314e-94f0-dd92a6607edc"),UUID.fromString("acc73073-a288-3496-8773-1c6a05e8b1b4")};
    private static final UUID[] BODIES={UUID.fromString("edc7667c-af9f-385d-a43f-a8ee7dac9a46"),UUID.fromString("b60fddab-c1da-3885-842d-a5743eee455f"),UUID.fromString("8677526f-63de-3abe-89b6-5f49a9079eef")};
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static NaturalVillageBuilding village;
    private static MinecraftServer measuredServer;
    private static CompoundTag savedRules;
    private static long savedTime,savedDay,preNanos,firstPre,lastPost,steadyPre,bootWall;
    private static final long[] nanos=new long[TOTAL];
    private static final long[] idleNanos=new long[TOTAL],startIntervals=new long[TOTAL],lateNanos=new long[TOTAL];
    private static MinecraftServer pacerServer;
    private static long nextDue,lastPacedStart,allPacedTicks,allIdleNanos,allLateNanos;
    private static boolean pacerInterrupted;
    private static int samples;
    private static boolean capture,complete;
    private static final JsonArray observations=new JsonArray();
    private static boolean selected(){return PHASE.equals(System.getProperty("sagadyssey.playability.phase",""));}

    @GameTestGenerator public static Collection<TestFunction> tests(){
        return selected()?List.of(new TestFunction("reliability_farm_profile","reliability_farm_profile","sagadyssey_playability_fix:empty",6400,0,true,SettlementReliabilityProfileGameTests::run)):List.of();
    }

    @SubscribeEvent public static void boot(ServerStartedEvent event){
        if(!selected()||!(event.getServer() instanceof GameTestServer))return;
        var server=event.getServer();var level=server.overworld();bootWall=System.nanoTime();
        try{
            var source=server.getWorldPath(LevelResource.ROOT).resolve("level.dat");
            var saved=NbtIo.readCompressed(source,NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
            savedTime=saved.getLongOr("Time", 0L);savedDay=saved.getLongOr("DayTime", 0L);
            if(savedTime!=52918||savedDay!=52917)throw new IllegalStateException("original unrun FARM snapshot Time52918/Day52917 required");
            savedRules=saved.getCompoundOrEmpty("GameRules").copy();
            if(savedRules.isEmpty())throw new IllegalStateException("original rules absent");
            server.getWorldData().overworldData().setGameTime(savedTime);
            server.getWorldData().overworldData().setDayTime(savedDay);
            com.jgeted.sagadyssey.core.MinecraftApi.loadRules(level, savedRules);
            server.setDifficulty(Difficulty.byId(saved.getByteOr("Difficulty", (byte) 0)),true);
            var metadata=server.getWorldData().overworldData();
            metadata.setRaining(saved.getBooleanOr("raining", false));metadata.setThundering(saved.getBooleanOr("thundering", false));
            metadata.setRainTime(saved.getIntOr("rainTime", 0));metadata.setThunderTime(saved.getIntOr("thunderTime", 0));metadata.setClearWeatherTime(saved.getIntOr("clearWeatherTime", 0));
            var life=NaturalVillageLifeData.existing(server);
            if(life==null)throw new IllegalStateException("original village ledger absent; no new initialization");
            village=life.villages().stream().map(NaturalVillageLifeData::decode).filter(v->NaturalVillageLifeData.person(v,0).equals(PEOPLE[0])).findFirst().orElseThrow();
            if(!village.templateId().toString().equals("sagadyssey:vilg_2_coordinated_v6"))throw new IllegalStateException("wrong original FARM template");
            NaturalVillageR1GameTests.visiting(level,village,true);
            SagadysseyConfig.ENABLE_SETTLEMENT_INDUSTRIES.set(true);
            level.getStructureManager().getOrCreate(Identifier.parse("sagadyssey_playability_fix:empty"))
                    .load(level.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
            measuredServer=server;samples=0;capture=false;complete=false;observations.asList().clear();
            Sagadyssey.LOGGER.info("FARM_PROFILE_BOOT source={} originalTime={} originalDay={} rules={} difficulty={} originalVillage={} noNewInputs=true noBusinessCalls=true sprint=false observer=programmatic_no_player",source,savedTime,savedDay,savedRules,level.getDifficulty(),village.buildingKey());
            Sagadyssey.LOGGER.info("FARM_PROFILE_CONTRACT totalTicks=6000 warmupTicks=400 steadyTicks=5600 gameTestTimeout=6400 wallSeconds=540 uniqueBatch=reliability_farm_profile boundedForcedRegion=true actualPlayerView=false performanceVerdict=NOT_EVALUATED clock=GameTestServer_controlled_20Hz explicitPacer=true periodNanos=50000000 catchUp=false skippedTicks=0 pacingIncludesSetup=true");
        }catch(Exception problem){throw new IllegalStateException("FARM profiling requires the original stopped world and metadata",problem);}
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void pre(ServerTickEvent.Pre event){
        var server=event.getServer();
        if(!selected()||!(server instanceof GameTestServer))return;
        long entered=System.nanoTime();
        if(pacerServer!=server){
            pacerServer=server;nextDue=entered;lastPacedStart=0;
            allPacedTicks=0;allIdleNanos=0;allLateNanos=0;pacerInterrupted=false;
        }
        // GameTestServer does not honor normal tick sleeping by itself. Pace every
        // server tick, including setup; wait before the business timing boundary.
        // Late ticks are retained. Next deadline follows this actual start, never
        // an accumulating debt that could cause unbounded catch-up or skipped ticks.
        boolean waited=false;long remaining;
        while((remaining=nextDue-System.nanoTime())>0){
            if(Thread.currentThread().isInterrupted()){pacerInterrupted=true;break;}
            waited=true;
            // Keep the last timer quantum outside a coarse Windows park.
            if(remaining>WAKE_GUARD_NANOS)LockSupport.parkNanos(remaining-WAKE_GUARD_NANOS);
            else Thread.onSpinWait();
        }
        long started=System.nanoTime();
        long idle=waited?started-entered:0;
        long lateness=Math.max(0L,started-nextDue);
        long interval=lastPacedStart==0?0:started-lastPacedStart;
        allPacedTicks++;allIdleNanos+=idle;allLateNanos+=lateness;
        lastPacedStart=started;nextDue=started+PERIOD_NANOS;
        if(!capture||server!=measuredServer)return;
        idleNanos[samples]=idle;startIntervals[samples]=interval;lateNanos[samples]=lateness;
        preNanos=started;if(samples==0)firstPre=preNanos;if(samples==WARMUP)steadyPre=preNanos;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void post(ServerTickEvent.Post event){
        if(!capture||event.getServer()!=measuredServer||preNanos==0)return;
        long now=System.nanoTime();nanos[samples++]=now-preNanos;lastPost=now;preNanos=0;
        if(samples==TOTAL){capture=false;complete=true;}
    }

    private static void run(GameTestHelper h){
        var level=h.getLevel();var server=level.getServer();long begin=level.getGameTime();
        boolean[] initialized={false},finished={false};
        h.onEachTick(()->{
            if(finished[0])return;
            try{
                long elapsed=level.getGameTime()-begin;
                if(System.nanoTime()-bootWall>540_000_000_000L)throw new IllegalStateException("frozen nine-minute observation wall limit reached");
                if(pacerInterrupted)throw new IllegalStateException("controlled 20Hz pacer interrupted; observation is incomplete");
                if(server.tickRateManager().isSprinting()||server.tickRateManager().tickrate()!=20.0F||server.tickRateManager().isFrozen())throw new IllegalStateException("profile requires controlled 20Hz plus unfrozen 20 TPS target, no sprint");
                if(!initialized[0]){
                    if(village==null)throw new IllegalStateException("original village not restored");
                    boolean loaded=true;for(int i=0;i<3;i++)if(NaturalVillageR1GameTests.actor(level,village,i)==null)loaded=false;
                    if(!loaded){if(elapsed>=200)throw new IllegalStateException("original entities failed to load in 200 setup ticks");return;}
                    verifyOriginals(h,level);verifyRules(level);
                    record(level,"initial");snapshot(server,"initial");
                    initialized[0]=true;capture=true;
                    Sagadyssey.LOGGER.info("FARM_PROFILE_START time={} day={} setupTicks={} persons={} bodies={} allServerTicksPaced={} explicitPacer=true",level.getGameTime(),level.getDayTime(),elapsed,Arrays.toString(PEOPLE),Arrays.toString(BODIES),allPacedTicks);
                    return;
                }
                if(complete){
                    verifyOriginals(h,level);verifyRules(level);record(level,"final");snapshot(server,"final");
                    writeMetrics(server,"OBSERVATION_COMPLETE",null);finished[0]=true;
                    Sagadyssey.LOGGER.info("FARM_PROFILE_COMPLETE samples={} warmup={} steady={} actualTicksPerSecond={} allPacedTicks={} allIdleNanos={} allLateNanos={} performanceVerdict=NOT_EVALUATED functionVerdict=NOT_ASSERTED clock=GameTestServer_controlled_20Hz",samples,WARMUP,TOTAL-WARMUP,samples/((lastPost-firstPre)/1.0e9),allPacedTicks,allIdleNanos,allLateNanos);
                    h.succeed();return;
                }
                if(samples>0&&samples%200==0){
                    if(Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("farm-profile-stop-request.json")))throw new IllegalStateException("external frozen-budget stop requested");
                    verifyOriginals(h,level);record(level,samples==WARMUP?"warmup-end":"periodic");
                }
            }catch(Throwable problem){
                capture=false;finished[0]=true;
                try{snapshot(server,"first-failure");writeMetrics(server,"OBSERVATION_FAILED",problem.toString());}catch(Exception saveFailure){Sagadyssey.LOGGER.error("FARM_PROFILE_EVIDENCE_FAILED",saveFailure);}
                h.fail(problem.toString());
            }
        });
    }

    private static void verifyRules(ServerLevel level){
        var actual=com.jgeted.sagadyssey.core.MinecraftApi.saveRules(level);
        for(String key:savedRules.keySet())if(!savedRules.getStringOr(key, "").equals(actual.getStringOr(key, "")))throw new IllegalStateException("original rule changed: "+key);
    }
    private static void verifyOriginals(GameTestHelper h,ServerLevel level){
        for(int i=0;i<3;i++){
            var npc=NaturalVillageR1GameTests.actor(level,village,i);
            h.assertTrue(npc!=null&&npc.isAlive()&&npc.getUUID().equals(BODIES[i])&&npc.getCharacterId().equals(PEOPLE[i]),"same original living person and body "+i);
        }
    }
    private static JsonObject inventory(Container container){
        var out=new JsonObject();if(container==null){out.addProperty("unavailable",true);return out;}
        var sums=new TreeMap<String,Integer>();
        for(int i=0;i<container.getContainerSize();i++){ItemStack s=container.getItem(i);if(!s.isEmpty())sums.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString(),s.getCount(),Integer::sum);}
        sums.forEach(out::addProperty);return out;
    }
    private static void record(ServerLevel level,String phase){
        var row=new JsonObject();row.addProperty("phase",phase);row.addProperty("sampleTicks",samples);row.addProperty("gameTime",level.getGameTime());row.addProperty("dayTime",level.getDayTime());
        var persons=new JsonArray();var store=level.getDataStorage();var work=store.get(FieldworkData.FACTORY.type(FieldworkData.DATA_NAME));
        for(int i=0;i<3;i++){
            var n=NaturalVillageR1GameTests.actor(level,village,i);var p=new JsonObject();
            p.addProperty("person",n.getCharacterId().toString());p.addProperty("body",n.getUUID().toString());p.addProperty("activity",n.getWorkActivityDetail());
            p.addProperty("x",n.getX());p.addProperty("y",n.getY());p.addProperty("z",n.getZ());p.addProperty("health",n.getHealth());
            p.add("personal",inventory(n.getPersonalInventory()));p.add("cargo",inventory(n.getTransportInventory()));
            if(work!=null){var receipts=work.receiptsFor(n.getUUID());p.addProperty("receipts",receipts.size());
                p.addProperty("harvest",receipts.stream().filter(r->r.action().operation()==FieldworkAction.Operation.HARVEST).count());
                p.addProperty("plant",receipts.stream().filter(r->r.action().operation()==FieldworkAction.Operation.PLANT).count());
                p.addProperty("carrotReceiptNet",receipts.stream().flatMap(r->r.inventoryDeltas().stream()).filter(d->d.fingerprint().equals("minecraft:carrot#0")).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum());}
            persons.add(p);
        }
        row.add("people",persons);var flows=store.get(SupplyFlowData.FACTORY.type(SupplyFlowData.NAME));
        if(flows!=null){var stages=new JsonObject();for(var stage:SupplyFlowData.Stage.values())stages.addProperty(stage.name(),flows.routes().stream().filter(r->r.stage()==stage).count());row.add("routes",stages);}
        var boxes=new JsonArray();for(var position:List.of(new net.minecraft.core.BlockPos(10116293,-60,-13984250),new net.minecraft.core.BlockPos(10116298,-60,-13984250),new net.minecraft.core.BlockPos(10116305,-60,-13984250),new net.minecraft.core.BlockPos(10116307,-60,-13984250))){
            var b=new JsonObject();b.addProperty("position",position.toShortString());b.add("inventory",inventory(level.getBlockEntity(position) instanceof Container c?c:null));boxes.add(b);}
        row.add("containers",boxes);observations.add(row);
        Sagadyssey.LOGGER.info("FARM_PROFILE_STATE {}",row);
    }
    private static <T extends NbtSavedData> void ledger(MinecraftServer s,CompoundTag out,String key,NbtSavedData.Factory<T> factory,String name){
        var original=s.overworld().getDataStorage().get(factory.type(name));if(original!=null)out.put(key,original.save(new CompoundTag(),s.registryAccess()));
    }
    private static void snapshot(MinecraftServer server,String phase)throws Exception{
        var tag=new CompoundTag();tag.putLong("Time",server.overworld().getGameTime());tag.putLong("DayTime",server.overworld().getDayTime());tag.put("GameRules",com.jgeted.sagadyssey.core.MinecraftApi.saveRules(server.overworld()));
        ledger(server,tag,"Fieldwork",FieldworkData.FACTORY,FieldworkData.DATA_NAME);
        ledger(server,tag,"Supply",SupplyFlowData.FACTORY,SupplyFlowData.NAME);
        ledger(server,tag,"Needs",NeedRequestData.FACTORY,NeedRequestData.NAME);
        ledger(server,tag,"Rights",RightsStateData.FACTORY,RightsStateData.DATA_NAME);
        var people=new ListTag();for(int i=0;i<3;i++){var npc=NaturalVillageR1GameTests.actor(server.overworld(),village,i);if(npc!=null)people.add(com.jgeted.sagadyssey.core.MinecraftApi.saveEntity(npc));}tag.put("Entities",people);
        NbtIo.writeCompressed(tag,server.getWorldPath(LevelResource.ROOT).resolve("farm-profile-"+phase+".nbt"));
    }
    private static JsonObject distribution(int from,int to){
        var j=new JsonObject();j.addProperty("ticks",Math.max(0,to-from));if(to<=from)return j;
        long[] a=Arrays.copyOfRange(nanos,from,to);Arrays.sort(a);long sum=0;for(long n:a)sum+=n;
        j.addProperty("meanMs",sum/(double)a.length/1e6);j.addProperty("p50Ms",a[(int)Math.ceil(a.length*.50)-1]/1e6);j.addProperty("p95Ms",a[(int)Math.ceil(a.length*.95)-1]/1e6);j.addProperty("p99Ms",a[(int)Math.ceil(a.length*.99)-1]/1e6);j.addProperty("maxMs",a[a.length-1]/1e6);return j;
    }
    private static void writeMetrics(MinecraftServer server,String status,String failure)throws Exception{
        var out=new JsonObject();out.addProperty("status",status);out.addProperty("performanceVerdict","NOT_EVALUATED");out.addProperty("functionalCompletionVerdict","NOT_ASSERTED");out.addProperty("observer","GameTestServer controlled 20Hz by explicit Pre-event pacer; no player/client rendering; not GUI comparison");
        out.addProperty("samples",samples);out.addProperty("originalTime",savedTime);out.addProperty("originalDay",savedDay);out.addProperty("warmupTarget",WARMUP);out.addProperty("totalTarget",TOTAL);out.addProperty("failure",failure);
        out.add("all",distribution(0,samples));out.add("warmup",distribution(0,Math.min(WARMUP,samples)));out.add("steady",distribution(Math.min(WARMUP,samples),samples));
        if(samples>0){out.addProperty("wallObservationSeconds",(lastPost-firstPre)/1e9);out.addProperty("actualTicksPerWallSecond",samples/((lastPost-firstPre)/1e9));}
        if(samples>WARMUP&&steadyPre>0)out.addProperty("steadyActualTicksPerWallSecond",(samples-WARMUP)/((lastPost-steadyPre)/1e9));
        out.addProperty("measurement","After explicit Pre-event idle wait to Post LOWEST; no business work subtracted; observer/event work remains included; wall TPS includes pacing and overruns");
        var pacer=new JsonObject();pacer.addProperty("periodNanos",PERIOD_NANOS);pacer.addProperty("includesSetup",true);
        pacer.addProperty("wakeGuardNanos",WAKE_GUARD_NANOS);
        pacer.addProperty("catchUp",false);pacer.addProperty("skippedTicks",0);pacer.addProperty("allServerPacedTicks",allPacedTicks);
        pacer.addProperty("allServerIdleNanos",allIdleNanos);pacer.addProperty("allServerLateNanos",allLateNanos);pacer.addProperty("interrupted",pacerInterrupted);
        long measuredIdle=0,overBudget=0;var raw=new JsonArray();var idle=new JsonArray();var intervals=new JsonArray();var late=new JsonArray();
        for(int i=0;i<samples;i++){raw.add(nanos[i]);idle.add(idleNanos[i]);intervals.add(startIntervals[i]);late.add(lateNanos[i]);measuredIdle+=idleNanos[i];if(nanos[i]>PERIOD_NANOS)overBudget++;}
        pacer.addProperty("observationIdleNanos",measuredIdle);pacer.addProperty("businessTicksOver50ms",overBudget);
        pacer.add("idleNanoseconds",idle);pacer.add("startIntervalNanoseconds",intervals);pacer.add("lateStartNanoseconds",late);out.add("pacer",pacer);
        out.add("tickNanoseconds",raw);out.add("observations",observations);
        Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("farm-profile-metrics.json"),JSON.toJson(out),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
}
