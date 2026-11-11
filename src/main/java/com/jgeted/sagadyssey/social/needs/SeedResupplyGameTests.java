package com.jgeted.sagadyssey.social.needs;

import net.minecraft.world.level.gamerules.GameRules;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.*;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.settlement.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Opt-in finite seed resupply chain, with isolated coherent world checkpoints. */
@GameTestHolder("sagadyssey_seeds")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid="sagadyssey")
public final class SeedResupplyGameTests {
    private static final BlockPos BASE=new BlockPos(384,80,384), SOURCE=BASE.offset(10,1,9), PANTRY=BASE.offset(22,1,9);
    private static final BlockPos FIRST=BASE.offset(3,1,6), SECOND=BASE.offset(5,1,6);
    private static final ItemFingerprint SEED=new ItemFingerprint("minecraft:wheat_seeds",0);
    private static final Identifier EMPTY=Identifier.parse("sagadyssey_seeds:empty");
    private static final String MARKER="seed-resupply.nbt";
    private static CompoundTag restored;
    private static boolean originalInventoryVerified;
    private static String phase(){return System.getProperty("sagadyssey.seeds.phase","disabled");}
    private static void require(boolean ok,String text){if(!ok)throw new IllegalStateException(text);}
    private record Fixture(ServerLevel level,UUID settlement,UUID household,UUID miner,UUID person,UUID courier,UUID sourceId,UUID sourceGrant,UUID pickupGrant) {
        NpcBase worker(){return (NpcBase)level.getEntity(miner);}
        NpcBase carrier(){return (NpcBase)level.getEntity(courier);}
        Container source(){return (Container)level.getBlockEntity(SOURCE);}
        Container pantry(){return (Container)level.getBlockEntity(PANTRY);}
        UUID pantryId(){return ContainerIds.block("household_storage",level.dimension().identifier().toString(),PANTRY);}
        List<FieldworkActionReceipt> plants(){return FieldworkData.get(level.getServer()).receiptsFor(miner).stream().filter(r->r.action().operation()==FieldworkAction.Operation.PLANT).toList();}
    }
    private static void force(ServerLevel l){for(int x=23;x<=26;x++)for(int z=23;z<=25;z++){l.setChunkForced(x,z,true);l.getChunk(x,z);}}
    @SubscribeEvent public static void install(ServerStartedEvent e) {
        if(!(e.getServer() instanceof GameTestServer)||phase().equals("disabled"))return;
        var s=e.getServer();var l=s.overworld();restored=null;originalInventoryVerified=false;
        try {
            l.getStructureManager().getOrCreate(EMPTY).load(l.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
            if(phase().equals("pre-resume")||phase().equals("post-resume")) {
                restored=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve(MARKER),NbtAccounter.unlimitedHeap());
                var world=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap());
                s.getWorldData().overworldData().setGameTime(Math.max(restored.getLongOr("Time", 0L),world.getCompoundOrEmpty("Data").getLongOr("Time", 0L))+1);
                var current=ledgers(l);
                for(String key:current.keySet()){
                    Tag observed=current.get(key),expected=restored.getCompoundOrEmpty("Ledgers").get(key);
                    if(key.equals("Transfers")){observed=transferMapOrder((CompoundTag)observed);expected=transferMapOrder((CompoundTag)expected);}
                    if(!observed.equals(expected)){
                        Files.writeString(s.getWorldPath(LevelResource.ROOT).resolve("seed-mismatch-current-"+key+".snbt"),current.get(key).toString());
                        Files.writeString(s.getWorldPath(LevelResource.ROOT).resolve("seed-mismatch-expected-"+key+".snbt"),restored.getCompoundOrEmpty("Ledgers").get(key).toString());
                    }
                    require(observed.equals(expected),"whole-world ledger mismatch: "+key);
                }
                var proof=restored.copy();proof.putBoolean("LinkedLedgersVerifiedBeforeTick",true);
                NbtIo.writeCompressed(proof,s.getWorldPath(LevelResource.ROOT).resolve("seed-restored-proof.nbt"));
            }
            force(l);l.setDayTime(1000);l.getGameRules().set(GameRules.ADVANCE_TIME, false,s);l.getGameRules().set(GameRules.SPAWN_MOBS, false,s);l.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0,s);
            if(phase().equals("regression"))l.getStructureManager().getOrCreate(Identifier.parse("sagadyssey_seeds:fieldwork_empty")).load(l.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[96,10,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
        }catch(Exception x){throw new IllegalStateException(x);}
    }
    // TransferReceipt.finalRevisions is a Map; only this list represents an unordered map.
    // Keep receipt chronology, audit order, route stages and every quantity strictly compared.
    private static CompoundTag transferMapOrder(CompoundTag source){
        var t=source.copy();for(var raw:MinecraftApi.listOfType(t, "Receipts",Tag.TAG_COMPOUND)){
            var receipt=(CompoundTag)raw;var entries=new ArrayList<CompoundTag>();
            for(var value:MinecraftApi.listOfType(receipt, "Revisions",Tag.TAG_COMPOUND))entries.add((CompoundTag)value);
            entries.sort(Comparator.comparing(v->v.read("Container", UUIDUtil.CODEC).orElseThrow().toString()));var list=new ListTag();entries.forEach(list::add);receipt.put("Revisions",list);
        }return t;
    }
    @SubscribeEvent public static void originalBodyLoaded(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e){
        if(restored==null||!(e.getLevel() instanceof ServerLevel l)||!(e.getEntity() instanceof NpcBase body)||!body.getUUID().equals(restored.read("Farmer", UUIDUtil.CODEC).orElseThrow()))return;
        var actual=MinecraftApi.saveEntity(body);var expected=restored.getCompoundOrEmpty("FarmerEntity");
        for(String key:List.of("Items","HandItems","PersonalInventoryRevision","HomeSettlementId"))require(Objects.equals(actual.get(key),expected.get(key)),"original entity checkpoint mismatch: "+key);
        require(body.getCharacterId().equals(restored.read("Person", UUIDUtil.CODEC).orElseThrow()),"original person before first entity tick");originalInventoryVerified=true;
        var proof=restored.copy();proof.putBoolean("LinkedLedgersVerifiedBeforeTick",true);proof.putBoolean("OriginalEntityInventoryVerifiedBeforeTick",true);
        try{NbtIo.writeCompressed(proof,l.getServer().getWorldPath(LevelResource.ROOT).resolve("seed-restored-proof.nbt"));}catch(Exception x){throw new IllegalStateException(x);}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){
        if(phase().equals("disabled"))return List.of();
        if(phase().equals("regression"))return regression();
        if(phase().startsWith("harvest-"))return List.of(new TestFunction("seed_priority","seed_"+phase(),EMPTY.toString(),Rotation.NONE,2400,0,true,SeedResupplyGameTests::harvestPriority));
        return List.of(new TestFunction("seeds","seeds_"+phase(),EMPTY.toString(),Rotation.NONE,2400,0,true,SeedResupplyGameTests::run));
    }
    private static void harvestPriority(GameTestHelper h){
        var f=fixture(h);long start=f.level().getGameTime();
        if(phase().equals("harvest-priority")){
            f.worker().getPersonalInventory().clearContent();
            f.level().setBlockAndUpdate(SECOND,((CropBlock)Blocks.WHEAT).getStateForAge(7));
        }else{
            f.source().clearContent();
            h.runAtTickTime(220,()->{
                require(f.plants().size()==1,"farmer used last seed before waiting");
                var wheat=(CropBlock)Blocks.WHEAT;
                for(int i=0;i<512&&!wheat.isMaxAge(f.level().getBlockState(FIRST));i++)f.level().getBlockState(FIRST).randomTick(f.level(),FIRST,f.level().random);
                require(wheat.isMaxAge(f.level().getBlockState(FIRST)),"controlled vanilla random ticks grew the existing real crop");
            });
        }
        h.succeedWhen(()->{
            boolean harvested=FieldworkData.get(f.level().getServer()).receiptsFor(f.miner()).stream().anyMatch(r->r.action().operation()==FieldworkAction.Operation.HARVEST);
            h.assertTrue(harvested,"ordinary harvest must proceed while external seeds are unavailable");
            require(pickups(f)==0&&f.carrier().isNoAi(),"existing harvest, no external pickup or test supply");
            record(f,start,"harvest-accepted");Sagadyssey.LOGGER.info("SEEDS_REGRESSION_ACCEPT phase={} elapsed={} ordinaryHarvest=true externalPickups=0",phase(),f.level().getGameTime()-start);
        });
    }
    private static Collection<TestFunction> regression(){
        var selected=new LinkedHashMap<Class<?>,List<String>>();
        selected.put(FieldworkGameTests.class,List.of("farmerHarvestReplantsAndDelivers","workerMinesAndRestsInsteadOfStandingForever","workerDiscoversAndWalksToEightyBlockTarget","missingToolRecoversAfterSupply","fullDestinationKeepsCargoAndRecovers","twoWorkersCannotDuplicateOneBlock","loggingIsOneBlockPerReceipt","scaffoldBuildAndRecoveryConserveOriginalMaterial","canceledWorkRecoversRecordedScaffoldOutsideNewZone","savedRunningTravelAndEntityReloadWithoutDuplicateProduction","fullPersonalBagDoesNotDestroyBlockOrWearTool","borrowedToolRequiresUseGrantAndRetainsOwner","changedProfessionAndZoneStopOldTravelBeforeMutation","unreachableBlockIsRememberedWhileAlternativeGetsDone","farmerTillsAndPlantsAfterSeedSupply","emptyAreaRestsBoundedlyAndExplicitStayCancels"));
        selected.put(SupplyGameTests.class,List.of("courierTakesCarriesAndDeliversRealStock","twoCouriersCannotTakeSameShipmentTwice","knownForeignStorageDoesNotGrantRights","householdAggregatesAndRestocksActualSharedPantry"));
        var tests=new ArrayList<TestFunction>();
        selected.forEach((type,names)->names.forEach(name->{try{
            var method=type.getMethod(name,GameTestHelper.class);var annotation=method.getAnnotation(GameTest.class);
            tests.add(new TestFunction("seed_regression_"+name,"seed_regression_"+name,"sagadyssey_seeds:fieldwork_empty",Rotation.NONE,annotation.timeoutTicks(),0,true,h->{
                FieldworkGameTests.templates(h.getLevel());if(type==SupplyGameTests.class)SupplyGameTests.day(h.getLevel());
                try{method.invoke(null,h);}catch(java.lang.reflect.InvocationTargetException x){if(x.getCause() instanceof RuntimeException r)throw r;throw new IllegalStateException(x.getCause());}catch(Exception x){throw new IllegalStateException(x);}
            }));
        }catch(Exception x){throw new IllegalStateException(x);}}));
        Sagadyssey.LOGGER.info("SEEDS_REGRESSION_SELECTED count={}",tests.size());return tests;
    }
    private static NpcBase npc(ServerLevel l,BlockPos p,NpcProfession job,UUID settlement){
        var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),l);n.snapTo(p.getX()+.5,p.getY(),p.getZ()+.5,0,0);n.setProfession(job);n.setHomeSettlementId(settlement);n.setPersistenceRequired();
        n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();n.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);n.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
        n.setCommand(NpcCommand.WORK);l.addFreshEntity(n);NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);return n;
    }
    private static Fixture fixture(GameTestHelper h){
        var l=h.getLevel();var s=l.getServer();force(l);
        for(var p:BlockPos.betweenClosed(BASE,BASE.offset(25,5,12)))l.setBlockAndUpdate(p,p.getY()==BASE.getY()?Blocks.BEDROCK.defaultBlockState():Blocks.AIR.defaultBlockState());
        l.setBlockAndUpdate(FIRST.below(),Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7));l.setBlockAndUpdate(SECOND.below(),Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7));l.setBlockAndUpdate(BASE.offset(4,0,7),Blocks.WATER.defaultBlockState());
        var registry=SettlementRegistryData.get(s);var village=registry.create(s,"seed village",SettlementType.VILLAGE,l.dimension().identifier().toString(),BASE,48,"sagadyssey:wilderness",null,l.getGameTime());
        var miner=npc(l,BASE.offset(2,1,6),NpcProfession.FARMER,village.id());var courier=npc(l,BASE.offset(2,1,10),NpcProfession.COURIER,village.id());courier.setNoAi(true);
        miner.setFarmMode(FarmMode.CROP);miner.setWorkZone(FIRST,SECOND);miner.getPersonalInventory().setItem(0,new ItemStack(Items.WHEAT_SEEDS));
        var people=List.of(miner,courier);for(int i=0;i<people.size();i++){
            var bed=BASE.offset(5+i*3,1,2);l.setBlockAndUpdate(bed,Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT));l.setBlockAndUpdate(bed.south(),Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.HEAD));
            NpcLifeData.get(s).registerInstitutional(people.get(i),l.dimension().identifier().toString(),village.id(),BASE,"seed residence",bed,null,NpcWorkShift.DAY,l.getGameTime(),false);
        }
        var homes=HouseholdData.get(s);var home=homes.create("seed household",l.dimension().identifier().toString(),village.id(),miner.getCharacterId(),BASE,l.getGameTime());homes.addMember(home.id(),courier.getCharacterId(),HouseholdRole.KIN,l.getGameTime());
        l.setBlockAndUpdate(SOURCE,Blocks.BARREL.defaultBlockState());l.setBlockAndUpdate(PANTRY,Blocks.BARREL.defaultBlockState());require(homes.assignSharedStorage(home.id(),home.dimension(),BASE,PANTRY),"pantry assigned");homes.setProvisionPlan(new HouseholdProvisionPlan(home.id(),0,0,0));
        registry.registerScenarioBuilding(s,home.dimension(),SOURCE,"sagadyssey:seed_source","warehouse","small",village.id(),l.getGameTime());registry.registerScenarioBuilding(s,home.dimension(),PANTRY,"sagadyssey:seed_pantry","warehouse","small",village.id(),l.getGameTime());
        registry.registerScenarioBuilding(s,home.dimension(),FIRST.below(),"sagadyssey:seed_field","farm","small",village.id(),l.getGameTime());
        var family=SubjectRef.household(home.id());var institution=SubjectRef.institution(village.id());var rights=RightsStateData.get(s);
        UUID source=ContainerIds.block("seed_source",home.dimension(),SOURCE),pantry=ContainerIds.block("household_storage",home.dimension(),PANTRY),sg=UUID.randomUUID(),pg=UUID.randomUUID();
        rights.ensureContext(source,"seed-source",institution,institution,AccessPolicy.PRIVATE);rights.ensureContext(pantry,"seed-pantry",family,family,AccessPolicy.HOUSEHOLD);
        rights.grant(new RightGrant(sg,source,family,EnumSet.of(AssetRight.ACCESS,AssetRight.CONSUME,AssetRight.TRANSFER),"explicit source seed grant",l.getGameTime(),null,true));
        rights.grant(new RightGrant(pg,pantry,SubjectRef.character(miner.getCharacterId()),EnumSet.of(AssetRight.ACCESS,AssetRight.CONSUME,AssetRight.TRANSFER),"explicit seed gift authorization",l.getGameTime(),null,true));
        ((Container)l.getBlockEntity(SOURCE)).setItem(0,new ItemStack(Items.WHEAT_SEEDS));
        var flows=SupplyFlowData.get(s);flows.register(new FieldworkStorage(home.dimension(),SOURCE,source,institution,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"seed-source"));flows.reveal(family,source);
        flows.register(new FieldworkStorage(home.dimension(),PANTRY,pantry,family,AccessPolicy.HOUSEHOLD,TransferIntent.DELIVERY,"seed-pantry"));flows.policy(new SupplyFlowData.Policy("wheat-seed-buffer",family,pantry,SEED,1,"household"));
        return new Fixture(l,village.id(),home.id(),miner.getUUID(),miner.getCharacterId(),courier.getUUID(),source,sg,pg);
    }
    private static CompoundTag ledgers(ServerLevel l){
        var s=l.getServer();var t=new CompoundTag();var r=s.registryAccess();
        t.put("Needs",NeedRequestData.get(s).save(new CompoundTag(),r));t.put("Flows",SupplyFlowData.get(s).save(new CompoundTag(),r));t.put("Rights",RightsStateData.get(s).save(new CompoundTag(),r));t.put("Transfers",TransferLedgerData.get(s).save(new CompoundTag(),r));
        t.put("Characters",CharacterRegistryData.get(s).save(new CompoundTag(),r));t.put("Households",HouseholdData.get(s).save(new CompoundTag(),r));t.put("Brain",NpcBrainRuntime.data(s).save(new CompoundTag(),r));t.put("Fieldwork",FieldworkData.get(s).save(new CompoundTag(),r));return t;
    }
    private static CompoundTag describe(Fixture f,long start){
        var t=new CompoundTag();t.putString("Phase",phase());t.putLong("Time",f.level().getGameTime());t.putLong("Started",start);
        t.store("Settlement", UUIDUtil.CODEC,f.settlement());t.store("Household", UUIDUtil.CODEC,f.household());t.store("Farmer", UUIDUtil.CODEC,f.miner());t.store("Person", UUIDUtil.CODEC,f.person());t.store("Courier", UUIDUtil.CODEC,f.courier());t.store("Source", UUIDUtil.CODEC,f.sourceId());t.store("SourceGrant", UUIDUtil.CODEC,f.sourceGrant());t.store("PickupGrant", UUIDUtil.CODEC,f.pickupGrant());
        t.put("Ledgers",ledgers(f.level()));t.put("FarmerEntity",MinecraftApi.saveEntity(f.worker()));t.putInt("Plants",f.plants().size());
        t.putString("FirstCrop",f.level().getBlockState(FIRST).toString());t.putString("SecondCrop",f.level().getBlockState(SECOND).toString());
        t.putInt("SourceSeeds",SupplyFlowService.count(f.source(),SEED));t.putInt("PantrySeeds",SupplyFlowService.count(f.pantry(),SEED));t.putInt("CargoSeeds",SupplyFlowService.count(f.carrier().getTransportInventory(),SEED));t.putInt("PersonalSeeds",SupplyFlowService.count(f.worker().getPersonalInventory(),SEED));return t;
    }
    private static void record(Fixture f,long start,String label){try{NbtIo.writeCompressed(describe(f,start),f.level().getServer().getWorldPath(LevelResource.ROOT).resolve("seed-"+label+".nbt"));}catch(Exception x){throw new IllegalStateException(x);}}
    private static void snapshot(Fixture f,long start){try{
        var s=f.level().getServer();var root=s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();NbtIo.writeCompressed(describe(f,start),root.resolve(MARKER));s.saveEverything(false,true,false);
        var dest=root.resolveSibling(root.getFileName()+"-seed-snapshot");require(!Files.exists(dest),"new snapshot only");Files.createDirectories(dest);
        try(var files=Files.walk(root)){for(var p:files.toList()){if(p.getFileName().toString().equals("session.lock"))continue;var q=dest.resolve(root.relativize(p));if(Files.isDirectory(p))Files.createDirectories(q);else Files.copy(p,q);}}
        Sagadyssey.LOGGER.info("SEEDS_WHOLE_WORLD_SNAPSHOT phase={} plants={} path={}",phase(),f.plants().size(),dest);
    }catch(Exception x){throw new IllegalStateException(x);}}
    private static ItemStack replacement(Fixture f){for(int i=0;i<f.worker().getPersonalInventory().getContainerSize();i++){var a=f.worker().getPersonalInventory().getItem(i);if(a.is(Items.WHEAT_SEEDS))return a;}return ItemStack.EMPTY;}
    private static long pickups(Fixture f){return TransferLedgerData.get(f.level().getServer()).audits().stream().filter(a->a.authority().equals("farmer_seed:"+f.person())&&a.status()==TransferReceipt.Status.SUCCESS).count();}
    private static void blocked(Fixture f){require(f.plants().size()==1&&replacement(f).isEmpty(),"last initial seed consumed, no phantom replacement or planting");require(f.level().getBlockState(SECOND).isAir(),"second sowing target remains empty");require(pickups(f)==0,"no unauthorized seed withdrawal");}
    private static void finish(GameTestHelper h,Fixture f,long start){
        require(f.plants().size()==2&&f.plants().stream().allMatch(r->r.toolDamage()==0),"two real planting receipts");
        require(f.plants().stream().allMatch(r->r.inventoryDeltas().stream().anyMatch(d->d.fingerprint().startsWith("minecraft:wheat_seeds#")&&d.quantity()==-1)),"each planting consumed exactly one real seed");
        require(f.level().getBlockState(FIRST).is(Blocks.WHEAT)&&f.level().getBlockState(SECOND).is(Blocks.WHEAT),"both original farmland cells actually planted");
        require(replacement(f).isEmpty()&&SupplyFlowService.count(f.source(),SEED)==0&&SupplyFlowService.count(f.pantry(),SEED)==0&&SupplyFlowService.count(f.carrier().getTransportInventory(),SEED)==0&&pickups(f)==1,"initial two seeds consumed into two crops; all physical inventories zero; one pickup");
        var s=f.level().getServer();require(CharacterRegistryData.get(s).allCharacters().size()==2&&f.person().equals(f.worker().getCharacterId()),"same original people");
        var home=HouseholdData.get(s).byId(f.household());require(f.settlement().equals(home.settlementId())&&home.contains(f.person())&&home.contains(f.carrier().getCharacterId()),"same real settlement and household");
        require(SettlementRegistryData.get(s).buildings().stream().filter(b->b.settlementId().equals(f.settlement())&&(b.position().equals(SOURCE)||b.position().equals(PANTRY)||b.position().equals(FIRST.below()))).count()==3,"both stores and field actually registered to same village");
        for(var npc:List.of(f.worker(),f.carrier()))require(f.settlement().equals(NpcLifeData.get(s).record(npc.getCharacterId()).originSettlementId()),"resident actually associated to village");
        require(NeedRequestData.get(s).requests().stream().anyMatch(r->r.key().item().equals(SEED)&&r.delivered()==1),"actual one-seed demand delivery");
        require(SupplyFlowData.get(s).routes().stream().anyMatch(r->r.stage()==SupplyFlowData.Stage.DONE&&f.courier().equals(r.carrierEntity())),"existing courier actually delivered");
        var ledger=TransferLedgerData.get(s);var audit=ledger.audits().stream().filter(a->a.authority().equals("farmer_seed:"+f.person())&&a.status()==TransferReceipt.Status.SUCCESS).findFirst().orElseThrow();
        var old=NpcBrainRuntime.data(s).history(f.miner()).stream().filter(p->p.id().equals(audit.transferId())).findFirst().orElseThrow();
        var before=ledger.save(new CompoundTag(),s.registryAccess());var executor=new FieldworkSeedTaskExecutor();var context=new com.jgeted.sagadyssey.npc.brain.execution.NpcEntityExecutionContext(f.worker(),f.level());
        for(int i=0;i<2;i++)require(executor.recover(context,old,old.steps().getFirst()).state()==com.jgeted.sagadyssey.npc.brain.execution.TaskTickResult.State.STEP_SUCCEEDED,"same successful seed pickup recovered without resubmission");
        require(before.equals(ledger.save(new CompoundTag(),s.registryAccess()))&&pickups(f)==1&&replacement(f).isEmpty()&&SupplyFlowService.count(f.pantry(),SEED)==0,"replayed pickup did not recreate or deduct another seed");
        record(f,start,"accepted");Sagadyssey.LOGGER.info("SEEDS_ACCEPT phase={} elapsed={} farmer={} person={} plants=2 source=0 pantry=0 cargo=0 personal=0 pickups=1 consumed=2",phase(),f.level().getGameTime()-start,f.miner(),f.person());h.succeed();
    }
    private static void run(GameTestHelper h){
        var t=restored;var f=t==null?fixture(h):new Fixture(h.getLevel(),t.read("Settlement", UUIDUtil.CODEC).orElseThrow(),t.read("Household", UUIDUtil.CODEC).orElseThrow(),t.read("Farmer", UUIDUtil.CODEC).orElseThrow(),t.read("Person", UUIDUtil.CODEC).orElseThrow(),t.read("Courier", UUIDUtil.CODEC).orElseThrow(),t.read("Source", UUIDUtil.CODEC).orElseThrow(),t.read("SourceGrant", UUIDUtil.CODEC).orElseThrow(),t.read("PickupGrant", UUIDUtil.CODEC).orElseThrow());
        long start=t==null?f.level().getGameTime():t.getLongOr("Started", 0L);long[] broken={-1},delivered={-1},complete={-1};boolean[] recovered={false};
        if(t==null){if(phase().equals("source-right"))RightsStateData.get(f.level().getServer()).revoke(f.sourceGrant());if(phase().equals("pickup-right")||phase().equals("pre-save"))RightsStateData.get(f.level().getServer()).revoke(f.pickupGrant());if(phase().equals("no-source"))f.source().clearContent();}
        if(t==null&&phase().equals("pickup-right"))RightsStateData.get(f.level().getServer()).grant(new RightGrant(f.pickupGrant(),f.pantryId(),SubjectRef.character(f.person()),EnumSet.of(AssetRight.ACCESS,AssetRight.TRANSFER),"access and transfer alone do not authorize consuming seed stock",f.level().getGameTime(),null,true));
        h.onEachTick(()->{
            if(f.worker()==null||f.carrier()==null)return;long now=f.level().getGameTime();
            if((now-start)%200==0){record(f,start,"progress");Sagadyssey.LOGGER.info("SEEDS_PROGRESS tick={} plants={} source={} pantry={} personal={} workerPos={} active={}",now-start,f.plants().size(),SupplyFlowService.count(f.source(),SEED),SupplyFlowService.count(f.pantry(),SEED),replacement(f),f.worker().position(),NpcBrainRuntime.data(f.level().getServer()).active(f.miner()));}
            if(t!=null){require(originalInventoryVerified,"original inventory verified on world load before first entity tick");require(f.person().equals(f.worker().getCharacterId()),"original identity recovered");if(phase().equals("pre-resume")&&!recovered[0]){grantPickup(f);recovered[0]=true;}}
            if(f.plants().size()>=1&&broken[0]<0){require(f.plants().getFirst().action().target().equals(FIRST),"first target order");if(t==null)require(replacement(f).isEmpty(),"first actual planting consumed last initial seed");broken[0]=now;f.carrier().setNoAi(false);record(f,start,"broken");}
            if(broken[0]<0)return;
            if((phase().equals("source-right")||phase().equals("no-source"))&&!recovered[0]){
                blocked(f);if(now-broken[0]<400)return;require(SupplyFlowService.count(f.pantry(),SEED)==0,"no illegal source credit");record(f,start,"blocked");
                if(phase().equals("no-source"))f.source().setItem(0,new ItemStack(Items.WHEAT_SEEDS));else RightsStateData.get(f.level().getServer()).grant(new RightGrant(f.sourceGrant(),f.sourceId(),SubjectRef.household(f.household()),EnumSet.of(AssetRight.ACCESS,AssetRight.CONSUME,AssetRight.TRANSFER),"restored source grant",now,null,true));recovered[0]=true;
            }
            if(SupplyFlowService.count(f.pantry(),SEED)==1&&delivered[0]<0){delivered[0]=now;record(f,start,"arrived");}
            if((phase().equals("audit")||phase().equals("pickup-right")||phase().equals("pre-save"))&&delivered[0]>=0&&!recovered[0]){
                blocked(f);if(now-delivered[0]<240)return;record(f,start,"blocked");
                if(phase().equals("audit")){Sagadyssey.LOGGER.info("SEEDS_GAP_CONFIRMED deliveredToHousehold=1 farmerSeeds=0 plants=1 elapsed={}",now-start);h.succeed();return;}
                if(phase().equals("pre-save")){snapshot(f,start);h.succeed();return;}grantPickup(f);recovered[0]=true;
            }
            if(phase().equals("post-save")&&pickups(f)==1&&f.plants().size()==1){snapshot(f,start);h.succeed();return;}
            if(f.plants().size()==2){if(complete[0]<0)complete[0]=now;if(now-complete[0]>=200)finish(h,f,start);}
        });
    }
    private static void grantPickup(Fixture f){RightsStateData.get(f.level().getServer()).grant(new RightGrant(f.pickupGrant(),f.pantryId(),SubjectRef.character(f.person()),EnumSet.of(AssetRight.ACCESS,AssetRight.CONSUME,AssetRight.TRANSFER),"restored seed gift authorization",f.level().getGameTime(),null,true));}
}
