package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.life.*;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.settlement.SettlementMemberCommand;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("sagadyssey_society_r1") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class NaturalVillageR1GameTests {
    private static final String MARKER="society-r1-running.nbt";
    private static CompoundTag previous;
    private static final ResourceLocation EMPTY=ResourceLocation.parse("sagadyssey_society_r1:empty");
    private static final TicketType<ChunkPos> TICK=TicketType.create("society-r1-crops",Comparator.comparingLong(ChunkPos::toLong));
    @net.neoforged.bus.api.SubscribeEvent public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!(e.getServer() instanceof GameTestServer))return;var l=e.getServer().overworld();try{
            l.getStructureManager().getOrCreate(EMPTY).load(l.holderLookup(Registries.BLOCK),TagParser.parseTag("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
        }catch(Exception x){throw new IllegalStateException(x);}
        previous=null;if(!System.getProperty("sagadyssey.society.phase","").equals("r1-restart"))return;
        try{var server=e.getServer();previous=NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve(MARKER),NbtAccounter.unlimitedHeap());
            var world=NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap());
            server.getWorldData().overworldData().setGameTime(Math.max(previous.getLong("Time"),world.getCompound("Data").getLong("Time"))+1);
            server.getWorldData().overworldData().setDayTime(world.getCompound("Data").getLong("DayTime")+1);
            var village=NaturalVillageLifeData.decode(previous.getCompound("Village"));
            var origin=village.buildingOrigin();for(int x=(origin.getX()>>4)-3;x<=(origin.getX()>>4)+3;x++)for(int z=(origin.getZ()>>4)-3;z<=(origin.getZ()>>4)+3;z++){
                l.setChunkForced(x,z,true);l.getChunk(x,z);var c=new ChunkPos(x,z);l.getChunkSource().addRegionTicket(TICK,c,2,c,true);
            }
        }catch(Exception x){throw new IllegalStateException("Original R1 world checkpoint required",x);}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){return switch(System.getProperty("sagadyssey.society.phase","r1-init")){
        case "r1-init"->List.of(test("natural_life_initialization",NaturalVillageR1GameTests::initialize,600),test("missing_bed_waits_then_resumes",NaturalVillageR1GameTests::missing,800),test("ordinary_member_actual_delivery",NaturalVillageR1GameTests::member,600));
        case "r1-access"->List.of(test("ordinary_member_cannot_bypass_storage_rights_with_vanilla_interaction",NaturalVillageR1GameTests::access,800));
        case "r1-cycle"->List.of(test("three_natural_growth_cycles_and_initial_reserve_exhaustion",NaturalVillageR1GameTests::cycle,240000));
        case "r1-restart"->List.of(test("original_people_continue_after_disk_restart",NaturalVillageR1GameTests::restart,48000));
        case "r1-shortage"->NaturalVillageShortageGameTests.tests();
        default->List.of();};}
    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> f,int limit){return new TestFunction("society_r1",name,EMPTY.toString(),limit,0,true,f);}
    public static NaturalVillageBuilding fixture(GameTestHelper h,int offset){return fixture(h,offset,NaturalVillageLayout.LIVING);}
    public static NaturalVillageBuilding fixture(GameTestHelper h,int offset,ResourceLocation requested){
        var l=h.getLevel();var gen=l.getChunkSource().getGenerator();var structure=l.registryAccess().registryOrThrow(Registries.STRUCTURE).get(NaturalVillageBuilding.SUPPORTED);
        var cp=new ChunkPos(h.absolutePos(new BlockPos(2048+offset,0,2048)));
        String developmentPhase=System.getProperty("sagadyssey.development.phase","");
        if(developmentPhase.startsWith("formal-")||developmentPhase.startsWith("counter-"))
            cp=new ChunkPos(new BlockPos(-11475920+offset,0,-41648));
        StructureStart start=null;
        for(int attempt=0;attempt<128;attempt++){
            var candidate=structure.generate(l.registryAccess(),gen,gen.getBiomeSource(),l.getChunkSource().randomState(),l.getStructureManager(),7019L+attempt,cp,0,l,biome->true);
            if(candidate.isValid()&&template(l,candidate).equals(requested)){start=candidate;break;}
            // The production pool now uses v3. Legacy regressions explicitly place the
            // retained v2 asset at the generated farm footprint; they are not v3 natural evidence.
            boolean retainedLegacy = (requested.equals(NaturalVillageLayout.FARM_V2)||requested.equals(NaturalVillageLayout.FARM_V3))
                    &&(template(l,candidate).equals(NaturalVillageLayout.FARM_V3)||template(l,candidate).equals(NaturalVillageLayout.HOMESTEAD_V4)
                    ||template(l,candidate).equals(com.jgeted.sagadyssey.social.growth.GrowthLayout.COORDINATED)
                    ||template(l,candidate).equals(com.jgeted.sagadyssey.social.growth.SettlementDevelopmentLayout.TEMPLATE));
            boolean retainedGrowth = requested.equals(com.jgeted.sagadyssey.social.growth.GrowthLayout.TEMPLATE)
                    && template(l,candidate).equals(com.jgeted.sagadyssey.social.growth.GrowthLayout.COORDINATED);
            if(candidate.isValid()&&(retainedLegacy || retainedGrowth)){
                var piece=(PoolElementStructurePiece)candidate.getPieces().getFirst();
                var context=l.registryAccess().createSerializationContext(NbtOps.INSTANCE);
                var encoded=(CompoundTag)net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement.CODEC.codec().encodeStart(context,(net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement)piece.getElement()).getOrThrow();
                encoded.putString("location",requested.toString());
                var element=net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement.CODEC.codec().parse(context,encoded).getOrThrow();
                var retained=new PoolElementStructurePiece(l.getStructureManager(),element,piece.getPosition(),piece.getGroundLevelDelta(),piece.getRotation(),element.getBoundingBox(l.getStructureManager(),piece.getPosition(),piece.getRotation()),net.minecraft.world.level.levelgen.structure.structures.JigsawStructure.DEFAULT_LIQUID_SETTINGS);
                start=new StructureStart(structure,cp,0,new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(List.of(retained)));
                Sagadyssey.LOGGER.info("LEGACY_FARM_FIXTURE retainedTemplate={} generatedFootprint={} testOnly=true",requested,piece.getPosition());break;
            }
        }
        h.assertTrue(start!=null&&start.isValid(),"actual seeded pool supports the requested versioned fixture");
        var p=(PoolElementStructurePiece)start.getPieces().getFirst();var anchor=cp.getWorldPosition();
        var b=new NaturalVillageBuilding(l.dimension().location().toString(),NaturalVillageBuilding.SUPPORTED.toString(),anchor,
                new BlockPos(anchor.getX()+8,p.getBoundingBox().minY(),anchor.getZ()+8),requested.toString(),p.getPosition(),p.getRotation());
        var box=start.getBoundingBox();
        for(int x=(box.minX()>>4)-1;x<=(box.maxX()>>4)+1;x++)for(int z=(box.minZ()>>4)-1;z<=(box.maxZ()>>4)+1;z++){
            l.setChunkForced(x,z,true);l.getChunk(x,z);var chunk=new ChunkPos(x,z);l.getChunkSource().addRegionTicket(TICK,chunk,2,chunk,true);
        }
        for(int x=box.minX()>>4;x<=box.maxX()>>4;x++)for(int z=box.minZ()>>4;z<=box.maxZ()>>4;z++){
            var c=new ChunkPos(x,z);start.placeInChunk(l,l.structureManager(),gen,RandomSource.create(44),new BoundingBox(c.getMinBlockX(),l.getMinBuildHeight(),c.getMinBlockZ(),c.getMaxBlockX(),l.getMaxBuildHeight()-1,c.getMaxBlockZ()),c);
        }
        l.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(3,l.getServer());l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true,l.getServer());
        l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,l.getServer());return b;
    }
    public static ResourceLocation template(ServerLevel l,StructureStart start){
        var p=(PoolElementStructurePiece)start.getPieces().getFirst();var element=(net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement)p.getElement();
        var tag=(CompoundTag)net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement.CODEC.codec().encodeStart(l.registryAccess().createSerializationContext(NbtOps.INSTANCE),element).getOrThrow();return ResourceLocation.parse(tag.getString("location"));
    }
    public static void visiting(ServerLevel l,NaturalVillageBuilding b,boolean visit){
        var a=NaturalVillageLayout.at(b,BlockPos.ZERO);var end=NaturalVillageLayout.at(b,new BlockPos(25,0,com.jgeted.sagadyssey.social.growth.SettlementDevelopmentLayout.supports(b)?39:21));
        for(int x=((Math.min(a.getX(),end.getX())-12)>>4)-1;x<=((Math.max(a.getX(),end.getX())+12)>>4)+1;x++)for(int z=((Math.min(a.getZ(),end.getZ())-12)>>4)-1;z<=((Math.max(a.getZ(),end.getZ())+12)>>4)+1;z++){
            var c=new ChunkPos(x,z);l.setChunkForced(x,z,visit);if(visit){l.getChunk(x,z);l.getChunkSource().addRegionTicket(TICK,c,2,c,true);}else l.getChunkSource().removeRegionTicket(TICK,c,2,c,true);
        }
    }
    public static NpcBase actor(ServerLevel l,NaturalVillageBuilding b,int i){return (NpcBase)l.getEntity(NaturalVillageLifeData.body(b,i));}
    private static void ready(GameTestHelper h,NaturalVillageBuilding b){var d=NaturalVillageLifeData.get(h.getLevel().getServer()).village(b.buildingKey());
        h.assertTrue(d!=null&&d.getBoolean("Ready"),"natural life ready: "+(d==null?"missing intent":d.getString("Reason")));}
    private static void initialize(GameTestHelper h){var b=fixture(h,0);h.runAfterDelay(240,()->{
        ready(h,b);var l=h.getLevel();
        var farmer=actor(l,b,0);var crop=NaturalVillageLayout.at(b,NaturalVillageLayout.crops().getFirst());
        l.setBlockAndUpdate(crop,Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE,7));
        h.assertTrue(!new com.jgeted.sagadyssey.npc.ai.FarmerWorkGoal(farmer).canUse(),"planned resident cannot run unreceipted legacy harvest");
        h.assertTrue(!FieldworkDiscovery.allowed(farmer,NaturalVillageLayout.at(b,new BlockPos(15,1,4))),"growth-support soil cannot silently expand the twelve registered crop positions");
        h.assertTrue(!new com.jgeted.sagadyssey.npc.ai.WorkerWorkGoal(actor(l,b,2)).canUse(),"planned resident cannot run legacy extraction");
        h.assertTrue(!new com.jgeted.sagadyssey.npc.ai.NpcHouseholdCareGoal(farmer).canUse(),"planned resident cannot run legacy food movement");
        var house=HouseholdData.get(l.getServer()).byId(NaturalVillageLifeData.home(b));
        h.assertTrue(house!=null&&house.members().size()==3,"one real household and three original people");
        for(int i=0;i<3;i++){var n=actor(l,b,i);h.assertTrue(n!=null&&NpcBrainRuntime.isPlanned(n),"autonomous resident mode");h.assertTrue(NpcLifeData.get(l.getServer()).record(n.getCharacterId()).bedPosition()!=null,"assigned real bed");}
        NaturalVillageLifeData.get(l.getServer()).schedule(b);
        h.assertTrue(HouseholdData.get(l.getServer()).byId(NaturalVillageLifeData.home(b)).members().size()==3,"repeated placement does not duplicate family");h.succeed();});}
    private static void missing(GameTestHelper h){var b=fixture(h,256);var l=h.getLevel();var p=NaturalVillageLayout.at(b,NaturalVillageLayout.BEDS.getFirst());var bed=l.getBlockState(p);l.setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());
        h.runAfterDelay(200,()->{var v=NaturalVillageLifeData.get(l.getServer()).village(b.buildingKey());h.assertTrue(!v.getBoolean("Ready")&&actor(l,b,0)==null,"missing prerequisite does not invent residents or work");l.setBlockAndUpdate(p,bed);});
        h.runAfterDelay(440,()->{ready(h,b);h.succeed();});}
    private static int dispatch(ServerPlayer p,String command){try{return p.server.getCommands().getDispatcher().execute(command,p.createCommandSourceStack().withPermission(0).withSuppressedOutput());}catch(Exception e){throw new IllegalStateException(e);}}
    private static int count(Container c,Item item){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n;}
    private static void member(GameTestHelper h){var b=fixture(h,512);h.runAfterDelay(240,()->{
        ready(h,b);var l=h.getLevel();var p=net.neoforged.neoforge.common.util.FakePlayerFactory.get(l,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"SocietyMember"));
        var loc=NaturalVillageLayout.at(b,NaturalVillageLayout.SPAWNS.get(1));p.setPos(loc.getX()+.5,loc.getY(),loc.getZ()+.5);p.getInventory().clearContent();p.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,4));
        var pantry=(Container)l.getBlockEntity(NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY));pantry.setItem(0,new ItemStack(Items.CARROT,2));
        h.assertTrue(dispatch(p,"saga society deliver")==0,"nonmember cannot obtain household payment");
        h.assertTrue(dispatch(p,"saga society join")==1&&dispatch(p,"saga society deliver")==1,"ordinary command path settles real work and payment");
        h.assertTrue(count(p.getInventory(),Items.OAK_LOG)==0&&count(p.getInventory(),Items.CARROT)==2&&count(pantry,Items.CARROT)==0,"actual resources exchange atomically");
        h.assertTrue(dispatch(p,"saga society deliver")==0,"repeated request cannot claim twice");
        h.assertTrue(dispatch(p,"saga society leave")==1,"ordinary member leaves");
        var provision=HouseholdData.get(l.getServer()).provisionPlan(NaturalVillageLifeData.home(b));
        h.assertTrue(provision.foodItemsTarget()==0&&provision.fuelItemsTarget()==0&&provision.workingToolsTarget()==0,"leaving preserves the declared direct-food diet");
        h.assertTrue(dispatch(p,"saga society join")==1&&dispatch(p,"saga society deliver")==0,"leave/rejoin cannot replay reward");
        p.getFoodData().setFoodLevel(10);for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(Items.CARROT)){p.getInventory().getItem(i).finishUsingItem(l,p);break;}
        h.assertTrue(count(p.getInventory(),Items.CARROT)==1&&p.getFoodData().getFoodLevel()>10,"earned food supports vanilla consumption");h.succeed();});}
    private static void access(GameTestHelper h){var b=fixture(h,0);h.runAfterDelay(300,()->{
        ready(h,b);var l=h.getLevel();var p=net.neoforged.neoforge.common.util.FakePlayerFactory.get(l,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"AccessMember"));var at=NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY);p.setPos(at.getX(),at.getY(),at.getZ());h.assertTrue(dispatch(p,"saga society join")==1,"ordinary membership");
        for(var relative:List.of(NaturalVillageLayout.RAW,NaturalVillageLayout.PANTRY,NaturalVillageLayout.WOOD,NaturalVillageLayout.PUBLIC)){
            var point=NaturalVillageLayout.at(b,relative);var hit=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(point),Direction.UP,point,false);
            var event=new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(p,net.minecraft.world.InteractionHand.MAIN_HAND,point,hit);net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);h.assertTrue(event.isCanceled(),"membership does not grant unrestricted vanilla inventory transfer");
            var breaking=new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(l,point,l.getBlockState(point),p);net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(breaking);h.assertTrue(breaking.isCanceled(),"ordinary member cannot destroy managed assets");
        }
        h.succeed();
    });}
    public static void observe(ServerLevel l,NaturalVillageBuilding b,Set<String> meals){for(int i=0;i<3;i++){var a=actor(l,b,i);if(a==null)continue;var plans=new ArrayList<>(NpcBrainRuntime.data(l.getServer()).history(a.getUUID()));NpcBrainRuntime.data(l.getServer()).active(a.getUUID()).ifPresent(plans::add);for(var p:plans)
        if(p.providerId().equals(LifePlanFactory.PROVIDER)&&Set.of("EAT","CARE").contains(p.steps().getFirst().params().get("operation"))&&"DONE".equals(LifeActivityData.get(l.getServer()).settlement(p.id()+":2")))meals.add(a.getUUID()+"/"+p.id());}}
    private static int stocks(ServerLevel l,NaturalVillageBuilding b){int n=0;for(var p:List.of(NaturalVillageLayout.RAW,NaturalVillageLayout.PANTRY,NaturalVillageLayout.WOOD,NaturalVillageLayout.PUBLIC))n+=count((Container)l.getBlockEntity(NaturalVillageLayout.at(b,p)),Items.CARROT);
        for(int i=0;i<3;i++){var a=actor(l,b,i);if(a!=null)n+=count(a.getPersonalInventory(),Items.CARROT)+count(a.getTransportInventory(),Items.CARROT);}return n;}
    private static void cycle(GameTestHelper h){var b=fixture(h,0);var l=h.getLevel();long began=l.getGameTime();Set<String> meals=new HashSet<>();
        h.onEachTick(()->{long elapsed=l.getGameTime()-began;if(elapsed<240||elapsed%20!=0)return;ready(h,b);observe(l,b,meals);var farmer=actor(l,b,0);
            var receipts=FieldworkData.get(l.getServer()).receiptsFor(farmer.getUUID());int delta=receipts.stream().flatMap(r->r.inventoryDeltas().stream()).filter(d->d.fingerprint().equals("minecraft:carrot#0")).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();
            h.assertTrue(16+delta==stocks(l,b)+meals.size(),"carrot balance: initial=16 netCrop="+delta+" stock="+stocks(l,b)+" meals="+meals.size()+" elapsed="+elapsed);
            long repeated=receipts.stream().filter(r->r.action().operation()==FieldworkAction.Operation.HARVEST).collect(java.util.stream.Collectors.groupingBy(r->r.action().target(),java.util.stream.Collectors.counting())).values().stream().mapToLong(Long::longValue).max().orElse(0);
            if(elapsed%12000==0)Sagadyssey.LOGGER.info("R1_CYCLE ticks={} stock={} netCrop={} meals={} repeatedHarvestMax={} farmer={} courier={}",elapsed,stocks(l,b),delta,meals.size(),repeated,farmer.getWorkActivityDetail(),actor(l,b,1).getWorkActivityDetail());
            if(elapsed<168000)return;
            if(repeated<4||meals.size()<20)return;
            for(int i=0;i<3;i++){var a=actor(l,b,i);if(NpcLifeData.get(l.getServer()).needs(a.getCharacterId()).hunger()>=800)return;}
            Sagadyssey.LOGGER.info("R1_ACCEPT ticks={} initialSeed=16 stock={} netCrop={} meals={} repeatedHarvests={}",elapsed,stocks(l,b),delta,meals.size(),repeated);Sagadyssey.LOGGER.info("R1_PERFORMANCE {}",com.jgeted.sagadyssey.worldsim.lod.LodMetrics.snapshot(l.getServer(),com.jgeted.sagadyssey.worldsim.lod.LodMetrics.Kind.SERVER_TICK));saveRunning(h,b,meals,delta);h.succeed();});}
    private static void saveRunning(GameTestHelper h,NaturalVillageBuilding b,Set<String> meals,int delta){
        var server=h.getLevel().getServer();var marker=new CompoundTag();marker.putLong("Time",h.getLevel().getGameTime());
        marker.put("Village",NaturalVillageLifeData.get(server).village(b.buildingKey()).copy());marker.putInt("Delta",delta);marker.putInt("Meals",meals.size());
        var keys=new ListTag();meals.forEach(k->keys.add(StringTag.valueOf(k)));marker.put("MealKeys",keys);
        try{NbtIo.writeCompressed(marker,server.getWorldPath(LevelResource.ROOT).resolve(MARKER));server.saveEverything(true,true,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();}
        catch(Exception e){throw new IllegalStateException("Save running natural household",e);}
    }
    private static void restart(GameTestHelper h){
        h.assertTrue(previous!=null,"running checkpoint exists");var b=NaturalVillageLifeData.decode(previous.getCompound("Village"));var l=h.getLevel();Set<String> meals=new HashSet<>();
        for(var key:previous.getList("MealKeys",Tag.TAG_STRING))meals.add(key.getAsString());
        h.succeedWhen(()->{ready(h,b);for(int i=0;i<3;i++){var n=actor(l,b,i);h.assertTrue(n!=null&&n.getCharacterId().equals(NaturalVillageLifeData.person(b,i)),"same people and bodies survive restart");}
            observe(l,b,meals);var farmer=actor(l,b,0);int delta=FieldworkData.get(l.getServer()).receiptsFor(farmer.getUUID()).stream().flatMap(r->r.inventoryDeltas().stream()).filter(d->d.fingerprint().equals("minecraft:carrot#0")).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();
            h.assertTrue(16+delta==stocks(l,b)+meals.size(),"restart exact carrot balance: net="+delta+" stock="+stocks(l,b)+" meals="+meals.size());
            h.assertTrue(delta>previous.getInt("Delta")&&meals.size()>previous.getInt("Meals"),"original autonomous chain produces and consumes after restart");
            Sagadyssey.LOGGER.info("R1_RESTART_ACCEPT netBefore={} netNow={} mealsBefore={} mealsNow={}",previous.getInt("Delta"),delta,previous.getInt("Meals"),meals.size());
        });
    }
}
