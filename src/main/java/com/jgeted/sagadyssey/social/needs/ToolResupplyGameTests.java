package com.jgeted.sagadyssey.social.needs;

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
import net.minecraft.resources.ResourceLocation;
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

/** Opt-in finite spare-tool chain, with isolated coherent world checkpoints. */
@GameTestHolder("sagadyssey_tools")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid="sagadyssey")
public final class ToolResupplyGameTests {
    private static final BlockPos BASE=new BlockPos(384,80,384), SOURCE=BASE.offset(10,1,9), PANTRY=BASE.offset(22,1,9);
    private static final BlockPos FIRST=BASE.offset(3,1,6), SECOND=BASE.offset(5,1,6);
    private static final ItemFingerprint TOOL=new ItemFingerprint("minecraft:iron_pickaxe",0);
    private static final ResourceLocation EMPTY=ResourceLocation.parse("sagadyssey_tools:empty");
    private static final String MARKER="tool-resupply.nbt";
    private static CompoundTag restored;
    private static boolean originalInventoryVerified;
    private static String phase(){return System.getProperty("sagadyssey.tools.phase","disabled");}
    private static void require(boolean ok,String text){if(!ok)throw new IllegalStateException(text);}
    private record Fixture(ServerLevel level,UUID settlement,UUID household,UUID miner,UUID person,UUID courier,UUID sourceId,UUID sourceGrant,UUID pickupGrant) {
        NpcBase worker(){return (NpcBase)level.getEntity(miner);}
        NpcBase carrier(){return (NpcBase)level.getEntity(courier);}
        Container source(){return (Container)level.getBlockEntity(SOURCE);}
        Container pantry(){return (Container)level.getBlockEntity(PANTRY);}
        UUID pantryId(){return ContainerIds.block("household_storage",level.dimension().location().toString(),PANTRY);}
        List<FieldworkActionReceipt> mines(){return FieldworkData.get(level.getServer()).receiptsFor(miner).stream().filter(r->r.action().operation()==FieldworkAction.Operation.MINE).toList();}
    }
    private static void force(ServerLevel l){for(int x=23;x<=26;x++)for(int z=23;z<=25;z++){l.setChunkForced(x,z,true);l.getChunk(x,z);}}
    @SubscribeEvent public static void install(ServerStartedEvent e) {
        if(!(e.getServer() instanceof GameTestServer)||phase().equals("disabled"))return;
        var s=e.getServer();var l=s.overworld();restored=null;originalInventoryVerified=false;
        try {
            l.getStructureManager().getOrCreate(EMPTY).load(l.holderLookup(Registries.BLOCK),TagParser.parseTag("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
            if(phase().endsWith("resume")) {
                restored=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve(MARKER),NbtAccounter.unlimitedHeap());
                var world=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap());
                s.getWorldData().overworldData().setGameTime(Math.max(restored.getLong("Time"),world.getCompound("Data").getLong("Time"))+1);
                var current=ledgers(l);
                for(String key:current.getAllKeys()){
                    Tag observed=current.get(key),expected=restored.getCompound("Ledgers").get(key);
                    if(key.equals("Transfers")){observed=transferMapOrder((CompoundTag)observed);expected=transferMapOrder((CompoundTag)expected);}
                    if(!observed.equals(expected)){
                        Files.writeString(s.getWorldPath(LevelResource.ROOT).resolve("tool-mismatch-current-"+key+".snbt"),current.get(key).toString());
                        Files.writeString(s.getWorldPath(LevelResource.ROOT).resolve("tool-mismatch-expected-"+key+".snbt"),restored.getCompound("Ledgers").get(key).toString());
                    }
                    require(observed.equals(expected),"whole-world ledger mismatch: "+key);
                }
                var proof=restored.copy();proof.putBoolean("LinkedLedgersVerifiedBeforeTick",true);
                NbtIo.writeCompressed(proof,s.getWorldPath(LevelResource.ROOT).resolve("tool-restored-proof.nbt"));
            }
            force(l);l.setDayTime(1000);l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,s);l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,s);
            if(phase().equals("regression"))l.getStructureManager().getOrCreate(ResourceLocation.parse("sagadyssey_tools:fieldwork_empty")).load(l.holderLookup(Registries.BLOCK),TagParser.parseTag("{size:[96,10,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
        }catch(Exception x){throw new IllegalStateException(x);}
    }
    // TransferReceipt.finalRevisions is a Map; only this list represents an unordered map.
    // Keep receipt chronology, audit order, route stages and every quantity strictly compared.
    private static CompoundTag transferMapOrder(CompoundTag source){
        var t=source.copy();for(var raw:t.getList("Receipts",Tag.TAG_COMPOUND)){
            var receipt=(CompoundTag)raw;var entries=new ArrayList<CompoundTag>();
            for(var value:receipt.getList("Revisions",Tag.TAG_COMPOUND))entries.add((CompoundTag)value);
            entries.sort(Comparator.comparing(v->v.getUUID("Container").toString()));var list=new ListTag();entries.forEach(list::add);receipt.put("Revisions",list);
        }return t;
    }
    @SubscribeEvent public static void originalBodyLoaded(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e){
        if(restored==null||!(e.getLevel() instanceof ServerLevel l)||!(e.getEntity() instanceof NpcBase body)||!body.getUUID().equals(restored.getUUID("Miner")))return;
        var actual=body.saveWithoutId(new CompoundTag());var expected=restored.getCompound("MinerEntity");
        for(String key:List.of("Items","HandItems","PersonalInventoryRevision","HomeSettlementId"))require(Objects.equals(actual.get(key),expected.get(key)),"original entity checkpoint mismatch: "+key);
        require(body.getCharacterId().equals(restored.getUUID("Person")),"original person before first entity tick");originalInventoryVerified=true;
        var proof=restored.copy();proof.putBoolean("LinkedLedgersVerifiedBeforeTick",true);proof.putBoolean("OriginalEntityInventoryVerifiedBeforeTick",true);
        try{NbtIo.writeCompressed(proof,l.getServer().getWorldPath(LevelResource.ROOT).resolve("tool-restored-proof.nbt"));}catch(Exception x){throw new IllegalStateException(x);}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){
        if(phase().equals("disabled"))return List.of();
        if(phase().equals("regression"))return regression();
        return List.of(new TestFunction("tools","tools_"+phase(),EMPTY.toString(),Rotation.NONE,2400,0,true,ToolResupplyGameTests::run));
    }
    private static Collection<TestFunction> regression(){
        var selected=new LinkedHashMap<Class<?>,List<String>>();
        selected.put(FieldworkGameTests.class,List.of("farmerHarvestReplantsAndDelivers","workerMinesAndRestsInsteadOfStandingForever","workerDiscoversAndWalksToEightyBlockTarget","missingToolRecoversAfterSupply","fullDestinationKeepsCargoAndRecovers","twoWorkersCannotDuplicateOneBlock","loggingIsOneBlockPerReceipt","scaffoldBuildAndRecoveryConserveOriginalMaterial","canceledWorkRecoversRecordedScaffoldOutsideNewZone","savedRunningTravelAndEntityReloadWithoutDuplicateProduction","fullPersonalBagDoesNotDestroyBlockOrWearTool","borrowedToolRequiresUseGrantAndRetainsOwner","changedProfessionAndZoneStopOldTravelBeforeMutation","unreachableBlockIsRememberedWhileAlternativeGetsDone"));
        selected.put(SupplyGameTests.class,List.of("courierTakesCarriesAndDeliversRealStock","twoCouriersCannotTakeSameShipmentTwice","knownForeignStorageDoesNotGrantRights","householdAggregatesAndRestocksActualSharedPantry"));
        var tests=new ArrayList<TestFunction>();
        selected.forEach((type,names)->names.forEach(name->{try{
            var method=type.getMethod(name,GameTestHelper.class);var annotation=method.getAnnotation(GameTest.class);
            tests.add(new TestFunction("tool_regression_"+name,"tool_regression_"+name,"sagadyssey_tools:fieldwork_empty",Rotation.NONE,annotation.timeoutTicks(),0,true,h->{
                FieldworkGameTests.templates(h.getLevel());if(type==SupplyGameTests.class)SupplyGameTests.day(h.getLevel());
                try{method.invoke(null,h);}catch(java.lang.reflect.InvocationTargetException x){if(x.getCause() instanceof RuntimeException r)throw r;throw new IllegalStateException(x.getCause());}catch(Exception x){throw new IllegalStateException(x);}
            }));
        }catch(Exception x){throw new IllegalStateException(x);}}));
        Sagadyssey.LOGGER.info("TOOLS_REGRESSION_SELECTED count={}",tests.size());return tests;
    }
    private static NpcBase npc(ServerLevel l,BlockPos p,NpcProfession job,UUID settlement){
        var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),l);n.moveTo(p.getX()+.5,p.getY(),p.getZ()+.5,0,0);n.setProfession(job);n.setHomeSettlementId(settlement);n.setPersistenceRequired();
        n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();n.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);n.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
        n.setCommand(NpcCommand.WORK);l.addFreshEntity(n);NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);return n;
    }
    private static Fixture fixture(GameTestHelper h){
        var l=h.getLevel();var s=l.getServer();force(l);
        for(var p:BlockPos.betweenClosed(BASE,BASE.offset(25,5,12)))l.setBlockAndUpdate(p,p.getY()==BASE.getY()?Blocks.BEDROCK.defaultBlockState():Blocks.AIR.defaultBlockState());
        l.setBlockAndUpdate(FIRST,Blocks.COAL_ORE.defaultBlockState());l.setBlockAndUpdate(SECOND,Blocks.COAL_ORE.defaultBlockState());
        var registry=SettlementRegistryData.get(s);var village=registry.create(s,"tool village",SettlementType.VILLAGE,l.dimension().location().toString(),BASE,48,"sagadyssey:wilderness",null,l.getGameTime());
        var miner=npc(l,BASE.offset(2,1,6),NpcProfession.WORKER,village.id());var courier=npc(l,BASE.offset(2,1,10),NpcProfession.COURIER,village.id());courier.setNoAi(true);
        miner.setWorkZone(FIRST,SECOND);var worn=new ItemStack(Items.IRON_PICKAXE);worn.setDamageValue(worn.getMaxDamage()-1);miner.setItemSlot(EquipmentSlot.MAINHAND,worn);
        var people=List.of(miner,courier);for(int i=0;i<people.size();i++){
            var bed=BASE.offset(5+i*3,1,2);l.setBlockAndUpdate(bed,Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT));l.setBlockAndUpdate(bed.south(),Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.HEAD));
            NpcLifeData.get(s).registerInstitutional(people.get(i),l.dimension().location().toString(),village.id(),BASE,"tool residence",bed,null,NpcWorkShift.DAY,l.getGameTime(),false);
        }
        var homes=HouseholdData.get(s);var home=homes.create("tool household",l.dimension().location().toString(),village.id(),miner.getCharacterId(),BASE,l.getGameTime());homes.addMember(home.id(),courier.getCharacterId(),HouseholdRole.KIN,l.getGameTime());
        l.setBlockAndUpdate(SOURCE,Blocks.BARREL.defaultBlockState());l.setBlockAndUpdate(PANTRY,Blocks.BARREL.defaultBlockState());require(homes.assignSharedStorage(home.id(),home.dimension(),BASE,PANTRY),"pantry assigned");homes.setProvisionPlan(new HouseholdProvisionPlan(home.id(),0,0,1));
        registry.registerScenarioBuilding(s,home.dimension(),SOURCE,"sagadyssey:tool_source","warehouse","small",village.id(),l.getGameTime());registry.registerScenarioBuilding(s,home.dimension(),PANTRY,"sagadyssey:tool_pantry","warehouse","small",village.id(),l.getGameTime());
        var family=SubjectRef.household(home.id());var institution=SubjectRef.institution(village.id());var rights=RightsStateData.get(s);
        UUID source=ContainerIds.block("tool_source",home.dimension(),SOURCE),pantry=ContainerIds.block("household_storage",home.dimension(),PANTRY),sg=UUID.randomUUID(),pg=UUID.randomUUID();
        rights.ensureContext(source,"tool-source",institution,institution,AccessPolicy.PRIVATE);rights.ensureContext(pantry,"tool-pantry",family,family,AccessPolicy.HOUSEHOLD);
        rights.grant(new RightGrant(sg,source,family,EnumSet.of(AssetRight.ACCESS,AssetRight.USE,AssetRight.TRANSFER),"explicit source tool grant",l.getGameTime(),null,true));
        rights.grant(new RightGrant(pg,pantry,SubjectRef.character(miner.getCharacterId()),EnumSet.of(AssetRight.ACCESS,AssetRight.USE,AssetRight.TRANSFER),"explicit spare-tool gift authorization",l.getGameTime(),null,true));
        ((Container)l.getBlockEntity(SOURCE)).setItem(0,new ItemStack(Items.IRON_PICKAXE));
        var flows=SupplyFlowData.get(s);flows.register(new FieldworkStorage(home.dimension(),SOURCE,source,institution,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"tool-source"));flows.reveal(family,source);
        return new Fixture(l,village.id(),home.id(),miner.getUUID(),miner.getCharacterId(),courier.getUUID(),source,sg,pg);
    }
    private static CompoundTag ledgers(ServerLevel l){
        var s=l.getServer();var t=new CompoundTag();var r=s.registryAccess();
        t.put("Needs",NeedRequestData.get(s).save(new CompoundTag(),r));t.put("Flows",SupplyFlowData.get(s).save(new CompoundTag(),r));t.put("Rights",RightsStateData.get(s).save(new CompoundTag(),r));t.put("Transfers",TransferLedgerData.get(s).save(new CompoundTag(),r));
        t.put("Characters",CharacterRegistryData.get(s).save(new CompoundTag(),r));t.put("Households",HouseholdData.get(s).save(new CompoundTag(),r));t.put("Brain",NpcBrainRuntime.data(s).save(new CompoundTag(),r));t.put("Fieldwork",FieldworkData.get(s).save(new CompoundTag(),r));return t;
    }
    private static CompoundTag describe(Fixture f,long start){
        var t=new CompoundTag();t.putString("Phase",phase());t.putLong("Time",f.level().getGameTime());t.putLong("Started",start);
        t.putUUID("Settlement",f.settlement());t.putUUID("Household",f.household());t.putUUID("Miner",f.miner());t.putUUID("Person",f.person());t.putUUID("Courier",f.courier());t.putUUID("Source",f.sourceId());t.putUUID("SourceGrant",f.sourceGrant());t.putUUID("PickupGrant",f.pickupGrant());
        t.put("Ledgers",ledgers(f.level()));t.put("MinerEntity",f.worker().saveWithoutId(new CompoundTag()));t.putInt("Mines",f.mines().size());return t;
    }
    private static void record(Fixture f,long start,String label){try{NbtIo.writeCompressed(describe(f,start),f.level().getServer().getWorldPath(LevelResource.ROOT).resolve("tool-"+label+".nbt"));}catch(Exception x){throw new IllegalStateException(x);}}
    private static void snapshot(Fixture f,long start){try{
        var s=f.level().getServer();var root=s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();NbtIo.writeCompressed(describe(f,start),root.resolve(MARKER));s.saveEverything(false,true,false);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        var dest=root.resolveSibling(root.getFileName()+"-tool-snapshot");require(!Files.exists(dest),"new snapshot only");Files.createDirectories(dest);
        try(var files=Files.walk(root)){for(var p:files.toList()){if(p.getFileName().toString().equals("session.lock"))continue;var q=dest.resolve(root.relativize(p));if(Files.isDirectory(p))Files.createDirectories(q);else Files.copy(p,q);}}
        Sagadyssey.LOGGER.info("TOOLS_WHOLE_WORLD_SNAPSHOT phase={} mines={} path={}",phase(),f.mines().size(),dest);
    }catch(Exception x){throw new IllegalStateException(x);}}
    private static ItemStack replacement(Fixture f){for(int i=0;i<f.worker().getPersonalInventory().getContainerSize();i++){var a=f.worker().getPersonalInventory().getItem(i);if(a.is(Items.IRON_PICKAXE))return a;}return ItemStack.EMPTY;}
    private static long pickups(Fixture f){return TransferLedgerData.get(f.level().getServer()).audits().stream().filter(a->a.authority().equals("worker_tool:"+f.person())&&a.status()==TransferReceipt.Status.SUCCESS).count();}
    private static void blocked(Fixture f){require(f.mines().size()==1&&f.worker().getMainHandItem().isEmpty()&&replacement(f).isEmpty(),"broken old tool, no phantom replacement or work");require(f.level().getBlockState(SECOND).is(Blocks.COAL_ORE),"second real target preserved");require(pickups(f)==0,"no unauthorized tool withdrawal");}
    private static void finish(GameTestHelper h,Fixture f,long start){
        require(f.mines().size()==2&&f.mines().stream().allMatch(r->r.toolDamage()==1),"two real mining receipts, each wear one");
        require(f.mines().getFirst().inventoryDeltas().stream().anyMatch(d->d.fingerprint().startsWith("minecraft:iron_pickaxe#")&&d.quantity()==-1),"first receipt records actual old pickaxe destruction");
        require(f.level().getBlockState(FIRST).isAir()&&f.level().getBlockState(SECOND).isAir(),"both original targets actually mined");
        require(f.worker().getMainHandItem().isEmpty()&&replacement(f).is(Items.IRON_PICKAXE)&&replacement(f).getDamageValue()==1,"old pickaxe broke, spare reached original worker and wore once");
        require(SupplyFlowService.count(f.source(),TOOL)==0&&SupplyFlowService.count(f.pantry(),TOOL)==0&&SupplyFlowService.count(f.carrier().getTransportInventory(),TOOL)==0&&pickups(f)==1,"source 0, pantry 0, transport 0, exactly one pickup");
        int coal=f.worker().getPersonalInventory().count(a->a.is(Items.COAL));for(int i=0;i<f.pantry().getContainerSize();i++)if(f.pantry().getItem(i).is(Items.COAL))coal+=f.pantry().getItem(i).getCount();require(coal==2,"two real coal outputs accounted");
        var s=f.level().getServer();require(CharacterRegistryData.get(s).allCharacters().size()==2&&f.person().equals(f.worker().getCharacterId()),"same original people");
        require(f.settlement().equals(HouseholdData.get(s).byId(f.household()).settlementId())&&f.household().equals(HouseholdData.get(s).forMember(f.person()).id()),"same real settlement and household");
        require(NeedRequestData.get(s).requests().stream().anyMatch(r->r.key().item().equals(TOOL)&&r.delivered()==1),"actual one-tool demand delivery");
        require(SupplyFlowData.get(s).routes().stream().anyMatch(r->r.stage()==SupplyFlowData.Stage.DONE&&f.courier().equals(r.carrierEntity())),"existing courier actually delivered");
        var ledger=TransferLedgerData.get(s);var audit=ledger.audits().stream().filter(a->a.authority().equals("worker_tool:"+f.person())&&a.status()==TransferReceipt.Status.SUCCESS).findFirst().orElseThrow();
        var old=NpcBrainRuntime.data(s).history(f.miner()).stream().filter(p->p.id().equals(audit.transferId())).findFirst().orElseThrow();
        var before=ledger.save(new CompoundTag(),s.registryAccess());var executor=new FieldworkToolTaskExecutor();var context=new com.jgeted.sagadyssey.npc.brain.execution.NpcEntityExecutionContext(f.worker(),f.level());
        for(int i=0;i<2;i++)require(executor.recover(context,old,old.steps().getFirst()).state()==com.jgeted.sagadyssey.npc.brain.execution.TaskTickResult.State.STEP_SUCCEEDED,"same completed pickup recovers without another transfer");
        require(before.equals(ledger.save(new CompoundTag(),s.registryAccess()))&&pickups(f)==1&&replacement(f).getCount()==1&&replacement(f).getDamageValue()==1,"replayed completed pickup leaves inventory and ledger unchanged");
        record(f,start,"accepted");Sagadyssey.LOGGER.info("TOOLS_ACCEPT phase={} elapsed={} miner={} person={} mines=2 coal=2 source=0 pantry=0 cargo=0 pickups=1 replacementDamage=1",phase(),f.level().getGameTime()-start,f.miner(),f.person());h.succeed();
    }
    private static void run(GameTestHelper h){
        var t=restored;var f=t==null?fixture(h):new Fixture(h.getLevel(),t.getUUID("Settlement"),t.getUUID("Household"),t.getUUID("Miner"),t.getUUID("Person"),t.getUUID("Courier"),t.getUUID("Source"),t.getUUID("SourceGrant"),t.getUUID("PickupGrant"));
        long start=t==null?f.level().getGameTime():t.getLong("Started");long[] broken={-1},delivered={-1},complete={-1};boolean[] recovered={false};
        if(t==null){if(phase().equals("source-right"))RightsStateData.get(f.level().getServer()).revoke(f.sourceGrant());if(phase().equals("pickup-right")||phase().equals("pre-save"))RightsStateData.get(f.level().getServer()).revoke(f.pickupGrant());if(phase().equals("no-source"))f.source().clearContent();}
        h.onEachTick(()->{
            if(f.worker()==null||f.carrier()==null)return;long now=f.level().getGameTime();
            if((now-start)%200==0){record(f,start,"progress");Sagadyssey.LOGGER.info("TOOLS_PROGRESS tick={} mines={} source={} pantry={} personal={} workerPos={} active={}",now-start,f.mines().size(),SupplyFlowService.count(f.source(),TOOL),SupplyFlowService.count(f.pantry(),TOOL),replacement(f),f.worker().position(),NpcBrainRuntime.data(f.level().getServer()).active(f.miner()));}
            if(t!=null){require(originalInventoryVerified,"original inventory verified on world load before first entity tick");require(f.person().equals(f.worker().getCharacterId()),"original identity recovered");if(phase().equals("pre-resume")&&!recovered[0]){grantPickup(f);recovered[0]=true;}}
            if(f.mines().size()>=1&&broken[0]<0){require(f.mines().getFirst().action().target().equals(FIRST),"first target order");require(f.worker().getMainHandItem().isEmpty(),"real first mining broke old pickaxe");broken[0]=now;f.carrier().setNoAi(false);record(f,start,"broken");}
            if(broken[0]<0)return;
            if((phase().equals("source-right")||phase().equals("no-source"))&&!recovered[0]){
                blocked(f);if(now-broken[0]<400)return;require(SupplyFlowService.count(f.pantry(),TOOL)==0,"no illegal source credit");record(f,start,"blocked");
                if(phase().equals("no-source"))f.source().setItem(0,new ItemStack(Items.IRON_PICKAXE));else RightsStateData.get(f.level().getServer()).grant(new RightGrant(f.sourceGrant(),f.sourceId(),SubjectRef.household(f.household()),EnumSet.of(AssetRight.ACCESS,AssetRight.USE,AssetRight.TRANSFER),"restored source grant",now,null,true));recovered[0]=true;
            }
            if(SupplyFlowService.count(f.pantry(),TOOL)==1&&delivered[0]<0){delivered[0]=now;record(f,start,"arrived");}
            if((phase().equals("audit")||phase().equals("pickup-right")||phase().equals("pre-save"))&&delivered[0]>=0&&!recovered[0]){
                blocked(f);if(now-delivered[0]<240)return;record(f,start,"blocked");
                if(phase().equals("audit")){Sagadyssey.LOGGER.info("TOOLS_GAP_CONFIRMED deliveredToHousehold=1 minerTools=0 mines=1");h.succeed();return;}
                if(phase().equals("pre-save")){snapshot(f,start);h.succeed();return;}grantPickup(f);recovered[0]=true;
            }
            if(phase().equals("post-save")&&pickups(f)==1&&f.mines().size()==1){snapshot(f,start);h.succeed();return;}
            if(f.mines().size()==2){if(complete[0]<0)complete[0]=now;if(now-complete[0]>=200)finish(h,f,start);}
        });
    }
    private static void grantPickup(Fixture f){RightsStateData.get(f.level().getServer()).grant(new RightGrant(f.pickupGrant(),f.pantryId(),SubjectRef.character(f.person()),EnumSet.of(AssetRight.ACCESS,AssetRight.USE,AssetRight.TRANSFER),"restored spare-tool gift authorization",f.level().getGameTime(),null,true));}
}
