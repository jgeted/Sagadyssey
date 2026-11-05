package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.life.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBrainData;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.politics.*;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.worldsim.lod.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Exact existing handoff, associated restart and bounded mixed-scale evidence. */
@GameTestHolder("sagadyssey_p8") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class LodContinuityP8GameTests {
    /** Reused read-only test capture; no success states or production commands. */
    public static CompoundTag associatedState(ServerLevel level){return links(level);}
    public static CompoundTag comparableState(CompoundTag tag){return comparable(tag);}
    private static CompoundTag links(ServerLevel l){var s=l.getServer();var t=new CompoundTag();
        t.put("Polity",NaturalPolityData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Office",PoliticalStateData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Exchange",SettlementExchangeData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("NaturalLife",NaturalVillageLifeData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Economy",NaturalVillageEconomyData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Brain",NpcBrainData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Activities",LifeActivityData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Lod",NpcLodData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Life",NpcLifeData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Households",HouseholdData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Rights",RightsStateData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Supply",SupplyFlowData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Needs",NeedRequestData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Transfers",TransferLedgerData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Logistics",LogisticsData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Buildings",SettlementRegistryData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Characters",CharacterRegistryData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Fieldwork",com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Conflict",NaturalConflictData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Encounters",CombatEncounterData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Injuries",InjuryData.get(s).save(new CompoundTag(),s.registryAccess()));
        return t;
    }
    /** Compare the exact entries of the two existing unordered maps/sets, never their iteration order. */
    private static CompoundTag comparable(CompoundTag source){var copy=source.copy();
        for(var entry:copy.getCompound("Transfers").getList("Receipts",Tag.TAG_COMPOUND)){
            var receipt=(CompoundTag)entry;var sorted=new TreeMap<UUID,CompoundTag>();
            for(var item:receipt.getList("Revisions",Tag.TAG_COMPOUND)){var revision=(CompoundTag)item;check(revision.hasUUID("Container")&&sorted.putIfAbsent(revision.getUUID("Container"),revision)==null,"unique revision map key");}
            var list=new ListTag();sorted.values().forEach(list::add);receipt.put("Revisions",list);
        }
        var knowledge=new TreeMap<String,CompoundTag>();
        for(var entry:copy.getCompound("Supply").getList("Known",Tag.TAG_COMPOUND)){
            var t=(CompoundTag)entry;var actor=SubjectRef.load(t.getCompound("Actor"));check(t.hasUUID("Point"),"valid knowledge endpoint");
            check(knowledge.putIfAbsent(actor.type()+":"+actor.id()+":"+t.getUUID("Point"),t)==null,"no duplicate actor/point knowledge");
        }
        var known=new ListTag();knowledge.values().forEach(known::add);copy.getCompound("Supply").put("Known",known);return copy;
    }
    private static final ResourceLocation EMPTY=ResourceLocation.parse("sagadyssey_p8:empty");
    private static Task active;
    private static GameTestHelper running;
    private static CompoundTag restored;
    private static final Set<UUID> admitted=new HashSet<>();
    interface Task {void tick();}
    private static String phase(){return System.getProperty("sagadyssey.society.phase","");}
    private static boolean enabled(){return phase().startsWith("p8-")&&!phase().startsWith("p8-combo");}
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static int count(Container c){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(Items.CARROT))n+=c.getItem(i).getCount();return n;}
    @net.neoforged.bus.api.SubscribeEvent public static void spawn(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e){
        if(!enabled()||!(e.getLevel() instanceof ServerLevel l)||!(l.getServer() instanceof GameTestServer)||!(e.getEntity() instanceof NpcBase n))return;
        if(!phase().endsWith("resume")){n.setNoAi(true);return;}
        if(!admitted.add(n.getUUID()))return;
        try{var saved=restored!=null?restored:NbtIo.readCompressed(l.getServer().getWorldPath(LevelResource.ROOT).resolve("p8-evidence.nbt"),NbtAccounter.unlimitedHeap());
            var t=saved.getList("Bodies",Tag.TAG_COMPOUND).stream().map(x->(CompoundTag)x).filter(x->x.getUUID("UUID").equals(n.getUUID())).findFirst().orElseThrow();
            check(n.getCharacterId().equals(t.getUUID("PersonProof"))&&n.isNoAi()==t.getBoolean("AIProof"),"same original person and captured AI state before work");
            check(NaturalVillageEconomyData.items(n.getPersonalInventory(),l.registryAccess()).equals(t.get("PersonalProof"))&&NaturalVillageEconomyData.items(n.getTransportInventory(),l.registryAccess()).equals(t.get("CargoProof")),"same physical personal and cargo items at admission");
            Sagadyssey.LOGGER.info("P8_ENTITY_LOAD body={} person={} originalInventory=true originalCargo=true beforeWork=true",n.getUUID(),n.getCharacterId());
        }catch(Exception x){throw new IllegalStateException("Original unloaded body required",x);}
    }
    @net.neoforged.bus.api.SubscribeEvent public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!enabled()||!(e.getServer() instanceof GameTestServer))return;active=null;restored=null;admitted.clear();var s=e.getServer();var l=s.overworld();
        try{if(!phase().endsWith("resume"))l.random.setSeed(20261022L);l.getStructureManager().getOrCreate(EMPTY).load(l.holderLookup(Registries.BLOCK),TagParser.parseTag("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
            if(phase().endsWith("resume")){
                restored=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("p8-evidence.nbt"),NbtAccounter.unlimitedHeap());var expected=restored.getCompound("Links").copy();
                for(var e2:expected.getCompound("Economy").getList("Regions",Tag.TAG_COMPOUND)){var r=(CompoundTag)e2;r.putBoolean("ObservedAway",true);r.putBoolean("ReturnRequested",false);}
                var actual=links(l);NbtIo.writeCompressed(actual,s.getWorldPath(LevelResource.ROOT).resolve("p8-before-first-tick.nbt"));
                check(comparable(actual).equals(comparable(expected)),"complete linked ledgers before first tick, only documented region observation flags differ");
                var level=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompound("Data");
                check(level.getLong("Time")==restored.getLong("SavedTime"),"full world game clock exactly preserved at snapshot");
                var savedRules=level.getCompound("GameRules");l.getGameRules().assignFrom(new GameRules(new com.mojang.serialization.Dynamic<>(NbtOps.INSTANCE,savedRules)),s);check(l.getGameRules().createTag().equals(savedRules),"entire saved GameRules restored before first tick, not native harness defaults");if(restored.contains("SavedRules"))check(savedRules.equals(restored.getCompound("SavedRules")),"exact frozen snapshot rules");Sagadyssey.LOGGER.info("P8_GAME_RULES_RESTORED beforeFirstTick=true allSavedRules=true randomTickSpeed={}",l.getGameRules().getInt(GameRules.RULE_RANDOMTICKING));
                s.getWorldData().overworldData().setGameTime(level.getLong("Time")+1);s.getWorldData().overworldData().setDayTime(level.getLong("DayTime")+1);
                Sagadyssey.LOGGER.info("P8_RESTART beforeFirstTick=true linkedLedgers=true savedTime={} resumedAt={} wallClockCatchup=false",level.getLong("Time"),l.getGameTime());
            }
        }catch(Exception x){throw new IllegalStateException("P8 associated world required",x);}
    }
    @net.neoforged.bus.api.SubscribeEvent public static void update(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){
        if(active==null||!enabled())return;try{active.tick();}catch(RuntimeException x){active=null;write(e.getServer().overworld(),"p8-failure-links.nbt",links(e.getServer().overworld()));Sagadyssey.LOGGER.error("P8_FAILURE {}",x.toString());running.runAtTickTime(running.getTick()+1,()->{throw new GameTestAssertException(x.getMessage());});}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){if(!enabled())return List.of();int limit=phase().contains("scale")?56000:24000;
        if(phase().contains("integrated"))limit=68000;final int ticks=limit;
        return List.of(new TestFunction("p8",phase().replace('-','_'),EMPTY.toString(),limit,0,true,h->{running=h;active=phase().contains("peace")?new Peace(h):phase().contains("integrated")?new Combined(h):phase().contains("scale")?new Scale(h):new Cargo(h);h.getLevel().getServer().getCommands().performPrefixedCommand(h.getLevel().getServer().createCommandSourceStack().withSuppressedOutput(),"tick rate 200");}));
    }
    static void paced(ServerLevel l){l.getServer().getCommands().performPrefixedCommand(l.getServer().createCommandSourceStack().withSuppressedOutput(),"tick sprint stop");l.getServer().getCommands().performPrefixedCommand(l.getServer().createCommandSourceStack().withSuppressedOutput(),"tick rate 200");}
    static void fast(ServerLevel l,int ticks){l.getServer().getCommands().performPrefixedCommand(l.getServer().createCommandSourceStack().withSuppressedOutput(),"tick sprint "+ticks);}
    static boolean away(ServerLevel l,NaturalVillageBuilding b){
        var r=NaturalVillageEconomyData.get(l.getServer()).row(b.buildingKey());if(r==null||!r.getString("Phase").equals("REMOTE")||!r.getBoolean("ObservedAway"))return false;
        for(int i=0;i<3;i++)if(NaturalVillageR1GameTests.actor(l,b,i)!=null)return false;
        for(var p:java.util.stream.Stream.concat(NaturalVillageLayout.stores(b).stream(),NaturalVillageLayout.crops().stream()).toList()){var at=NaturalVillageLayout.at(b,p);if(l.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4)!=null||((NaturalVillageEconomyData.RetainedChunks)(Object)l.getChunkSource().chunkMap).sagadyssey$retained(new net.minecraft.world.level.ChunkPos(at).toLong())!=null)return false;}
        return l.getChunkSource().getChunkNow(b.buildingOrigin().getX()>>4,b.buildingOrigin().getZ()>>4)==null;
    }
    static boolean near(ServerLevel l,NaturalVillageBuilding b){var r=NaturalVillageEconomyData.get(l.getServer()).row(b.buildingKey());check(r==null||!r.getString("Phase").equals("REVIEW"),"region review: "+r);if(r==null||!r.getString("Phase").equals("NEAR"))return false;for(int i=0;i<3;i++)if(NaturalVillageR1GameTests.actor(l,b,i)==null)return false;return true;}
    static ListTag bodies(ServerLevel l,NaturalVillageBuilding b){var list=new ListTag();for(int i=0;i<3;i++){var n=NaturalVillageR1GameTests.actor(l,b,i);check(n!=null&&n.getCharacterId().equals(NaturalVillageLifeData.person(b,i)),"original body/person");var t=n.saveWithoutId(new CompoundTag());t.putUUID("PersonProof",n.getCharacterId());t.putBoolean("AIProof",n.isNoAi());t.put("PersonalProof",NaturalVillageEconomyData.items(n.getPersonalInventory(),l.registryAccess()));t.put("CargoProof",NaturalVillageEconomyData.items(n.getTransportInventory(),l.registryAccess()));list.add(t);}return list;}
    static int physical(ServerLevel l,NaturalVillageBuilding b){int n=0;for(var p:NaturalVillageLayout.stores(b))n+=count((Container)l.getBlockEntity(NaturalVillageLayout.at(b,p)));for(int i=0;i<3;i++){var a=NaturalVillageR1GameTests.actor(l,b,i);n+=count(a.getPersonalInventory())+count(a.getTransportInventory());}return n;}
    static int virtual(ServerLevel l,CompoundTag r){int n=0;for(var e:r.getList("Stores",Tag.TAG_COMPOUND))n+=count(NaturalVillageEconomyData.inventory(((CompoundTag)e).getList("Items",Tag.TAG_COMPOUND),l.registryAccess()));return n;}
    static void write(ServerLevel l,String name,CompoundTag t){try{NbtIo.writeCompressed(t,l.getServer().getWorldPath(LevelResource.ROOT).resolve(name));}catch(Exception x){throw new IllegalStateException(x);}}
    static void fullSnapshot(ServerLevel l,CompoundTag t){try{var s=l.getServer();t.put("SavedRules",l.getGameRules().createTag());t.put("Links",links(l));t.putLong("SavedTime",l.getGameTime());write(l,"p8-evidence.nbt",t);s.saveEverything(true,true,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();var world=s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();var dst=world.resolveSibling("world-p8-snapshot");check(Files.notExists(dst),"never overwrite checkpoint");try(var walk=Files.walk(world)){for(var p:walk.filter(Files::isRegularFile).toList()){if(p.getFileName().toString().equals("session.lock"))continue;var to=dst.resolve(world.relativize(p));Files.createDirectories(to.getParent());Files.copy(p,to);}}Sagadyssey.LOGGER.info("P8_CHECKPOINT completeWorld=true actualUnloaded=true sameRoute={} time={}",t.hasUUID("Route")?t.getUUID("Route"):"integrated-or-peace",l.getGameTime());}catch(Exception x){throw new IllegalStateException(x);}}
    private static final class Cargo implements Task {
        final GameTestHelper h;final ServerLevel l;final NaturalVillageBuilding b;final ListTag events=new ListTag();
        int stage=0,boundary=0;long since=0;UUID routeId;CompoundTag point;ListTag savedBodies;final List<NpcBase> departed=new ArrayList<>();
        Cargo(GameTestHelper helper){h=helper;l=h.getLevel();b=restored==null?NaturalVillageR1GameTests.fixture(h,0):NaturalVillageLifeData.decode(restored.getCompound("Village"));
            if(restored!=null){routeId=restored.getUUID("Route");boundary=restored.getInt("Boundary");point=restored.getCompound("Point").copy();savedBodies=restored.getList("Bodies",Tag.TAG_COMPOUND).copy();events.addAll(restored.getList("Events",Tag.TAG_COMPOUND));stage=3;since=l.getGameTime();check(away(l,b),"restarted world starts actually unloaded, no test force-load");}}
        NpcBase npc(int i){return NaturalVillageR1GameTests.actor(l,b,i);}
        SupplyFlowData.Route route(){return routeId==null?SupplyFlowData.get(l.getServer()).routes().stream().filter(r->r.principal().equals(SubjectRef.household(NaturalVillageLifeData.home(b)))).findFirst().orElse(null):SupplyFlowData.get(l.getServer()).route(routeId);}
        CompoundTag point(){var r=route();var n=NeedRequestData.get(l.getServer()).request(r.need());var t=new CompoundTag();t.putString("Stage",r.stage().name());t.putUUID("Route",r.id());t.putUUID("Need",r.need());t.putInt("Reserved",n.reserved());t.putInt("Transit",n.inTransit());t.putInt("Delivered",n.delivered());t.putInt("Cargo",npc(1)==null?point.getInt("Cargo"):count(npc(1).getTransportInventory()));return t;}
        void event(String label){var t=new CompoundTag();t.putString("Label",label);t.putLong("Time",l.getGameTime());t.putInt("Boundary",boundary);t.put("Point",point());t.put("Economy",NaturalVillageEconomyData.get(l.getServer()).row(b.buildingKey()));t.putBoolean("AllFootprintChunksAbsent",away(l,b));events.add(t);Sagadyssey.LOGGER.info("P8_CARGO_EVENT label={} boundary={} point={} actuallyAway={}",label,boundary,t.get("Point"),t.getBoolean("AllFootprintChunksAbsent"));}
        public void tick(){var s=l.getServer();long now=l.getGameTime();var d=NaturalVillageEconomyData.get(s);check(h.getTick()<24000,"frozen cargo total deadline");
            if(stage==0){if(h.getTick()<160)return;check(near(l,b),"original initialized household");l.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0,s);npc(0).setNoAi(true);npc(2).setNoAi(true);
                var a=npc(0);var raw=(Container)l.getBlockEntity(NaturalVillageLayout.at(b,NaturalVillageLayout.RAW));var rights=RightsStateData.get(s);var from=ContainerIds.npcPersonal(a.getCharacterId());var to=NaturalVillageEconomyData.storeId(b,0);
                var req=new TransferRequest(UUID.randomUUID(),SubjectRef.character(a.getCharacterId()),TransferIntent.GIFT,List.of(new TransferLeg(from,to,new ItemFingerprint("minecraft:carrot",0),16)),List.of(new ExpectedRevision(from,rights.context(from).revision()),new ExpectedRevision(to,rights.context(to).revision())),"P8 declared finite original16 carrots placed in own known source before observation",now);
                var result=new TransferService(rights,TransferLedgerData.get(s)).submit(req,Map.of(from,new TransferEndpoint(from,a.getPersonalInventory()),to,new TransferEndpoint(to,raw)),now);check(result.successful()&&result.lawful()&&physical(l,b)==16,"lawful finite original source, no free stock");npc(1).setNoAi(false);stage=1;since=now;}
            if(stage==1){var r=route();check(now-since<6000,"normal route stage must progress, not indefinite wait");if(r==null)return;routeId=r.id();var expected=boundary==0?SupplyFlowData.Stage.HELD:boundary==1?SupplyFlowData.Stage.CARRYING:SupplyFlowData.Stage.DONE;if(r.stage()!=expected)return;
                npc(1).setNoAi(true);point=point();savedBodies=bodies(l,b);departed.clear();for(int i=0;i<3;i++)departed.add(npc(i));paced(l);event("BEFORE_RELEASE");NaturalVillageR1GameTests.visiting(l,b,false);stage=2;since=now;}
            if(stage==2){check(now-since<2400,"frozen actual unload deadline");if(!away(l,b)||departed.stream().anyMatch(n->!n.isRemoved()||n.getRemovalReason()!=net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK))return;event("ACTUALLY_UNLOADED");since=now;stage=3;}
            if(stage==3){check(away(l,b),"bodies and all registered chunks remain absent throughout remote observation");check(point().equals(point),"original cargo/need/route counters frozen while unloaded");if(now-since<600||d.backlog(now)>2)return;
                if(phase().equals("p8-carry-save")&&boundary==1){var t=new CompoundTag();t.put("Village",NaturalVillageLifeData.get(s).village(b.buildingKey()));t.putUUID("Route",routeId);t.putInt("Boundary",boundary);t.put("Point",point.copy());t.put("Bodies",savedBodies.copy());t.put("Events",events.copy());fullSnapshot(l,t);active=null;h.succeed();return;}
                event("RETURN_REQUEST");NaturalVillageR1GameTests.visiting(l,b,true);stage=4;since=now;}
            if(stage==4){check(now-since<2400,"frozen return deadline");if(!near(l,b))return;check(point().equals(point)&&physical(l,b)==16,"same original cargo, reservation and delivered counts;16 exact physical stock");
                for(int i=0;i<3;i++){check(npc(i).getCharacterId().equals(NaturalVillageLifeData.person(b,i))&&NpcLodRuntime.loaded(npc(i)),"same three original valid bodies, no replacement");var old=savedBodies.getCompound(i);check(NaturalVillageEconomyData.items(npc(i).getPersonalInventory(),s.registryAccess()).equals(old.get("PersonalProof"))&&NaturalVillageEconomyData.items(npc(i).getTransportInventory(),s.registryAccess()).equals(old.get("CargoProof")),"same original saved bags/cargo after return");}
                if(restored!=null)check(admitted.size()==3,"all original physical entity inventories checked before first work after process restart");event("BOUNDARY_PASS");npc(1).setNoAi(false);since=now;stage=1;
                if(++boundary==3){var r=route();var n=NeedRequestData.get(s).request(r.need());check(r.stage()==SupplyFlowData.Stage.DONE&&n.delivered()==16&&n.reserved()==0&&n.inTransit()==0&&count(npc(1).getTransportInventory())==0,"one actual completed16 delivery, no replay");
                    check(RightsStateData.get(s).reservationsForToken(r.token(),now).isEmpty(),"no orphan original hold");var out=new CompoundTag();out.put("Events",events.copy());out.put("Links",links(l));out.put("Bodies",bodies(l,b));out.put("Village",NaturalVillageLifeData.get(s).village(b.buildingKey()));out.putInt("PhysicalCarrots",physical(l,b));out.putInt("Delivered",n.delivered());out.putBoolean("IndependentRestart",restored!=null);write(l,"p8-cargo-result.nbt",out);Sagadyssey.LOGGER.info("P8_CARGO_ACCEPT states=HELD,CARRYING,DONE stock=16 delivered=16 independentRestart={}",restored!=null);active=null;h.succeed();}
            }
        }
    }
    static final class RemoteProbe {
        final ServerLevel l;final NaturalVillageBuilding b;final int initial;final ListTag samples=new ListTag();int stage;long start,requested;ListTag frozen;final List<NpcBase> departed=new ArrayList<>();
        RemoteProbe(ServerLevel level,NaturalVillageBuilding village){this(level,village,16);}
        RemoteProbe(ServerLevel level,NaturalVillageBuilding village,int initialCarrots){l=level;b=village;initial=initialCarrots;}
        void release(){for(int i=0;i<3;i++)NaturalVillageR1GameTests.actor(l,b,i).setNoAi(false);frozen=bodies(l,b);departed.clear();for(int i=0;i<3;i++)departed.add(NaturalVillageR1GameTests.actor(l,b,i));paced(l);NaturalVillageR1GameTests.visiting(l,b,false);stage=1;requested=l.getGameTime();}
        boolean observe(){var d=NaturalVillageEconomyData.get(l.getServer());var r=d.row(b.buildingKey());long now=l.getGameTime();
            if(stage==1){check(now-requested<2400,"remote initialization unload deadline");if(!away(l,b)||departed.stream().anyMatch(n->!n.isRemoved()||n.getRemovalReason()!=net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK))return false;check(r.getBoolean("Farmer"),"actual existing qualified farmer captured, not free virtual labor");stage=2;start=now;Sagadyssey.LOGGER.info("P8_REMOTE_BEGIN settlement={} actuallyUnloaded=true originals=3 initialFinite=16",b.settlementId());}
            if(stage==2){check(away(l,b),"remote observation requires physical chunks and original entities absent");var c=RemoteCropClock.load(r.getCompound("Clock"));check(!r.getString("Phase").equals("REVIEW"),"no unexplained regional review");
                int bags=0;for(var e:frozen){var t=(CompoundTag)e;bags+=count(NaturalVillageEconomyData.inventory(t.getList("PersonalProof",Tag.TAG_COMPOUND),l.registryAccess()))+count(NaturalVillageEconomyData.inventory(t.getList("CargoProof",Tag.TAG_COMPOUND),l.registryAccess()));}
                check(initial+c.produced()-c.planted()==bags+virtual(l,r)+r.getLong("Meals"),"remote initial + gross outputs - actual seeds = frozen real bags + unique authoritative stores + actual meals");
                if((now-start)%200==0){var t=new CompoundTag();t.putLong("At",now);t.putLong("Backlog",d.backlog(now));t.putLong("Produced",c.produced());t.putLong("Planted",c.planted());t.putLong("Meals",r.getLong("Meals"));t.putInt("VirtualStock",virtual(l,r));t.putBoolean("ActuallyAbsent",true);samples.add(t);}
                return true;}
            return false;
        }
        boolean finish(){var s=l.getServer();long now=l.getGameTime();if(stage<=2){if(!observe()||now-start<48000)return false;var r=NaturalVillageEconomyData.get(s).row(b.buildingKey());var c=RemoteCropClock.load(r.getCompound("Clock"));check(c.produced()>0&&r.getLong("Meals")>0,"true unloaded harvest and real food consumption within frozen window");paced(l);NaturalVillageR1GameTests.visiting(l,b,true);stage=3;requested=now;return false;}
            if(stage==3){check(now-requested<2400,"remote return deadline");if(!near(l,b))return false;for(int i=0;i<3;i++)NaturalVillageR1GameTests.actor(l,b,i).setNoAi(true);var r=NaturalVillageEconomyData.get(s).row(b.buildingKey());var c=RemoteCropClock.load(r.getCompound("Clock"));check(initial+c.produced()-c.planted()==physical(l,b)+r.getLong("Meals"),"returned original physical projection conserves outputs,seed,food");check(!NaturalVillageEconomyData.get(s).resume(s,b.buildingKey()),"no duplicate projection");var out=new CompoundTag();out.put("Region",r);out.put("Bodies",bodies(l,b));out.put("Samples",samples);out.putLong("Observation",now-start);out.putLong("RecoveryTicks",now-requested);write(l,"p8-remote-"+b.settlementId()+".nbt",out);stage=4;Sagadyssey.LOGGER.info("P8_REMOTE_RETURN settlement={} duration={} recovery={} gross={} seed={} meals={} sameOriginals=true noDuplicateProjection=true",b.settlementId(),now-start,now-requested,c.produced(),c.planted(),r.getLong("Meals"));}return stage==4;}
    }
    private static final class Combined implements Task {
        final GameTestHelper h;final ServerLevel l;final NaturalVillageBuilding seller,buyer;final RemoteProbe probe;final Set<String> meals=new HashSet<>();final ListTag events=new ListTag();int stage;long since;boolean paidCargo,foodCargo,accelerated;UUID dealId;ListTag buyerFrozen;
        Combined(GameTestHelper helper){h=helper;l=h.getLevel();seller=restored==null?NaturalVillageR1GameTests.fixture(h,0,NaturalVillageLayout.FARM_V2):NaturalVillageLifeData.decode(restored.getCompound("Seller"));buyer=restored==null?NaturalVillageR1GameTests.fixture(h,192,NaturalVillageLayout.WOOD_V2):NaturalVillageLifeData.decode(restored.getCompound("Buyer"));probe=new RemoteProbe(l,seller,24);
            if(restored!=null){stage=2;since=l.getGameTime();probe.stage=2;probe.start=restored.getLong("RemoteStart");probe.frozen=restored.getList("SellerBodies",Tag.TAG_COMPOUND).copy();buyerFrozen=restored.getList("BuyerFrozen",Tag.TAG_COMPOUND).copy();probe.samples.addAll(restored.getList("RemoteSamples",Tag.TAG_COMPOUND));events.addAll(restored.getList("Events",Tag.TAG_COMPOUND));for(var e:restored.getList("Meals",Tag.TAG_STRING))meals.add(e.getAsString());check(away(l,seller)&&away(l,buyer),"same saved two regions start actually unloaded");}}
        NpcBase npc(NaturalVillageBuilding b,int i){return NaturalVillageR1GameTests.actor(l,b,i);}
        Container store(NaturalVillageBuilding b,int i){return (Container)l.getBlockEntity(NaturalVillageLayout.at(b,NaturalVillageLayout.stores(b).get(i)));}
        CompoundTag pact(){return CharterTradeGovernance.pact(l.getServer(),buyer.settlementId(),seller.settlementId());}
        CompoundTag rule(NaturalVillageBuilding b){return NaturalPolityData.get(l.getServer()).site(b.settlementId()).getCompound("ReserveRule");}
        int command(NaturalVillageBuilding b,String action,NaturalVillageBuilding peer){var n=npc(b,1);try{return l.getServer().getCommands().getDispatcher().execute("saga politics "+action+" "+b.settlementId()+(peer==null?"":" "+peer.settlementId()),l.getServer().createCommandSourceStack().withEntity(n).withLevel(l).withPosition(n.position()).withPermission(0).withSuppressedOutput());}catch(Exception x){throw new IllegalStateException(x);}}
        void event(String label){var t=new CompoundTag();t.putString("Label",label);t.putLong("At",l.getGameTime());t.put("Pact",pact());t.put("PolicySeller",rule(seller));t.put("Exchange",SettlementExchangeData.get(l.getServer()).save(new CompoundTag(),l.registryAccess()));t.put("Region",NaturalVillageEconomyData.get(l.getServer()).row(seller.buildingKey()));events.add(t);Sagadyssey.LOGGER.info("P8_INTEGRATED_EVENT label={} at={} policy={} pact={} trades={}",label,l.getGameTime(),rule(seller).getString("State"),pact().getString("State"),SettlementExchangeData.get(l.getServer()).deals());}
        void corridor(){int chunks=0;int minX=Math.min(seller.buildingOrigin().getX(),buyer.buildingOrigin().getX())-32,maxX=Math.max(seller.buildingOrigin().getX(),buyer.buildingOrigin().getX())+32,minZ=Math.min(seller.buildingOrigin().getZ(),buyer.buildingOrigin().getZ())-32,maxZ=Math.max(seller.buildingOrigin().getZ(),buyer.buildingOrigin().getZ())+32;for(int x=minX>>4;x<=maxX>>4;x++)for(int z=minZ>>4;z<=maxZ>>4;z++){check(++chunks<=160,"existing192-block charter corridor under160chunks");l.setChunkForced(x,z,true);l.getChunk(x,z);}NaturalVillageR1GameTests.visiting(l,buyer,true);}
        public void tick(){long now=l.getGameTime();var s=l.getServer();check(h.getTick()<68000,"frozen integrated total deadline");for(var b:List.of(seller,buyer))if(near(l,b))NaturalVillageR1GameTests.observe(l,b,meals);
            if(stage==0){if(h.getTick()<160)return;check(near(l,buyer)&&near(l,seller),"two supported natural initialized households");check(count(store(seller,1))==0&&count(store(buyer,1))==0,"no trade food preinserted: only future real harvest can supply it");
                for(var b:List.of(seller,buyer)){check(store(b,3).getItem(2).isEmpty(),"finite initial food uses an empty slot and preserves original template wool3");store(b,3).setItem(2,new ItemStack(Items.CARROT,8));check(command(b,"reserve enable",null)==1,"both real legal officials enable original public policy");}store(buyer,2).setItem(1,new ItemStack(Items.OAK_LOG,2));NpcLifeData.get(s).setHunger(npc(buyer,1).getCharacterId(),850,now);stage=1;since=now;}
            if(stage==1){check(now-since<1600,"initial real public-policy opening deadline");if(!rule(seller).getString("State").equals("OPEN")||!rule(buyer).getString("State").equals("OPEN"))return;
                check(command(buyer,"pact propose",seller)==1&&command(seller,"pact approve",buyer)==1&&pact().getString("State").equals("ACTIVE"),"own proposer and independent peer approve through product commands");check(SettlementExchangeData.get(s).deals().isEmpty(),"active pact cannot manufacture missing food");event("INITIAL_APPROVED_NO_FOOD");buyerFrozen=bodies(l,buyer);NaturalVillageR1GameTests.visiting(l,buyer,false);probe.release();stage=2;since=now;}
            if(stage==2){if(!probe.observe())return;check(now-since<2400||away(l,buyer),"buyer must also fully unload within frozen deadline");if(!away(l,buyer))return;if(!accelerated){fast(l,48000);accelerated=true;}check(SettlementExchangeData.get(s).deals().isEmpty(),"no false trade from unavailable physical producer");if(now-probe.start<40)return;check(rule(seller).getString("State").equals("CLOSED")&&pact().getString("State").equals("PAUSED"),"actual public-store unavailability closes governance and pauses pact, never bypasses it");if(now-probe.start<48000)return;
                var r=NaturalVillageEconomyData.get(s).row(seller.buildingKey());check(virtual(l,r)>8&&RemoteCropClock.load(r.getCompound("Clock")).produced()>0&&r.getList("Receipts",Tag.TAG_COMPOUND).stream().anyMatch(e->((CompoundTag)e).getString("Kind").equals("SUPPLY_DELIVERED")),"real unloaded finite harvest and legal normal household supply precede trade");event("REMOTE_HARVEST_SUPPLY_PACT_PAUSED");
                if(phase().equals("p8-integrated-save")){var t=new CompoundTag();t.put("Seller",NaturalVillageLifeData.get(s).village(seller.buildingKey()));t.put("Buyer",NaturalVillageLifeData.get(s).village(buyer.buildingKey()));t.put("SellerBodies",probe.frozen.copy());t.put("BuyerFrozen",buyerFrozen.copy());var all=probe.frozen.copy();all.addAll(buyerFrozen.copy());t.put("Bodies",all);t.putLong("RemoteStart",probe.start);t.put("RemoteSamples",probe.samples.copy());t.put("Events",events.copy());var ms=new ListTag();meals.stream().sorted().forEach(m->ms.add(StringTag.valueOf(m)));t.put("Meals",ms);fullSnapshot(l,t);active=null;h.succeed();return;}
                stage=3;since=now;
            }
            if(stage==3){if(!probe.finish())return;NaturalVillageR1GameTests.visiting(l,buyer,true);if(!near(l,buyer))return;corridor();npc(seller,1).setNoAi(false);check(count(store(seller,1))>=12,"remote-grown food actually projected into original household source");event("RETURNED_FOOD_POLICY_RECHECK");stage=4;since=now;}
            if(stage==4){check(now-since<1600,"original policy continuous recovery deadline");check(!pact().getString("State").equals("ACTIVE")&&SettlementExchangeData.get(s).deals().isEmpty(),"reloading and food do not automatically restore bilateral consent; original PENDING awaits both renewed signatures");if(!rule(seller).getString("State").equals("OPEN")||!rule(buyer).getString("State").equals("OPEN"))return;
                check(command(buyer,"pact approve",seller)==1&&!pact().getString("State").equals("ACTIVE"),"one renewed consent cannot replace peer");stage=5;since=now;event("ONE_NEW_APPROVAL");}
            if(stage==5){check(SettlementExchangeData.get(s).deals().isEmpty(),"ordinary new exchange gate remains closed for single approval");if(now-since<200)return;check(command(seller,"pact approve",buyer)==1&&pact().getString("State").equals("ACTIVE"),"independent second renewed approval");npc(buyer,1).setNoAi(false);fast(l,12000);stage=6;since=now;event("BOTH_NEW_APPROVALS_REAL_FOOD_AVAILABLE");}
            if(stage==6){check(now-since<12000,"normal physical192-block exchange completion deadline");var ds=SettlementExchangeData.get(s).deals();check(ds.size()<=1,"one original bounded exchange, no duplicate commitments");if(ds.isEmpty())return;var deal=ds.getFirst();dealId=deal.getUUID("Id");
                if(deal.hasUUID("PayRoute")){var p=SupplyFlowData.get(s).route(deal.getUUID("PayRoute"));paidCargo|=p.stage()==SupplyFlowData.Stage.CARRYING&&SupplyFlowService.count(npc(buyer,1).getTransportInventory(),new ItemFingerprint("minecraft:oak_log",0))==2;}
                if(deal.hasUUID("FoodRoute")){var f=SupplyFlowData.get(s).route(deal.getUUID("FoodRoute"));foodCargo|=f.stage()==SupplyFlowData.Stage.CARRYING&&count(npc(buyer,1).getTransportInventory())==4;}
                if(!deal.getString("State").equals("DONE")||meals.isEmpty()||NpcLifeData.get(s).needs(npc(buyer,1).getCharacterId()).hunger()>=850)return;
                check(paidCargo&&foodCargo&&deal.getInt("FoodOwed")==0,"same original courier carried both real goods and completed original debt");
                for(String field:List.of("PayRoute","FoodRoute")){var route=SupplyFlowData.get(s).route(deal.getUUID(field));var need=NeedRequestData.get(s).request(route.need());check(route.stage()==SupplyFlowData.Stage.DONE&&need.reserved()==0&&need.inTransit()==0&&need.delivered()==(field.equals("PayRoute")?2:4)&&route.carrierCharacter().equals(npc(buyer,1).getCharacterId()),"normal exact actual two-sided settlement");}
                check(SupplyFlowService.count(store(seller,1),new ItemFingerprint("minecraft:oak_log",0))==2,"other real household received original two-log price");
                var r=NaturalVillageEconomyData.get(s).row(seller.buildingKey());var c=RemoteCropClock.load(r.getCompound("Clock"));int actual=physical(l,seller)+physical(l,buyer);check(48+c.produced()-c.planted()==actual+r.getLong("Meals")+meals.size(),"48 original finite carrots + real remote harvest - seeds = actual stock + distinct near and remote consumption");
                if(restored!=null)check(admitted.size()==6,"same six original entity inventories checked across complete process restart");event("REAL_EXCHANGE_AND_LIFE_RECOVERED");var t=new CompoundTag();t.put("Events",events);t.put("Links",links(l));t.put("SellerBodies",bodies(l,seller));t.put("BuyerBodies",bodies(l,buyer));t.putInt("InitialCarrots",48);t.putLong("GrossHarvest",c.produced());t.putLong("Seeds",c.planted());t.putLong("RemoteMeals",r.getLong("Meals"));t.putInt("NearMeals",meals.size());t.putInt("PhysicalCarrots",actual);t.putInt("PriceLogsReceived",2);t.putInt("Debt",0);t.putUUID("Deal",dealId);t.putBoolean("Restart",restored!=null);write(l,"p8-integrated-result.nbt",t);Sagadyssey.LOGGER.info("P8_INTEGRATED_ACCEPT originalCourier={} realRemoteHarvest={} seed={} remoteMeals={} nearMeals={} stock={} priceReceived=2 debt=0 reapproved=true restart={}",npc(buyer,1).getCharacterId(),c.produced(),c.planted(),r.getLong("Meals"),meals.size(),actual,restored!=null);active=null;h.succeed();
            }
        }
    }
    private static final class Peace implements Task {
        final GameTestHelper h;final ServerLevel l;final NaturalVillageBuilding seller,buyer;final ListTag events=new ListTag();final List<NpcBase> departing=new ArrayList<>();int stage;long since;CompoundTag expectedConsent;UUID trade;int initialWounds;
        Peace(GameTestHelper helper){h=helper;l=h.getLevel();check(restored!=null,"current-candidate P7 final complete world is required");seller=NaturalVillageLifeData.decode(restored.getCompound("Seller"));buyer=NaturalVillageLifeData.decode(restored.getCompound("Buyer"));trade=restored.getCompound("Trace").getUUID("Deal");since=l.getGameTime();NaturalVillageR1GameTests.visiting(l,seller,true);NaturalVillageR1GameTests.visiting(l,buyer,true);}
        NpcBase npc(NaturalVillageBuilding b,int i){return NaturalVillageR1GameTests.actor(l,b,i);}
        CompoundTag pact(){return CharterTradeGovernance.pact(l.getServer(),buyer.settlementId(),seller.settlementId());}
        CompoundTag debt(){return SettlementExchangeData.get(l.getServer()).deals().stream().filter(d->d.getUUID("Id").equals(trade)).findFirst().orElseThrow();}
        void invariant(){var s=l.getServer();check(debt().getInt("FoodOwed")==4&&debt().getString("State").equals("PAY"),"original prepaid4-food debt and seller duty never forgiven");check(pact().getString("State").equals("PAUSED"),"P6 pact stays independently PAUSED across mode/restart/truce");check(LimitedConflictGovernance.record(s,trade).equals(expectedConsent),"same authoritative bilateral truce/signatures unchanged");check(NaturalConflictData.get(s).disputes().stream().noneMatch(t->t.getString("State").equals("ACTIVE")),"no restarted war despite old grievance");var oldWounds=restored.getCompound("Links").getCompound("Injuries").getList("Wounds",Tag.TAG_COMPOUND);check(InjuryData.get(s).wounds().size()==oldWounds.size(),"no duplicated or new wound under truce");for(var e:oldWounds){var before=(CompoundTag)e;var after=InjuryData.get(s).wound(before.getUUID("Id"));check(after!=null&&after.patientCharacterId().equals(before.getUUID("Patient"))&&Objects.equals(after.attackerCharacterId(),before.hasUUID("Attacker")?before.getUUID("Attacker"):null)&&after.inflictedAt()==before.getLong("InflictedAt"),"same original wound identity, not replaced or erased; ordinary existing game-time progression remains permitted");}}
        boolean authorityAway(){var row=NaturalVillageEconomyData.get(l.getServer()).row(buyer.buildingKey());if(!row.getString("Phase").equals("REMOTE"))return false;for(int i:new int[]{0,2})if(npc(buyer,i)!=null)return false;for(var p:NaturalVillageLayout.stores(buyer)){var at=NaturalVillageLayout.at(buyer,p);if(l.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4)!=null)return false;}return true;}
        void event(String label){var t=new CompoundTag();t.putString("Label",label);t.putLong("At",l.getGameTime());t.putBoolean("BuyerHomeAuthorityRemote",authorityAway());t.putBoolean("OriginalCourierStillPhysical",npc(buyer,1)!=null);t.put("Debt",debt());t.put("Pact",pact());t.put("Truce",LimitedConflictGovernance.record(l.getServer(),trade));t.putBoolean("SellerReallyUnloaded",away(l,seller));events.add(t);Sagadyssey.LOGGER.info("P8_PEACE_EVENT label={} sameDebt=4 pact=PAUSED sellerActuallyUnloaded={}",label,t.getBoolean("SellerReallyUnloaded"));}
        public void tick(){long now=l.getGameTime();var s=l.getServer();
            if(stage==0){check(now-since<2400,"original six must load without respawn");if(!near(l,buyer)||!near(l,seller))return;check(admitted.size()==6,"all six original entities proved at admission across independent P7-world restart");expectedConsent=LimitedConflictGovernance.record(s,trade);check(expectedConsent!=null&&expectedConsent.getBoolean("Truce"),"original formal bilateral truce");invariant();event("RESTARTED_SAME_TRUCE_DEBT");
                for(var b:List.of(seller,buyer))for(int i=0;i<3;i++)npc(b,i).setNoAi(true);for(int i:new int[]{0,2})departing.add(npc(buyer,i));
                NaturalVillageR1GameTests.visiting(l,seller,false);NaturalVillageR1GameTests.visiting(l,buyer,false);
                int minX=Math.min(seller.buildingOrigin().getX(),buyer.buildingOrigin().getX())-64,maxX=Math.max(seller.buildingOrigin().getX(),buyer.buildingOrigin().getX())+64,minZ=Math.min(seller.buildingOrigin().getZ(),buyer.buildingOrigin().getZ())-64,maxZ=Math.max(seller.buildingOrigin().getZ(),buyer.buildingOrigin().getZ())+64;
                for(long cp: l.getForcedChunks().toLongArray()){var c=new net.minecraft.world.level.ChunkPos(cp);if(c.x>=minX>>4&&c.x<=maxX>>4&&c.z>=minZ>>4&&c.z<=maxZ>>4)l.setChunkForced(c.x,c.z,false);}
                // Keep only the buyer's real pending-courier bubble; do not suppress production leases.
                paced(l);stage=1;since=now;
            }
            invariant();
            if(stage==1){check(now-since<2400,"truce seller actual unload deadline, original courier lease cannot be deleted");if(!authorityAway()||departing.stream().anyMatch(n->!n.isRemoved()||n.getRemovalReason()!=net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK))return;event("BUYER_HOME_REMOTE_ORIGINAL_COURIER_STILL_PHYSICAL");stage=2;since=now;}
            if(stage==2){check(authorityAway(),"buyer home authority remote throughout truce window; retained physical halo explicitly permitted by original courier lease");if(now-since<2400)return;event("REMOTE_TRUCE_STABLE_2400");NaturalVillageR1GameTests.visiting(l,buyer,true);stage=3;since=now;}
            if(stage==3){check(now-since<2400,"same seller return deadline");if(!near(l,seller)||!near(l,buyer))return;for(var b:List.of(seller,buyer))for(int i=0;i<3;i++){var n=npc(b,i);var old=restored.getList("Bodies",Tag.TAG_COMPOUND).stream().map(e->(CompoundTag)e).filter(t->t.getUUID("UUID").equals(n.getUUID())).findFirst().orElseThrow();n.setNoAi(old.getBoolean("AIProof"));check(NpcLodRuntime.loaded(n),"same original person checkpoint after truce return");}
                event("ORIGINAL_AI_RESTORED_UNDER_TRUCE");stage=4;since=now;
            }
            if(stage==4){for(var b:List.of(seller,buyer))for(int i=0;i<3;i++)check(npc(b,i).isAlive()&&npc(b,i).getTarget()==null,"no false death or retained attack under truce");if(now-since<600)return;
                var out=new CompoundTag();out.put("Events",events);out.put("Links",links(l));out.put("SellerBodies",bodies(l,seller));out.put("BuyerBodies",bodies(l,buyer));out.putInt("Debt",4);out.putString("Pact","PAUSED");out.putLong("UnloadedTruceObservation",2400);out.putLong("NormalAiPostReturnObservation",600);write(l,"p8-peace-result.nbt",out);Sagadyssey.LOGGER.info("P8_PEACE_ACCEPT independentRestart=true buyerHomeAuthorityRemote=true originalCourierPhysical=true retainedCourierHalo=true debt=4 pact=PAUSED truceSame=true realOldAiStable=true");active=null;h.succeed();}
        }
    }
    private static final class Scale implements Task {
        final GameTestHelper h;final ServerLevel l;final List<NaturalVillageBuilding> villages=new ArrayList<>();final List<RemoteProbe> probes=new ArrayList<>();final Set<String> meals=new HashSet<>();final ListTag samples=new ListTag();
        int stage;long start,wall,recoveredAt;long peakHeap;final List<Long> tickTimes=new ArrayList<>();int maxRoutes;long maxRouteAge,maxNeedWait;boolean observed;
        Scale(GameTestHelper helper){h=helper;l=h.getLevel();int n=phase().endsWith("expanded")?4:2;for(int i=0;i<n;i++)villages.add(NaturalVillageR1GameTests.fixture(h,i*384,NaturalVillageLayout.LIVING));}
        public void tick(){long now=l.getGameTime();var s=l.getServer();var d=NaturalVillageEconomyData.get(s);check(h.getTick()<56000,"frozen total scale deadline");
            if(stage==0){if(h.getTick()<160)return;for(int i=0;i<villages.size();i++){var b=villages.get(i);check(near(l,b),"all supported initialized originals");for(int j=0;j<3;j++)NaturalVillageR1GameTests.actor(l,b,j).setNoAi(false);if(i%2==1){var p=new RemoteProbe(l,b);probes.add(p);p.release();}}stage=1;start=now;}
            if(stage==1){check(now-start<2400,"mixed region setup deadline");boolean all=true;for(var p:probes)all&=p.observe();if(!all)return;LodMetrics.reset(s);start=now;wall=System.nanoTime();fast(l,48000);stage=2;}
            if(stage==2){tickTimes.add(s.getTickTimesNanos()[Math.floorMod(s.getTickCount()-1,100)]);for(var p:probes)p.observe();for(int i=0;i<villages.size();i+=2){var b=villages.get(i);check(near(l,b),"near originals remain loaded and active");NaturalVillageR1GameTests.observe(l,b,meals);}
                long heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();peakHeap=Math.max(peakHeap,heap);var routes=SupplyFlowData.get(s).routes().stream().filter(r->!r.closed()).toList();maxRoutes=Math.max(maxRoutes,routes.size());
                for(var r:routes){var need=NeedRequestData.get(s).request(r.need());maxRouteAge=Math.max(maxRouteAge,now-need.createdAt());}
                for(var n:NeedRequestData.get(s).requests())if(!n.status().terminal()&&n.status()!=NeedRequestStatus.DELIVERED)maxNeedWait=Math.max(maxNeedWait,now-n.createdAt());
                if((now-start)%200==0){var t=new CompoundTag();t.putLong("At",now);t.putLong("HeapBytes",heap);t.putInt("LoadedOriginals",villages.size()/2*3);t.putInt("ActuallyUnloadedOriginals",villages.size()/2*3);t.putLong("RemoteBacklog",d.backlog(now));t.putInt("OpenRoutes",routes.size());t.putInt("Reservations",RightsStateData.get(s).reservations().size());t.putLong("MaxRouteAge",maxRouteAge);t.putLong("MaxNeedWait",maxNeedWait);t.putString("ServerTick",LodMetrics.snapshot(s,LodMetrics.Kind.SERVER_TICK).toString());samples.add(t);}
                if(now-start<48000)return;var metric=LodMetrics.snapshot(s,LodMetrics.Kind.SERVER_TICK);var remote=LodMetrics.snapshot(s,LodMetrics.Kind.REGION_ECONOMY);check(metric.p95Nanos()<=50_000_000&&remote.p95Nanos()<=2_000_000,"frozen actual tick and region processing budgets");check(peakHeap<=4096L*1024*1024&&d.backlog(now)<=2,"frozen heap and bounded remote backlog");stage=3;observed=true;}
            if(stage==3){boolean finished=true;for(var p:probes)finished&=p.finish();if(!finished)return;for(var b:villages)for(int j=0;j<3;j++)NaturalVillageR1GameTests.actor(l,b,j).setNoAi(false);stage=4;recoveredAt=now;}
            if(stage==4){for(var b:villages)NaturalVillageR1GameTests.observe(l,b,meals);if(now-recoveredAt<600)return;var identities=new HashSet<UUID>();int physical=0,nearDelta=0;long output=0,input=0,remoteMeals=0;
                for(int i=0;i<villages.size();i++){var b=villages.get(i);for(int j=0;j<3;j++){var n=NaturalVillageR1GameTests.actor(l,b,j);check(n!=null&&n.getCharacterId().equals(NaturalVillageLifeData.person(b,j))&&identities.add(n.getCharacterId())&&NpcLodRuntime.loaded(n),"same unique living original after mixed-scale return");n.setNoAi(true);}physical+=physical(l,b);
                    {NaturalVillageR1GameTests.observe(l,b,meals);for(var r:com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData.get(s).receiptsFor(NaturalVillageLifeData.body(b,0)))for(var delta:r.inventoryDeltas())if(delta.fingerprint().equals("minecraft:carrot#0"))nearDelta+=delta.quantity();}
                    if(i%2==1){var r=d.row(b.buildingKey());var c=RemoteCropClock.load(r.getCompound("Clock"));output+=c.produced();input+=c.planted();remoteMeals+=r.getLong("Meals");}}
                check(villages.size()*16+nearDelta+output-input==physical+meals.size()+remoteMeals,"mixed-scale exact physical items+near/remote outputs/seed/actual meals");
                for(var r:SupplyFlowData.get(s).routes())if(!r.closed()){var n=NeedRequestData.get(s).request(r.need());check(now-n.createdAt()<2400,"no unexplained old live route at end");}
                long[] allTicks=tickTimes.stream().mapToLong(Long::longValue).toArray();long[] ordered=allTicks.clone();Arrays.sort(ordered);long fullP95=ordered[(int)Math.ceil(ordered.length*.95)-1];double average=Arrays.stream(allTicks).average().orElseThrow();check(fullP95<=50_000_000,"whole48000-tick observation P95, not only final rolling window");var t=new CompoundTag();t.putLongArray("AllObservedServerTickNanos",allTicks);t.putLong("WholeWindowP95Nanos",fullP95);t.putDouble("WholeWindowAverageNanos",average);t.putDouble("TickCapacityTPSCapped20",Math.min(20,1e9/average));t.putString("ProcessorIdentifier",System.getenv("PROCESSOR_IDENTIFIER"));t.putLong("WorldSeed",s.getWorldData().worldGenOptions().seed());t.putLong("TestRandomSeed",20261022L);t.putString("JvmArguments",java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().toString());var os=(com.sun.management.OperatingSystemMXBean)java.lang.management.ManagementFactory.getOperatingSystemMXBean();t.putLong("MachinePhysicalMemoryBytes",os.getTotalMemorySize());var sites=new ListTag();for(var b: villages)sites.add(NaturalVillageLifeData.get(s).village(b.buildingKey()));t.put("Villages",sites);t.put("Samples",samples);t.put("Links",links(l));t.putInt("Regions",villages.size());t.putInt("OriginalPeople",identities.size());t.putInt("NearRegions",villages.size()/2);t.putInt("RemoteRegions",villages.size()/2);t.putLong("ActualObservation",48000);t.putLong("Elapsed",now-start);t.putLong("PeakHeapBytes",peakHeap);t.putString("ServerTick",LodMetrics.snapshot(s,LodMetrics.Kind.SERVER_TICK).toString());t.putString("RegionEconomy",LodMetrics.snapshot(s,LodMetrics.Kind.REGION_ECONOMY).toString());t.putDouble("UnpacedTicksPerSecond",(now-start)/((System.nanoTime()-wall)/1e9));t.putString("OS",System.getProperty("os.name")+" "+System.getProperty("os.version")+" "+System.getProperty("os.arch"));t.putInt("Processors",Runtime.getRuntime().availableProcessors());t.putLong("MaxHeapBytes",Runtime.getRuntime().maxMemory());t.putInt("PhysicalCarrots",physical);t.putInt("NearNetProduction",nearDelta);t.putLong("RemoteGross",output);t.putLong("RemoteSeeds",input);t.putLong("RemoteMeals",remoteMeals);t.putInt("NearMeals",meals.size());t.putLong("MaxRouteAge",maxRouteAge);t.putLong("MaxNeedWait",maxNeedWait);t.putInt("MaxOpenRoutes",maxRoutes);write(l,"p8-scale-result.nbt",t);Sagadyssey.LOGGER.info("P8_SCALE_ACCEPT regions={} originals={} near={} remote={} tick={} region={} peakHeap={} maxRouteAge={} maxNeedWait={} physical={} nearNet={} remoteGross={} seeds={} meals={}",villages.size(),identities.size(),villages.size()/2,villages.size()/2,t.getString("ServerTick"),t.getString("RegionEconomy"),peakHeap,maxRouteAge,maxNeedWait,physical,nearDelta,output,input,meals.size()+remoteMeals);active=null;h.succeed();}
        }
    }

}
