package com.jgeted.sagadyssey.core.structure;

import net.minecraft.world.level.gamerules.GameRules;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.politics.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** One damaged existing charter, real inputs/attendance/food; no injected business completion. */
@GameTestHolder("sagadyssey_p4") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class SettlementCycleP4GameTests {
    private static final Identifier EMPTY=Identifier.parse("sagadyssey_p4:empty");
    private static final Set<UUID> paused=new HashSet<>();
    private static CompoundTag restored;
    private static Case active;
    @net.neoforged.bus.api.SubscribeEvent public static void update(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){
        if(active==null||!phase().startsWith("p4-"))return;var c=active;
        try{c.tick();}catch(RuntimeException failure){active=null;c.h.runAtTickTime(c.h.getTick()+1,()->{throw failure;});}
    }
    private static String phase(){return System.getProperty("sagadyssey.society.phase","");}
    private static void check(boolean v,String why){if(!v)throw new IllegalStateException(why);}
    @net.neoforged.bus.api.SubscribeEvent public static void spawn(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e){
        if(e.getLevel() instanceof ServerLevel l&&l.getServer() instanceof GameTestServer&&phase().startsWith("p4-")&&!phase().endsWith("resume")&&e.getEntity() instanceof NpcBase n&&paused.add(n.getUUID()))n.setNoAi(true);
    }
    @net.neoforged.bus.api.SubscribeEvent public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!(e.getServer() instanceof GameTestServer)||!phase().startsWith("p4-"))return;
        try{var s=e.getServer();var l=s.overworld();paused.clear();restored=null;active=null;
            l.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, 3,s);
            l.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, true,s);
            l.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false,s);
            l.getStructureManager().getOrCreate(EMPTY).load(l.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
            if(phase().endsWith("resume")){
                restored=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("p4-evidence.nbt"),NbtAccounter.unlimitedHeap());
                var b=NaturalVillageLifeData.decode(restored.getCompoundOrEmpty("Village"));
                var actual=links(l);NbtIo.writeCompressed(actual,s.getWorldPath(LevelResource.ROOT).resolve("p4-restored-links.nbt"));
                Sagadyssey.LOGGER.info("P4_RESTORE_DIFFERENCES fields={}",actual.keySet().stream().filter(key->!actual.get(key).equals(restored.getCompoundOrEmpty("Links").get(key))).toList());
                check(comparable(actual).equals(comparable(restored.getCompoundOrEmpty("Links"))),"whole linked ledgers must match before first server tick");
                var saved=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
                s.getWorldData().overworldData().setGameTime(saved.getLongOr("Time", 0L)+1);s.getWorldData().overworldData().setDayTime(saved.getLongOr("DayTime", 0L)+1);
                NaturalVillageR1GameTests.visiting(l,b,true);
                Sagadyssey.LOGGER.info("P4_RESTORE beforeTicks=true linkedWorld=true stage={}",restored.getStringOr("Stage", ""));
            }
        }catch(Exception x){throw new IllegalStateException("P4 coherent original world required",x);}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){if(!phase().startsWith("p4-"))return List.of();
        return List.of(new TestFunction("p4",phase().replace('-','_'),EMPTY.toString(),phase().contains("decline")?240000:80000,0,true,SettlementCycleP4GameTests::run));}
    private static int count(Container c,Item item){int total=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))total+=c.getItem(i).getCount();return total;}
    private static CompoundTag links(ServerLevel l){var s=l.getServer();var t=new CompoundTag();
        t.put("Polity",NaturalPolityData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Life",NpcLifeData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Households",HouseholdData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Rights",RightsStateData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Production",ProductionBatchData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Supply",SupplyFlowData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Transfers",TransferLedgerData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Buildings",SettlementRegistryData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Fieldwork",com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Characters",com.jgeted.sagadyssey.social.character.CharacterRegistryData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Office",PoliticalStateData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Needs",NeedRequestData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("ProductionOrders",ProductionOrderData.get(s).save(new CompoundTag(),s.registryAccess()));
        t.put("Logistics",com.jgeted.sagadyssey.social.logistics.LogisticsData.get(s).save(new CompoundTag(),s.registryAccess()));return t;}
    /** Revisions is a UUID-keyed Map, not an ordered series; keep every value and reject duplicates. */
    private static CompoundTag comparable(CompoundTag source){
        var copy=source.copy();
        for(var entry:MinecraftApi.listOfType(copy.getCompoundOrEmpty("Transfers"), "Receipts",Tag.TAG_COMPOUND)){
            var receipt=(CompoundTag)entry;var sorted=new TreeMap<UUID,CompoundTag>();
            for(var item:MinecraftApi.listOfType(receipt, "Revisions",Tag.TAG_COMPOUND)){
                var revision=(CompoundTag)item;
                check(revision.read("Container", UUIDUtil.CODEC).isPresent(),"invalid revision map key");
                check(sorted.putIfAbsent(revision.read("Container", UUIDUtil.CODEC).orElseThrow(),revision)==null,"duplicate revision map key");
            }
            var list=new ListTag();sorted.values().forEach(list::add);receipt.put("Revisions",list);
        }
        return copy;
    }
    private static final class Case {
        final GameTestHelper h;final ServerLevel l;final NaturalVillageBuilding b;
        final Set<String> meals=new HashSet<>();final CompoundTag trace=new CompoundTag();
        int stage=0;long blocked=0;int savedWork;RightGrant grant;boolean setup=false,slept=false;
        Case(GameTestHelper helper){h=helper;l=h.getLevel();
            b=restored==null?NaturalVillageR1GameTests.fixture(h,0,NaturalVillageLayout.WOOD_V2):NaturalVillageLifeData.decode(restored.getCompoundOrEmpty("Village"));
            if(restored!=null){setup=true;trace.merge(restored.getCompoundOrEmpty("Trace"));for(var t:MinecraftApi.listOfType(restored, "Meals",Tag.TAG_STRING))meals.add(t.asString().orElseThrow());
                stage=restored.getStringOr("Stage", "").equals("DECLINING")?3:restored.getStringOr("Stage", "").equals("DONE")?5:1;}
        }
        NpcBase npc(int i){return NaturalVillageR1GameTests.actor(l,b,i);}
        Container store(int i){return NaturalPolityData.inventory(l.getServer(),b,i);}
        UUID id(int i){return NaturalVillageEconomyData.storeId(b,i);}
        NaturalPolityData data(){return NaturalPolityData.get(l.getServer());}
        CompoundTag row(){return data().site(b.settlementId());}
        CompoundTag project(){return row().getCompoundOrEmpty("Project");}
        void gift(SubjectRef actor,UUID from,Container source,UUID to,Container target,Item item,int n){
            var s=l.getServer();var rights=RightsStateData.get(s);long now=l.getGameTime();
            var req=new TransferRequest(UUID.randomUUID(),actor,TransferIntent.GIFT,List.of(new TransferLeg(from,to,ItemFingerprint.of(new ItemStack(item)),n)),List.of(new ExpectedRevision(from,rights.context(from).revision()),new ExpectedRevision(to,rights.context(to).revision())),"P4 declared lawful initial allocation / shortage recovery",now);
            var result=new TransferService(rights,TransferLedgerData.get(s)).submit(req,Map.of(from,new TransferEndpoint(from,source),to,new TransferEndpoint(to,target)),now);
            check(result.successful()&&result.lawful(),"actual lawful allocation: "+result.reason());
        }
        int food(){int sum=0;for(int i=0;i<5;i++)sum+=count(store(i),Items.CARROT);for(int i=0;i<3;i++)sum+=count(npc(i).getPersonalInventory(),Items.CARROT)+count(npc(i).getTransportInventory(),Items.CARROT);return sum;}
        void init(){var life=NpcLifeData.get(l.getServer());check(npc(2)!=null&&row()!=null,"original natural initialization complete");
            // Entire initial budget is explicit; after this method all item moves use TransferService.
            store(1).setItem(0,new ItemStack(Items.CARROT,64));store(1).setItem(1,new ItemStack(Items.CARROT,64));store(1).setItem(2,new ItemStack(Items.OAK_LOG));
            gift(SubjectRef.character(npc(0).getCharacterId()),ContainerIds.npcPersonal(npc(0).getCharacterId()),npc(0).getPersonalInventory(),id(1),store(1),Items.CARROT,16);
            // Deliberate initial damage: two residents remain housed, no usable spare bed exists.
            var beds=new ArrayList<BlockPos>();if(!phase().equals("p4-capacity"))beds.addAll(NaturalVillageLayout.spareBeds());beds.add(NaturalVillageLayout.BEDS.get(2));
            for(var p:beds){var at=NaturalVillageLayout.at(b,p);var head=NpcResidenceFixtures.normalizeBedHead(l,at);var state=l.getBlockState(head);var foot=head.relative(state.getValue(BedBlock.FACING).getOpposite());l.setBlockAndUpdate(head,Blocks.AIR.defaultBlockState());l.setBlockAndUpdate(foot,Blocks.AIR.defaultBlockState());}
            check(phase().equals("p4-capacity")?PublicHousingConditions.beneficiary(l.getServer(),b)==null:npc(2).getCharacterId().equals(PublicHousingConditions.beneficiary(l.getServer(),b)),"real bed deficit, not population label");
            if(phase().equals("p4-output"))for(int i=0;i<24;i++)store(0).setItem(i,new ItemStack(Items.COBBLESTONE,64));
            if(phase().equals("p4-materials"))gift(SubjectRef.character(npc(1).getCharacterId()),id(3),store(3),id(2),store(2),Items.WHITE_WOOL,3);
            if(phase().equals("p4-space"))l.setBlockAndUpdate(PublicHousingConditions.head(b),Blocks.STONE.defaultBlockState());
            for(int i=0;i<3;i++)npc(i).setNoAi(false);
            check(food()==144,"initial carrot budget exactly 144");check(count(store(3),Items.WHITE_WOOL)==(phase().equals("p4-materials")?0:3),"original generated wool budget");
            trace.putLong("InitialAt",l.getGameTime());trace.putInt("InitialCarrots",144);trace.putInt("InitialValidBeds",2);setup=true;
        }
        void snapshot(String state){try{var s=l.getServer();var out=new CompoundTag();out.putString("Stage",state);out.put("Village",NaturalVillageLifeData.get(s).village(b.buildingKey()));out.put("Links",links(l));out.put("Trace",trace.copy());out.putLong("Time",l.getGameTime());
            var ms=new ListTag();meals.stream().sorted().forEach(x->ms.add(StringTag.valueOf(x)));out.put("Meals",ms);
            var stores=new ListTag();for(int i=0;i<5;i++){var x=new CompoundTag();x.putInt("Index",i);x.store("Id", UUIDUtil.CODEC,id(i));x.put("Items",NaturalVillageEconomyData.items(store(i),s.registryAccess()));stores.add(x);}out.put("Stores",stores);
            var bodies=new ListTag();for(int i=0;i<3;i++){var x=MinecraftApi.saveEntity(npc(i));x.put("PersonalProof",NaturalVillageEconomyData.items(npc(i).getPersonalInventory(),s.registryAccess()));x.put("CargoProof",NaturalVillageEconomyData.items(npc(i).getTransportInventory(),s.registryAccess()));bodies.add(x);}out.put("Bodies",bodies);
            var world=s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();NbtIo.writeCompressed(out,world.resolve("p4-evidence.nbt"));s.saveEverything(true,true,true);
            var dst=world.resolveSibling("world-p4-snapshot");check(Files.notExists(dst),"never overwrite checkpoint");try(var walk=Files.walk(world)){for(var p:walk.filter(Files::isRegularFile).toList()){if(p.getFileName().toString().equals("session.lock"))continue;var to=dst.resolve(world.relativize(p));Files.createDirectories(to.getParent());Files.copy(p,to);}}
            Sagadyssey.LOGGER.info("P4_CHECKPOINT state={} physicalCarrots={} individualMeals={} trace={}",state,food(),meals.size(),trace);
        }catch(Exception e){throw new IllegalStateException("P4 associated world snapshot",e);}}
        void finalAudit(){var s=l.getServer();var p=project();
            check(p.getStringOr("Stage", "").equals("DONE")&&row().getIntOr("ExtraBeds", 0)==1&&p.getIntOr("LaborTicks", 0)==600,"one completed facility");
            check(p.read("Beneficiary", UUIDUtil.CODEC).orElseThrow().equals(npc(2).getCharacterId())&&p.read("FacilityOwner", UUIDUtil.CODEC).orElseThrow().equals(b.settlementId()),"same needy resident and public institution");
            check(PublicHousingConditions.validBed(l,PublicHousingConditions.head(b))&&NpcLifeData.get(s).record(npc(2).getCharacterId()).bedPosition().equals(PublicHousingConditions.head(b)),"real assigned facility");
            check(count(store(3),Items.OAK_PLANKS)==1&&count(store(3),Items.OAK_LOG)==0&&count(store(3),Items.WHITE_WOOL)==0&&count(store(3),Items.WHITE_BED)==0,"public recipe: log1 wool3 -> bed1 placed + plank1");
            if(phase().equals("p4-output"))check(count(store(0),Items.COBBLESTONE)==1536&&count(store(3),Items.COBBLESTONE)==0,"all original obstruction goods returned, not deleted");
            var batches=ProductionBatchData.get(s).batches().stream().filter(x->x.containerId().equals(id(3))).toList();
            check(batches.size()==2&&batches.stream().allMatch(x->x.status()==ProductionBatchStatus.COMPLETED),"two original batches settled, no unresolved construction");
            for(var batch:batches)check(RightsStateData.get(s).reservationsForToken(batch.token(),l.getGameTime()).isEmpty(),"no orphan production reservation");
            check(food()+meals.size()+4==144,"exact food = physical + individual receipted meals + construction4; food="+food()+" meals="+meals.size());
            check(SupplyFlowData.get(s).routes().stream().noneMatch(r->!r.closed()&&(id(3).equals(r.source())||id(3).equals(r.destination()))),"no unexplained unfinished construction transport");
            for(var need:NeedRequestData.get(s).requests()){check(need.reserved()==0&&need.inTransit()==0,"end-of-window cargo and reservations fully reconciled");if(!need.status().terminal()&&need.status()!=NeedRequestStatus.DELIVERED)check(l.getGameTime()-need.createdAt()<=24000,"no unbounded unexplained supply backlog");}
            trace.putInt("UnfinishedPublicBatches",0);trace.putInt("ActivePublicReservations",0);trace.putInt("ConstructionTransportObligations",0);
            check(NaturalPolityData.population(s,b).size()==3,"population unchanged");
            var before=p.copy();data().tick(s);data().tick(s);for(int i=0;i<3;i++)check(data().work(npc(2)).equals("DONE"),"completed replay is no-op");check(project().equals(before),"replay never duplicates public result");
        }
        void tick(){if(h.getTick()<150)return;if(!setup)init();
            var s=l.getServer();NaturalVillageR1GameTests.observe(l,b,meals);var p=project();String ps=p.getStringOr("Stage", "");
            if(h.getTick()%12000==0)Sagadyssey.LOGGER.info("P4_PROGRESS phase={} tick={} stage={} condition={} project={} hunger={} food={} meals={}",phase(),h.getTick(),stage,row().getStringOr("Condition", ""),p,java.util.stream.IntStream.range(0,3).map(i->NpcLifeData.get(s).needs(npc(i).getCharacterId()).hunger()).boxed().toList(),food(),meals.size());
            if(phase().equals("p4-capacity")&&h.getTick()>=50000){check(ps.isEmpty()&&count(store(1),Items.OAK_LOG)==1&&count(store(3),Items.WHITE_WOOL)==3,"available existing capacity never causes needless construction");snapshot("NO_PROJECT");Sagadyssey.LOGGER.info("P4_ACCEPT availableCapacity=true noFreeConstruction=true");active=null;h.succeed();return;}
            if(!ps.isEmpty()&&!trace.contains("ProjectAt")){trace.putLong("ProjectAt",l.getGameTime());trace.store("Project", UUIDUtil.CODEC,p.read("Id", UUIDUtil.CODEC).orElseThrow());check(p.read("Beneficiary", UUIDUtil.CODEC).orElseThrow().equals(npc(2).getCharacterId()),"approval records actual deficit");}
            if(phase().equals("p4-build-save")&&ps.equals("PLANKS")&&p.getIntOr("Work", 0)>=40){snapshot("BUILDING");active=null;h.succeed();return;}
            if(stage==0&&!ps.isEmpty())stage=1;
            if(phase().equals("p4-materials")&&stage==1&&ps.equals("BED")){blocked=l.getGameTime();stage=2;}
            if(phase().equals("p4-space")&&stage==1&&ps.equals("PLACE")){blocked=l.getGameTime();stage=2;}
            if(phase().equals("p4-output")&&stage==1&&ps.equals("PLANKS")&&p.getIntOr("Work", 0)>=20){gift(SubjectRef.character(npc(0).getCharacterId()),id(0),store(0),id(3),store(3),Items.COBBLESTONE,1536);blocked=l.getGameTime();stage=2;}
            if((phase().equals("p4-rights")||phase().equals("p4-conflict"))&&stage==1&&ps.equals("PLANKS")&&p.getIntOr("Work", 0)>=20){
                savedWork=p.getIntOr("Work", 0);blocked=l.getGameTime();stage=2;
                if(phase().equals("p4-conflict"))npc(2).setCommand(NpcCommand.STAY);
                else{var rights=RightsStateData.get(s);grant=rights.grants().stream().filter(g->g.id().equals(ContainerIds.named("charter-public-office:"+b.settlementId()))).findFirst().orElseThrow();rights.revoke(grant.id());}
            }
            if(stage==2&&!phase().contains("decline")){
                check(!project().getStringOr("Stage", "").equals("DONE"),"blocked case never builds illegally");
                if(phase().equals("p4-rights")||phase().equals("p4-conflict"))check(project().getIntOr("Work", 0)==savedWork,"withdrawn authority/work conflict does not accumulate work");
                if(l.getGameTime()-blocked>=2400){
                    if(phase().equals("p4-output"))gift(SubjectRef.character(npc(1).getCharacterId()),id(3),store(3),id(0),store(0),Items.COBBLESTONE,1536);
                    if(phase().equals("p4-materials"))gift(SubjectRef.character(npc(2).getCharacterId()),id(2),store(2),id(3),store(3),Items.WHITE_WOOL,3);
                    if(phase().equals("p4-space"))l.setBlockAndUpdate(PublicHousingConditions.head(b),Blocks.AIR.defaultBlockState());
                    if(phase().equals("p4-rights"))RightsStateData.get(s).grant(grant);
                    if(phase().equals("p4-conflict"))npc(2).setCommand(NpcCommand.WORK);
                    trace.putLong("BlockTicks",l.getGameTime()-blocked);stage=4;
                }
            }
            if(phase().contains("decline")){
                if(stage==1&&ps.equals("PLANKS")&&p.getIntOr("Work", 0)>=20){
                    savedWork=p.getIntOr("Work", 0);npc(2).setCommand(NpcCommand.STAY);
                    int n=count(store(1),Items.CARROT);gift(SubjectRef.household(NaturalVillageLifeData.home(b)),id(1),store(1),id(3),store(3),Items.CARROT,n);
                    trace.putInt("WithheldCarrots",n);trace.putLong("WithheldAt",l.getGameTime());stage=2;
                }
                if(stage==2&&row().getStringOr("Condition", "").equals("DECLINING")){
                    check(l.getGameTime()-row().getLongOr("ShortSince", 0L)>=48000,"full genuine severe hunger window");
                    check(count(store(1),Items.CARROT)==0&&count(store(3),Items.CARROT)>=68,"shortage is household access, not destroyed or missing goods");
                    npc(2).setCommand(NpcCommand.WORK);blocked=l.getGameTime();trace.putLong("DeclinedAt",blocked);trace.putInt("WorkAtDecline",p.getIntOr("Work", 0));stage=3;
                }
                if(stage==3){
                    check(row().getStringOr("Condition", "").equals("DECLINING")&&project().getIntOr("Work", 0)==trace.getIntOr("WorkAtDecline", 0),"food shortage causes actual production suspension despite labor and materials");
                    if(restored!=null||l.getGameTime()-blocked>=2400){
                        trace.putLong("SuspensionObservedUntil",l.getGameTime());
                        if(phase().equals("p4-decline-save")){snapshot("DECLINING");active=null;h.succeed();return;}
                        gift(SubjectRef.character(npc(1).getCharacterId()),id(3),store(3),id(1),store(1),Items.CARROT,64);
                        trace.putLong("FoodRestoredAt",l.getGameTime());trace.putInt("RescueCarrots",64);stage=4;
                    }
                }
                if(stage==4&&row().getStringOr("Condition", "").equals("STABLE")&&!trace.contains("RecoveredAt")){
                    check(l.getGameTime()-row().getLongOr("RecoveredSince", 0L)>=24000,"full real recovery observation");trace.putLong("RecoveredAt",l.getGameTime());
                }
            }
            // A second call within the same world tick must never count another labor contribution.
            if((ps.equals("PLANKS")||ps.equals("BED"))&&p.contains("LastWorkAt")&&p.getLongOr("LastWorkAt", 0L)==l.getGameTime()){
                var before=project().copy();data().work(npc(2));check(project().equals(before),"same-tick execution replay changes nothing");
            }
            if(stage==4&&trace.contains("BlockTicks")&&!project().getStringOr("Stage", "").equals("DONE"))check(l.getGameTime()-blocked<=4800,"legal unblock must resume within frozen 2400 work window");
            if(stage==4&&trace.contains("FoodRestoredAt")&&!trace.contains("RecoveredAt"))check(l.getGameTime()-trace.getLongOr("FoodRestoredAt", 0L)<=28800,"real food restoration must recover within frozen full observation plus meal bound");
            if(trace.contains("RecoveredAt")&&!project().getStringOr("Stage", "").equals("DONE"))check(l.getGameTime()-trace.getLongOr("RecoveredAt", 0L)<=2400,"stable eligible worker must resume real construction");
            if(project().getStringOr("Stage", "").equals("DONE")){
                if(phase().contains("decline"))check(trace.contains("RecoveredAt"),"real recovery precedes completion");
                if(!trace.contains("CompletedAt")){trace.putLong("CompletedAt",l.getGameTime());trace.putInt("FatigueBeforeSleep",NpcLifeData.get(s).record(npc(2).getCharacterId()).fatigue());}
                if(!slept)check(l.getGameTime()-trace.getLongOr("CompletedAt", 0L)<=24000,"assigned facility must provide real sleep within frozen day window");
                if(npc(2).isSleeping()&&npc(2).getSleepingPos().orElse(BlockPos.ZERO).equals(PublicHousingConditions.head(b))){
                    if(!trace.contains("FirstSleepAt")){trace.putLong("FirstSleepAt",l.getGameTime());trace.putInt("SleepFatigue",NpcLifeData.get(s).record(npc(2).getCharacterId()).fatigue());}
                    if(NpcLifeData.get(s).record(npc(2).getCharacterId()).fatigue()<trace.getIntOr("SleepFatigue", 0))slept=true;
                }
                if(slept||(restored!=null&&restored.getStringOr("Stage", "").equals("DONE")&&h.getTick()>=450)){
                    finalAudit();trace.putBoolean("RealSleepAndFatigueImprovement",true);snapshot("DONE");
                    Sagadyssey.LOGGER.info("P4_ACCEPT phase={} realBed=true labor=600 linkedIdentity=true initialCarrots=144 physicalCarrots={} meals={} constructionRations=4 trace={}",phase(),food(),meals.size(),trace);active=null;h.succeed();
                }
            }
        }
    }
    private static void run(GameTestHelper h){try{h.getLevel().getServer().getCommands().getDispatcher().execute("tick sprint "+(phase().contains("decline")?240000:80000),h.getLevel().getServer().createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSuppressedOutput());}catch(Exception e){throw new IllegalStateException(e);}active=new Case(h);}
}
