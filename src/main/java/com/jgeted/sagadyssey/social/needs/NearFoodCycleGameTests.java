package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.life.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.household.*;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.production.ProductionContainerIds;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Real configured potato farm/furnace/household. No maturity, food or receipt injection after setup. */
@GameTestHolder("sagadyssey_cycle")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class NearFoodCycleGameTests {
    private static CompoundTag previous;
    private static final String MARKER = "near-food-cycle-checkpoint.nbt";
    private static boolean restart() { return "restart".equals(System.getProperty("sagadyssey.nearCycle.phase")); }
    @net.neoforged.bus.api.SubscribeEvent
    public static void restore(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        previous=null;
        if (!(event.getServer() instanceof GameTestServer)) return;
        try {
            event.getServer().overworld().getStructureManager().getOrCreate(net.minecraft.resources.Identifier.parse("sagadyssey_cycle:fieldwork_empty"))
                    .load(event.getServer().overworld().holderLookup(net.minecraft.core.registries.Registries.BLOCK),TagParser.parseCompoundFully("{size:[22,10,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
        } catch(Exception e) { throw new IllegalStateException("isolated cycle test template",e); }
        if(!restart()) return;
        try {
            var server=event.getServer();
            previous=NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve(MARKER),NbtAccounter.unlimitedHeap());
            // GameTestServer resets its clock; retain the final saved world time, including later batches.
            var savedWorld=NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap());
            long resume=Math.max(previous.getLongOr("Time", 0L),savedWorld.getCompoundOrEmpty("Data").getLongOr("Time", 0L));
            server.getWorldData().overworldData().setGameTime(resume+1);
            force(server.overworld(),BlockPos.of(previous.getLongOr("Base", 0L)));
        } catch(Exception e) { throw new IllegalStateException("required original food cycle checkpoint",e); }
    }
    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        if(System.getProperty("sagadyssey.nearCycle.phase", "").startsWith("self-"))return List.of();
        if("selection".equals(System.getProperty("sagadyssey.nearCycle.phase"))) return List.of(test("empty_private_does_not_hide_family_food",NearFoodCycleGameTests::privateFallback,100));
        if("continuous".equals(System.getProperty("sagadyssey.nearCycle.phase"))) return List.of(test("continuous_three_days",NearFoodCycleGameTests::continuous,74000));
        if("life-regression".equals(System.getProperty("sagadyssey.nearCycle.phase"))) return List.of(
                test("private_food_rights",LifeGameTests::walksToPrivateFoodAndConsumesWithRights,600),
                test("foreign_food_denied",LifeGameTests::foreignOwnedFoodCannotBeEaten,400),
                test("empty_then_resupply",LifeGameTests::noFoodThenResupplyRecoversWithoutFakeMeal,700),
                test("family_care",LifeGameTests::familyCareUsesOneRealRationAtRecipient,500),
                test("meal_disk_reload",LifeGameTests::completedMealDiskReloadDoesNotConsumeAgain,700),
                test("empty_private_fallback",NearFoodCycleGameTests::privateFallback,100));
        if(restart()) return List.of(test("independent_process_recovery",NearFoodCycleGameTests::recover,1600));
        return List.of(test("normal_two_growth_cycles",h->cycle(h,false),10000),
                test("missing_fuel_then_recovery",h->cycle(h,true),10000),
                test("missing_seed_has_explained_shortage",NearFoodCycleGameTests::missingSeed,1800),
                test("settlement_daily_supply_shortage_recovery",NearFoodCycleGameTests::settlement,52000),
                test("existing_supply_to_forging",SupplyGameTests::shortageToTransportToForgingCompletesActualProduction,1800),
                test("existing_physical_unload_recovery",com.jgeted.sagadyssey.worldsim.lod.LodGameTests::realChunkUnloadKeepsOriginalBodyAndCannotEarnOfflineForgeWork,20000));
    }
    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> body,int ticks) {
        return new TestFunction("near_cycle_"+name,"sagadyssey_cycle."+name,"sagadyssey_cycle:fieldwork_empty",ticks,0,true,body);
    }
    private static void force(ServerLevel level,BlockPos base) {
        for(int x=-1;x<=2;x++)for(int z=-1;z<=1;z++) {
            level.setChunkForced((base.getX()>>4)+x,(base.getZ()>>4)+z,true);
            level.getChunk((base.getX()>>4)+x,(base.getZ()>>4)+z);
        }
    }
    private record Fixture(BlockPos base,BlockPos crop,BlockPos input,BlockPos furnace,BlockPos output,BlockPos pantry,
                           UUID household,NpcBase farmer,NpcBase courier,int initialMoral) {}
    private static NpcBase actor(ServerLevel level,BlockPos pos,NpcProfession role) {
        var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),level);n.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
        n.setProfession(role);n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();
        n.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);n.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
        n.setPersistenceRequired();n.setCommand(NpcCommand.WORK);level.addFreshEntity(n);
        NpcLifeData.get(level.getServer()).ensureRegistered(n,level.getGameTime());
        NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);return n;
    }
    private static Container box(ServerLevel l,BlockPos p) { return (Container)l.getBlockEntity(p); }
    private static Fixture setup(GameTestHelper h,boolean fuel,boolean seed) {
        var l=h.getLevel();var server=l.getServer();
        var base=h.absolutePos(new BlockPos(4096,1,4096));force(l,base);
        l.setDayTime(1000);l.getGameRules().set(GameRules.ADVANCE_TIME, false,server);
        l.getGameRules().set(GameRules.SPAWN_MOBS, false,server);
        for(int x=0;x<22;x++)for(int z=0;z<12;z++)l.setBlockAndUpdate(base.offset(x,-1,z),Blocks.STONE.defaultBlockState());
        var crop=base.offset(4,0,5);l.setBlockAndUpdate(crop.below(),Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7));
        l.setBlockAndUpdate(crop.offset(0,-1,1),Blocks.WATER.defaultBlockState());
        l.setBlockAndUpdate(crop.south(),Blocks.STONE.defaultBlockState());
        l.setBlockAndUpdate(crop.above(3),Blocks.GLOWSTONE.defaultBlockState());
        // Age zero belongs to the initial planted reserve; all later maturity comes from vanilla randomTick.
        if(seed) l.setBlockAndUpdate(crop,Blocks.POTATOES.defaultBlockState());
        var furnace=base.offset(8,1,5);var input=furnace.above(2);var output=base.offset(9,0,5);var pantry=base.offset(17,0,5);
        l.setBlockAndUpdate(furnace,Blocks.FURNACE.defaultBlockState());
        l.setBlockAndUpdate(input,Blocks.BARREL.defaultBlockState());
        l.setBlockAndUpdate(input.below(),Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING,Direction.DOWN));
        l.setBlockAndUpdate(furnace.below(),Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING,Direction.EAST));
        l.setBlockAndUpdate(output,Blocks.BARREL.defaultBlockState());l.setBlockAndUpdate(pantry,Blocks.BARREL.defaultBlockState());
        if(fuel) box(l,furnace).setItem(1,new ItemStack(Items.COAL,2));
        var farmer=actor(l,base.offset(2,0,5),NpcProfession.FARMER);farmer.setFarmMode(FarmMode.CROP);farmer.setWorkZone(crop,crop);
        if(seed) farmer.getPersonalInventory().setItem(0,new ItemStack(Items.POTATO,16));
        var courier=actor(l,base.offset(15,0,5),NpcProfession.COURIER);
        var life=NpcLifeData.get(server);life.setHunger(courier.getCharacterId(),seed?340:920,l.getGameTime());
        String dim=l.dimension().identifier().toString();
        h.assertTrue(life.assignPrivateStorage(server,farmer.getCharacterId(),dim,input,l.getGameTime()),"real configured private input barrel");
        var homes=HouseholdData.get(server);var home=homes.create("near-cycle:"+UUID.randomUUID(),dim,null,courier.getCharacterId(),pantry,l.getGameTime());
        h.assertTrue(homes.assignSharedStorage(home.id(),dim,pantry,pantry),"real household pantry");
        homes.setProvisionPlan(new HouseholdProvisionPlan(home.id(),2,0,0));
        var owner=SubjectRef.character(farmer.getCharacterId());var principal=SubjectRef.household(home.id());
        var id=ProductionContainerIds.resolve(server,dim,output);
        var rights=RightsStateData.get(server);rights.ensureContext(id,dim+":"+output.asLong(),owner,owner,AccessPolicy.PRIVATE);
        rights.grant(new RightGrant(UUID.randomUUID(),id,principal,Set.of(AssetRight.TRANSFER),"configured household food supply",l.getGameTime(),null,true));
        var flows=SupplyFlowData.get(server);flows.register(new FieldworkStorage(dim,output,id,owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"configured-vanilla-furnace"));flows.reveal(principal,id);
        return new Fixture(base,crop,input,furnace,output,pantry,home.id(),farmer,courier,courier.getMoral());
    }
    private static long operations(Fixture f,FieldworkAction.Operation op) {
        return FieldworkData.get(MinecraftApi.server(f.farmer())).receiptsFor(f.farmer().getUUID()).stream().filter(r->r.action().operation()==op).count();
    }
    private static int delta(Fixture f,String item) {
        return FieldworkData.get(MinecraftApi.server(f.farmer())).receiptsFor(f.farmer().getUUID()).stream()
                .flatMap(r->r.inventoryDeltas().stream()).filter(d->d.fingerprint().equals(item+"#0")).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();
    }
    private static int count(Container c,Item item) { int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n; }
    private static int physical(ServerLevel l,Fixture f,Item item) {
        int n=0;for(var p:List.of(f.input(),f.input().below(),f.furnace(),f.furnace().below(),f.output(),f.pantry()))n+=count(box(l,p),item);
        for(var actor:List.of(f.farmer(),f.courier()))n+=count(actor.getPersonalInventory(),item)+count(actor.getTransportInventory(),item);
        return n;
    }
    private static int cooked(ServerLevel l,Fixture f) {
        return l.getBlockEntity(f.furnace()).saveWithoutMetadata(l.registryAccess()).getCompoundOrEmpty("RecipesUsed").getIntOr("minecraft:baked_potato", 0);
    }
    private static List<String> meals(Fixture f) {
        return meals(f.courier());
    }
    private static List<String> meals(NpcBase actor) {
        var server=MinecraftApi.server(actor);
        return NpcBrainRuntime.data(server).history(actor.getUUID()).stream()
                .filter(p->p.providerId().equals(LifePlanFactory.PROVIDER)&&p.status()==NpcPlanStatus.SUCCEEDED
                        &&"EAT".equals(p.steps().getFirst().params().get("operation")))
                .map(p->p.id()+":2").filter(k->"DONE".equals(LifeActivityData.get(server).settlement(k))).toList();
    }
    private static CompoundTag account(GameTestHelper h,Fixture f,boolean finalCheck) {
        var l=h.getLevel();int raw=physical(l,f,Items.POTATO),baked=physical(l,f,Items.BAKED_POTATO),cooked=cooked(l,f),eaten=meals(f).size();
        int generated=delta(f,"minecraft:potato");
        h.assertTrue(16+generated==raw+cooked,"potato balance: initial16 + net field receipts "+generated+" != raw "+raw+" + cooked "+cooked);
        h.assertTrue(cooked==baked+eaten,"cooked balance: recipes "+cooked+" != physical "+baked+" + meals "+eaten);
        h.assertTrue(physical(l,f,Items.COAL)<=2,"fuel cannot increase");
        var tag=new CompoundTag();tag.putInt("Raw",raw);tag.putInt("Baked",baked);tag.putInt("Cooked",cooked);tag.putInt("NetCropDelta",generated);
        tag.putInt("Meals",eaten);tag.putInt("Coal",physical(l,f,Items.COAL));tag.putLong("Harvests",operations(f,FieldworkAction.Operation.HARVEST));
        tag.putLong("Plants",operations(f,FieldworkAction.Operation.PLANT));
        if(finalCheck) {
            h.assertTrue(tag.getLongOr("Harvests", 0L)>=2&&tag.getLongOr("Plants", 0L)>=2,"two complete growth, harvest and replant cycles");
            h.assertTrue(eaten>=1&&cooked>0,"newly produced food was actually eaten");
            h.assertTrue(NpcLifeData.get(l.getServer()).needs(f.courier().getCharacterId()).hunger()<350,"meal satisfies real hunger");
            for(var route:SupplyFlowData.get(l.getServer()).routes())if(NeedRequestData.get(l.getServer()).request(route.need()).key().requester().equals(f.household()))
                h.assertTrue(route.closed()&&route.pending()==null&&route.stage()!=SupplyFlowData.Stage.REVIEW,"no unresolved household cargo at final checkpoint");
        }
        return tag;
    }
    private static void cycle(GameTestHelper h,boolean shortage) {
        var l=h.getLevel();var f=setup(h,!shortage,true);long started=l.getGameTime();boolean[] fuelAdded={!shortage},parked={false};
        int[] randomTicks={0};
        h.onEachTick(()->{
            long elapsed=l.getGameTime()-started;
            if(operations(f,FieldworkAction.Operation.PLANT)>=2&&!parked[0]
                    && !FieldworkDepositTaskExecutor.hasDeliverableCargo(f.farmer(),FieldworkData.get(l.getServer()))) {
                f.farmer().setCommand(NpcCommand.STAY);NpcBrainRuntime.cancel(f.farmer(),PlanCancellationReason.MANUAL,"finite cycle observation complete");parked[0]=true;
            }
            if(operations(f,FieldworkAction.Operation.PLANT)<2&&elapsed%5==0) {
                var state=l.getBlockState(f.crop());
                if(state.getBlock() instanceof CropBlock c&&!c.isMaxAge(state)) {state.randomTick(l,f.crop(),l.random);randomTicks[0]++;}
            }
            if(shortage&&!fuelAdded[0]&&elapsed>=1800) {
                h.assertTrue(cooked(l,f)==0&&physical(l,f,Items.BAKED_POTATO)==0&&meals(f).isEmpty(),"no fuel means no output or phantom meal");
                h.assertTrue(count(box(l,f.furnace()),Items.POTATO)>0,"real produced potatoes reached an unfuelled furnace");
                h.assertTrue(NpcLifeData.get(l.getServer()).needs(f.courier().getCharacterId()).hunger()>=340,"unfed hunger never improves");
                account(h,f,false);box(l,f.furnace()).setItem(1,new ItemStack(Items.COAL,2));fuelAdded[0]=true;
                Sagadyssey.LOGGER.info("NEAR_CYCLE fuel shortage verified at {} ticks; explicitly supplied 2 coal",elapsed);
            }
            if(elapsed%1000==0)Sagadyssey.LOGGER.info("NEAR_CYCLE progress shortage={} elapsed={} growthCalls={} harvest={} plant={} raw={} cooked={} meals={} farmer={} courier={}",
                    shortage,elapsed,randomTicks[0],operations(f,FieldworkAction.Operation.HARVEST),operations(f,FieldworkAction.Operation.PLANT),physical(l,f,Items.POTATO),cooked(l,f),meals(f).size(),f.farmer().getWorkActivity(),f.courier().getWorkActivity());
            if(elapsed%1000==0)Sagadyssey.LOGGER.info("NEAR_CYCLE diagnostics crop={} soil={} farmerBag={} hopper={} furnace={} reason={} pendingCargo={}",l.getBlockState(f.crop()),l.getBlockState(f.crop().below()),count(f.farmer().getPersonalInventory(),Items.POTATO),count(box(l,f.input()),Items.POTATO),count(box(l,f.furnace()),Items.POTATO),f.farmer().getWorkActivityDetail(),FieldworkData.get(l.getServer()).pendingCargo(f.farmer().getUUID()));
        });
        h.succeedWhen(()->{
            h.assertTrue(fuelAdded[0]&&parked[0]&&meals(f).size()>=1,"wait for original actors to grow, produce, transport and eat");
            // Let the original physical delivery and its final plan step settle.
            h.assertTrue(l.getGameTime()-started>=2400,"observe a complete production and supply interval");
            h.assertTrue(physical(l,f,Items.POTATO)==16,"all new potatoes processed; only original seed reserve remains");
            var totals=account(h,f,true);h.assertTrue(randomTicks[0]>=7,"observed real vanilla growth calls, never set mature age");
            if(!shortage)save(h,f,totals,randomTicks[0]);
            Sagadyssey.LOGGER.info("NEAR_CYCLE ACCEPT shortage={} ticks={} growthCalls={} totals={}",shortage,l.getGameTime()-started,randomTicks[0],totals);
            f.courier().setCommand(NpcCommand.STAY);f.courier().setNoAi(true);f.farmer().setNoAi(true);
        });
    }
    private static void missingSeed(GameTestHelper h) {
        var f=setup(h,false,false);var l=h.getLevel();long start=l.getGameTime();
        h.runAtTickTime(1400,()->{
            h.assertTrue(operations(f,FieldworkAction.Operation.PLANT)==0&&operations(f,FieldworkAction.Operation.HARVEST)==0,"no seed means no invented crop or harvest");
            h.assertTrue(physical(l,f,Items.POTATO)==0&&physical(l,f,Items.BAKED_POTATO)==0&&meals(f).isEmpty(),"empty supply stays physically empty");
            h.assertTrue(NpcLifeData.get(l.getServer()).needs(f.courier().getCharacterId()).hunger()>=920&&f.courier().getMoral()<f.initialMoral(),"real severe shortage preserves hunger and lowers morale");
            var demand=NeedRequestData.get(l.getServer()).requests().stream().filter(r->r.key().requester().equals(f.household())&&!r.status().terminal()).toList();
            h.assertTrue(demand.size()==1&&demand.getFirst().status()==NeedRequestStatus.WAITING_EXTERNAL&&!demand.getFirst().detail().isBlank(),"one explained external-food wait");
            h.assertTrue(f.farmer().getWorkActivityDetail().contains("等待生长")||NpcBrainRuntime.data(l.getServer()).history(f.farmer().getUUID()).stream().anyMatch(p->p.steps().stream().anyMatch(s->s.params().values().stream().anyMatch(v->v.contains("等待生长")))),"missing production input has an explained wait");
            Sagadyssey.LOGGER.info("NEAR_CYCLE ACCEPT missing seed ticks={} hunger={} morale={} demand={}",l.getGameTime()-start,NpcLifeData.get(l.getServer()).needs(f.courier().getCharacterId()).hunger(),f.courier().getMoral(),demand);
            f.farmer().setNoAi(true);f.courier().setNoAi(true);h.succeed();
        });
    }
    private static void privateFallback(GameTestHelper h) {
        var f=setup(h,true,true);var l=h.getLevel();var homes=HouseholdData.get(l.getServer());
        h.assertTrue(homes.addMember(f.household(),f.farmer().getCharacterId(),HouseholdRole.KIN,l.getGameTime()),"farmer belongs to the supplied family");
        f.farmer().setNoAi(true);f.courier().setNoAi(true);
        NpcLifeData.get(l.getServer()).setHunger(f.farmer().getCharacterId(),500,l.getGameTime());
        box(l,f.pantry()).setItem(0,new ItemStack(Items.BAKED_POTATO,2));
        h.runAtTickTime(20,()->{
            // Planner seam: a grounded, paused actor; transport/consumption are covered by real-cycle tests.
            f.farmer().setOnGround(true);
            var chosen=LifePlanProvider.choose(f.farmer(),l.getGameTime(),false).orElseThrow();
            h.assertTrue("HOUSEHOLD".equals(chosen.steps().getFirst().params().get("source")),"empty or raw-only private processing input must not hide eligible household food; got "+chosen.steps().getFirst().params());h.succeed();
        });
    }
    private static void continuous(GameTestHelper h) {
        var f=setup(h,true,true);var l=h.getLevel();var server=l.getServer();
        h.assertTrue(HouseholdData.get(server).addMember(f.household(),f.farmer().getCharacterId(),HouseholdRole.KIN,l.getGameTime()),"production worker is a real family member");
        HouseholdData.get(server).setProvisionPlan(new HouseholdProvisionPlan(f.household(),4,0,0));
        NpcLifeData.get(server).setHunger(f.farmer().getCharacterId(),340,l.getGameTime());
        box(l,f.furnace()).setItem(1,new ItemStack(Items.COAL,64));
        long start=l.getGameTime();
        Set<String> workerReceipts=new HashSet<>(),courierReceipts=new HashSet<>();
        h.onEachTick(()->{
            long elapsed=l.getGameTime()-start;
            if(elapsed%20==0) {workerReceipts.addAll(meals(f.farmer()));courierReceipts.addAll(meals(f.courier()));}
            if(elapsed%100==0) {
                var state=l.getBlockState(f.crop());
                if(state.getBlock() instanceof CropBlock c&&!c.isMaxAge(state))state.randomTick(l,f.crop(),l.random);
            }
            if(elapsed%12000==0)Sagadyssey.LOGGER.info("NEAR_CYCLE continuous ticks={} harvest={} plants={} raw={} cooked={} workerHunger={} courierHunger={}",elapsed,operations(f,FieldworkAction.Operation.HARVEST),operations(f,FieldworkAction.Operation.PLANT),physical(l,f,Items.POTATO),cooked(l,f),NpcLifeData.get(server).needs(f.farmer().getCharacterId()).hunger(),NpcLifeData.get(server).needs(f.courier().getCharacterId()).hunger());
        });
        h.runAtTickTime(72000,()->{
            workerReceipts.addAll(meals(f.farmer()));courierReceipts.addAll(meals(f.courier()));
            int workerMeals=workerReceipts.size(),courierMeals=courierReceipts.size();
            h.assertTrue(workerMeals>=2&&courierMeals>=2,"both original family members repeatedly eat produced food over three days; worker="+workerMeals+" courier="+courierMeals);
            h.assertTrue(operations(f,FieldworkAction.Operation.HARVEST)>=2&&operations(f,FieldworkAction.Operation.PLANT)>=2,"repeated complete crop cycles; adequacy is measured by actual meals, hunger and conservation");
            h.assertTrue(count(f.farmer().getPersonalInventory(),Items.POTATO)>=15,"planting reserve not depleted by ordinary meals");
            int raw=physical(l,f,Items.POTATO),cooked=cooked(l,f),baked=physical(l,f,Items.BAKED_POTATO);
            h.assertTrue(16+delta(f,"minecraft:potato")==raw+cooked,"all raw and recipe conversions conserved over three days");
            h.assertTrue(cooked==baked+workerMeals+courierMeals,"all produced food is physical stock or completed meals");
            h.assertTrue(NpcLifeData.get(server).needs(f.farmer().getCharacterId()).hunger()<800&&NpcLifeData.get(server).needs(f.courier().getCharacterId()).hunger()<800,"configured productive capacity prevents severe hunger");
            Sagadyssey.LOGGER.info("NEAR_CYCLE ACCEPT continuous three days ticks={} harvests={} plants={} initialSeed=16 raw={} netCrop={} cooked={} baked={} workerMeals={} courierMeals={} coalRemaining={}",l.getGameTime()-start,operations(f,FieldworkAction.Operation.HARVEST),operations(f,FieldworkAction.Operation.PLANT),raw,delta(f,"minecraft:potato"),cooked,baked,workerMeals,courierMeals,physical(l,f,Items.COAL));
            f.farmer().setNoAi(true);f.courier().setNoAi(true);h.succeed();
        });
    }
    private static void settlement(GameTestHelper h) {
        var l=h.getLevel();var server=l.getServer();var base=h.absolutePos(new BlockPos(8192,1,8192));force(l,base);
        for(int x=0;x<12;x++)for(int z=0;z<12;z++)l.setBlockAndUpdate(base.offset(x,-1,z),Blocks.STONE.defaultBlockState());
        var a=actor(l,base.offset(3,0,4),NpcProfession.COURIER);var b=actor(l,base.offset(5,0,4),NpcProfession.COURIER);
        a.setNoAi(true);b.setNoAi(true);a.setCommand(NpcCommand.STAY);b.setCommand(NpcCommand.STAY);
        String dim=l.dimension().identifier().toString();
        var town=com.jgeted.sagadyssey.social.settlement.SettlementRegistryData.get(server).create(server,"ration-cycle",com.jgeted.sagadyssey.social.settlement.SettlementType.HAMLET,dim,base,16,"sagadyssey:cycle-test",a.getCharacterId(),l.getGameTime());
        var life=NpcLifeData.get(server);
        for(var n:List.of(a,b)) {life.registerInstitutional(n,dim,town.id(),base,"ration fixture",null,null,NpcWorkShift.FLEXIBLE,l.getGameTime(),true);life.setHunger(n.getCharacterId(),700,l.getGameTime());}
        var storage=base.offset(8,0,5);l.setBlockAndUpdate(storage,Blocks.BARREL.defaultBlockState());
        com.jgeted.sagadyssey.social.logistics.LogisticsData.get(server).registerWarehouse(server,town.id(),"ration-stock",dim,storage,a.getCharacterId(),l.getGameTime());
        box(l,storage).setItem(0,new ItemStack(Items.WHEAT,4));
        int[] phase={0},morale={0};long[] firstDay={-1},firstMeal={-1};
        h.onEachTick(()->{
            var record=com.jgeted.sagadyssey.social.economy.SettlementEconomyData.get(server).record(town.id());
            if(record==null)return;
            if(phase[0]==0) {
                h.assertTrue(record.population()==2&&record.foodRequired()==4&&record.foodConsumed()==4&&count(box(l,storage),Items.WHEAT)==0,"first daily ration consumes four actual wheat for two registered residents");
                h.assertTrue(life.needs(a.getCharacterId()).hunger()<350&&life.needs(b.getCharacterId()).hunger()<350,"both real resident needs satisfied once");
                firstDay[0]=record.processedDay();firstMeal[0]=life.needs(a.getCharacterId()).lastAteAt();morale[0]=a.getMoral();phase[0]=1;
            } else if(phase[0]==1&&record.processedDay()==firstDay[0]) {
                h.assertTrue(life.needs(a.getCharacterId()).lastAteAt()==firstMeal[0],"same daily ration cannot be consumed twice");
            } else if(phase[0]==1) {
                h.assertTrue(record.foodConsumed()==0&&record.shortageDays()==1&&count(box(l,storage),Items.WHEAT)==0,"following empty day records an actual shortfall");
                h.assertTrue(life.needs(a.getCharacterId()).lastAteAt()==firstMeal[0]&&a.getMoral()<morale[0],"empty warehouse never feeds; recorded shortage lowers morale");
                box(l,storage).setItem(0,new ItemStack(Items.WHEAT,4));phase[0]=2;
                Sagadyssey.LOGGER.info("NEAR_CYCLE settlement shortage day={} required={} consumed={}; explicit resupply=4 wheat",record.processedDay(),record.foodRequired(),record.foodConsumed());
            } else if(phase[0]==2&&record.processedDay()==firstDay[0]+1) {
                h.assertTrue(count(box(l,storage),Items.WHEAT)==4&&life.needs(a.getCharacterId()).lastAteAt()==firstMeal[0],"late resupply waits for next scheduled daily settlement");
            } else if(phase[0]==2) {
                h.assertTrue(record.processedDay()==firstDay[0]+2&&record.foodConsumed()==4&&record.shortageDays()==0&&count(box(l,storage),Items.WHEAT)==0,"third daily ration consumes only explicit resupply and clears shortage");
                h.assertTrue(life.needs(a.getCharacterId()).lastAteAt()>firstMeal[0],"recovery is tied to real consumption");
                Sagadyssey.LOGGER.info("NEAR_CYCLE ACCEPT three daily settlements; initial wheat=4, explicit resupply=4, actual consumption=8, remaining=0; final={}",record);h.succeed();phase[0]=3;
            }
        });
    }
    private static void save(GameTestHelper h,Fixture f,CompoundTag totals,int growth) {
        var l=h.getLevel();var server=l.getServer();
        f.farmer().setNoAi(true);f.courier().setNoAi(true);f.courier().setCommand(NpcCommand.STAY);
        NpcBrainRuntime.cancel(f.courier(),PlanCancellationReason.MANUAL,"cycle checkpoint after physical settlement");
        var marker=new CompoundTag();marker.putLong("Base",f.base().asLong());marker.putLong("Crop",f.crop().asLong());marker.putLong("Input",f.input().asLong());
        marker.putLong("Furnace",f.furnace().asLong());marker.putLong("Output",f.output().asLong());marker.putLong("Pantry",f.pantry().asLong());
        marker.store("Farmer", UUIDUtil.CODEC,f.farmer().getUUID());marker.store("Courier", UUIDUtil.CODEC,f.courier().getUUID());marker.store("FarmerPerson", UUIDUtil.CODEC,f.farmer().getCharacterId());
        marker.store("CourierPerson", UUIDUtil.CODEC,f.courier().getCharacterId());marker.store("Household", UUIDUtil.CODEC,f.household());marker.put("Totals",totals);marker.putInt("GrowthCalls",growth);
        marker.putLong("Time",l.getGameTime());marker.putString("MealReceipt",meals(f).getFirst());
        marker.putString("FarmerInventory",com.jgeted.sagadyssey.worldsim.lod.LodFingerprints.inventory(f.farmer()));
        marker.putString("CourierInventory",com.jgeted.sagadyssey.worldsim.lod.LodFingerprints.inventory(f.courier()));
        try{NbtIo.writeCompressed(marker,server.getWorldPath(LevelResource.ROOT).resolve(MARKER));server.saveEverything(true,true,true);}
        catch(Exception e){throw new IllegalStateException("real cycle save",e);}
    }
    private static void recover(GameTestHelper h) {
        h.assertTrue(previous!=null,"independent baseline checkpoint required");var l=h.getLevel();
        h.succeedWhen(()->{
            var farmer=l.getEntity(previous.read("Farmer", UUIDUtil.CODEC).orElseThrow());var courier=l.getEntity(previous.read("Courier", UUIDUtil.CODEC).orElseThrow());
            if(farmer==null||courier==null)java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            h.assertTrue(farmer instanceof NpcBase&&courier instanceof NpcBase,"original entities from actual region save");
            var a=(NpcBase)farmer;var b=(NpcBase)courier;
            h.assertTrue(a.getCharacterId().equals(previous.read("FarmerPerson", UUIDUtil.CODEC).orElseThrow())&&b.getCharacterId().equals(previous.read("CourierPerson", UUIDUtil.CODEC).orElseThrow()),"same original people");
            var f=new Fixture(BlockPos.of(previous.getLongOr("Base", 0L)),BlockPos.of(previous.getLongOr("Crop", 0L)),BlockPos.of(previous.getLongOr("Input", 0L)),BlockPos.of(previous.getLongOr("Furnace", 0L)),BlockPos.of(previous.getLongOr("Output", 0L)),BlockPos.of(previous.getLongOr("Pantry", 0L)),previous.read("Household", UUIDUtil.CODEC).orElseThrow(),a,b,0);
            h.assertTrue(com.jgeted.sagadyssey.worldsim.lod.NpcLodRuntime.loaded(a)&&com.jgeted.sagadyssey.worldsim.lod.NpcLodRuntime.loaded(b),"original LOD identities reconcile");
            h.assertTrue(previous.getStringOr("FarmerInventory", "").equals(com.jgeted.sagadyssey.worldsim.lod.LodFingerprints.inventory(a))&&previous.getStringOr("CourierInventory", "").equals(com.jgeted.sagadyssey.worldsim.lod.LodFingerprints.inventory(b)),"no inventory duplication or constructor gear");
            h.assertTrue("DONE".equals(LifeActivityData.get(l.getServer()).settlement(previous.getStringOr("MealReceipt", ""))),"original completed meal receipt retained");
            // Later batches can advance several days. Hunger may legitimately grow after the saved meal.
            var totals=account(h,f,false);h.assertTrue(totals.equals(previous.getCompoundOrEmpty("Totals")),"unchanged physical and receipt totals across independent process");
            h.assertTrue(NpcLifeData.get(l.getServer()).needs(b.getCharacterId()).lastAteAt()<=previous.getLongOr("Time", 0L),"no invented newer meal during save or restart");
            Sagadyssey.LOGGER.info("NEAR_CYCLE ACCEPT independent JVM same actors, completed meal and totals={}",totals);
        });
    }
}
