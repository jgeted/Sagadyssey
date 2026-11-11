package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.knowledge.*;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.politics.NaturalPolityData;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("sagadyssey_development")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class SettlementDevelopmentGameTests {
    private static final com.jgeted.sagadyssey.worldsim.ChunkTickets.Kind OBSERVER=
            com.jgeted.sagadyssey.worldsim.ChunkTickets.kind("development-observer");
    @net.neoforged.bus.api.SubscribeEvent public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!(e.getServer() instanceof GameTestServer))return;var l=e.getServer().overworld();try{
            if(System.getProperty("sagadyssey.development.phase","").equals("resume")){
                var world=net.minecraft.nbt.NbtIo.readCompressed(e.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("level.dat"),net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                // GameTest intentionally installs fresh world time; restore the full checkpoint's clock before any original entity tick.
                e.getServer().getWorldData().overworldData().setGameTime(world.getCompoundOrEmpty("Data").getLongOr("Time", 0L)+1);
                e.getServer().getWorldData().overworldData().setDayTime(world.getCompoundOrEmpty("Data").getLongOr("DayTime", 0L)+1);
            }
            l.getStructureManager().getOrCreate(net.minecraft.resources.Identifier.parse("sagadyssey_development:empty")).load(
                    l.holderLookup(net.minecraft.core.registries.Registries.BLOCK),net.minecraft.nbt.TagParser.parseCompoundFully("{size:[16,8,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
        }catch(Exception x){throw new IllegalStateException(x);}
    }
    @BeforeBatch(batch="development") public static void setup(ServerLevel l){
        l.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
        l.getGameRules().set(GameRules.SPAWN_MOBS, false,l.getServer());
        l.getGameRules().set(GameRules.ADVANCE_TIME, true,l.getServer());
        l.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 3,l.getServer());
        l.getServer().tickRateManager().requestGameToSprint(240000);
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){
        String phase=System.getProperty("sagadyssey.development.phase","init");
        if(phase.startsWith("formal-")||phase.startsWith("counter-"))return List.of();
        if(phase.equals("init"))return List.of(new TestFunction("development","natural_v7_initialization","sagadyssey_development:empty",1600,0,true,SettlementDevelopmentGameTests::init));
        if(phase.equals("resume"))return List.of(new TestFunction("development","resume_same_people_and_project","sagadyssey_development:empty",240000,0,true,SettlementDevelopmentGameTests::resume));
        return List.of(new TestFunction("development","calibration_two_rounds_original_people","sagadyssey_development:empty",240000,0,true,SettlementDevelopmentGameTests::rounds));
    }
    static int count(Container c,Item type){int total=0;if(c!=null)for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(type))total+=c.getItem(i).getCount();return total;}
    private static void init(GameTestHelper h){
        var b=NaturalVillageR1GameTests.fixture(h,0,SettlementDevelopmentLayout.TEMPLATE);
        h.onEachTick(()->{
            var d=SettlementDevelopmentData.existing(h.getLevel().getServer());if(d==null||d.site(b.buildingKey())==null)return;
            var row=d.site(b.buildingKey());var homes=HouseholdData.get(h.getLevel().getServer());
            h.assertTrue(homes.byId(NaturalVillageLifeData.home(b)).members().size()==3,"exactly original three people");
            h.assertTrue(MinecraftApi.listOfType(row, "Projects",net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty(),"no invented growth or incoming people");
            for(int lot=0;lot<2;lot++)for(var bed:SettlementDevelopmentLayout.beds(b,lot))
                h.assertTrue(h.getLevel().getBlockState(bed).isAir(),"new beds are not prebuilt");
            h.assertTrue(count(NaturalPolityData.inventory(h.getLevel().getServer(),b,3),Items.WHITE_WOOL)==21,"declared finite wool for original and incoming beds");
            Sagadyssey.LOGGER.info("DEVELOPMENT_NATURAL_INIT template={} village={} people=3 newProjects=0 foundingWool=21",b.templateId(),b.buildingKey());h.succeed();
        });
    }
    private static void rounds(GameTestHelper h){
        var target=NaturalVillageR1GameTests.fixture(h,0,SettlementDevelopmentLayout.TEMPLATE);
        var source1=NaturalVillageR1GameTests.fixture(h,256,NaturalVillageLayout.FARM_V3);
        var source2=NaturalVillageR1GameTests.fixture(h,512,NaturalVillageLayout.FARM_V3);
        observe(h,target,source1,source2,true);
    }
    private static void resume(GameTestHelper h){
        var l=h.getLevel();var d=SettlementDevelopmentData.existing(l.getServer());
        h.assertTrue(d!=null&&d.sites().size()==1,"must recover the prior full world, never recreate the fixture");
        var target=NaturalVillageLifeData.decode(d.sites().getFirst().getCompoundOrEmpty("Village"));
        var sources=d.applications().stream().map(a->NaturalVillageLifeData.decode(a.getCompoundOrEmpty("Source"))).toList();
        h.assertTrue(sources.size()==2,"same two pending source households");
        var list=List.of(target,sources.get(0),sources.get(1));
        var ticket=OBSERVER;
        for(var b:list){
            var a=NaturalVillageLayout.at(b,BlockPos.ZERO);var z=NaturalVillageLayout.at(b,new BlockPos(25,0,SettlementDevelopmentLayout.supports(b)?39:21));
            for(int x=(Math.min(a.getX(),z.getX())>>4)-1;x<=(Math.max(a.getX(),z.getX())>>4)+1;x++)
                for(int k=(Math.min(a.getZ(),z.getZ())>>4)-1;k<=(Math.max(a.getZ(),z.getZ())>>4)+1;k++){
                    l.setChunkForced(x,k,true);l.getChunk(x,k);var c=new net.minecraft.world.level.ChunkPos(x,k);
                    com.jgeted.sagadyssey.worldsim.ChunkTickets.add(l.getChunkSource(), ticket,c,2,c,true);
                }
        }
        Sagadyssey.LOGGER.info("DEVELOPMENT_DIAGNOSTIC_RESUME sameWorld=true originGameTime={} priorProjects={} peopleNotRecreated=true trueUnloadClaimed=false",l.getGameTime(),d.sites().getFirst().get("Projects"));
        observe(h,target,sources.get(0),sources.get(1),false);
    }
    private static void observe(GameTestHelper h,NaturalVillageBuilding target,NaturalVillageBuilding source1,NaturalVillageBuilding source2,boolean configure){
        var villages=List.of(target,source1,source2);var l=h.getLevel();long[] started={-1};var ids=new HashSet<UUID>();
        h.onEachTick(()->{
            var s=l.getServer();var d=SettlementDevelopmentData.existing(s);if(d==null||d.site(target.buildingKey())==null)return;
            if(java.nio.file.Files.exists(s.getServerDirectory().resolve("stop-calibration.flag"))){h.fail("校准由操作者停止，保留失败与完整世界，不计通过");return;}
            var natural=NaturalVillageLifeData.existing(s);if(villages.stream().anyMatch(b->{var r=natural.village(b.buildingKey());return r==null||!r.getBooleanOr("Ready", false);}))return;
            long now=l.getGameTime();
            if(started[0]<0){
                started[0]=now;
                observeRoute(l,villages);
                if(configure){
                    configureInitial(l,target,source1,source2);
                }
                for(var b:villages)for(int i=0;i<3;i++)ids.add(NaturalVillageLifeData.person(b,i));
                h.assertTrue(ids.size()==9,"nine original identities, three distinct households");
                if(configure)Sagadyssey.LOGGER.info("DEVELOPMENT_INITIAL controlled=true people=9 targetFood=64 publicStone=165 publicWool=21 sourcePantries=64each sourceBeds=1each sourceFields=blocked actualMigrationNotPrecompleted=true target={} source1={} source2={}",target.buildingKey(),source1.buildingKey(),source2.buildingKey());return;
            }
            observeProgress(h,target,source1,source2,started[0],ids);
        });
    }
    static void configureInitial(ServerLevel l,NaturalVillageBuilding target,NaturalVillageBuilding source1,NaturalVillageBuilding source2){
                var s=l.getServer();long now=l.getGameTime();
                // Declared controlled initial conditions. No inventory changes occur after this setup block.
                NaturalPolityData.inventory(s,target,1).setItem(0,new ItemStack(Items.CARROT,64));
                NaturalPolityData.inventory(s,target,3).setItem(1,new ItemStack(Items.COBBLESTONE,64));
                NaturalPolityData.inventory(s,target,3).setItem(2,new ItemStack(Items.COBBLESTONE,64));
                NaturalPolityData.inventory(s,target,3).setItem(3,new ItemStack(Items.COBBLESTONE,37));
                for(var from:List.of(source1,source2)){
                    var pantry=NaturalPolityData.inventory(s,from,1);pantry.clearContent();pantry.setItem(0,new ItemStack(Items.CARROT,64));
                    // Declared initial housing shortfall. No inventory edits follow the setup boundary.
                    for(int bedIndex=1;bedIndex<3;bedIndex++){
                        var head=com.jgeted.sagadyssey.social.life.NpcResidenceFixtures.normalizeBedHead(l,NaturalVillageLayout.at(from,NaturalVillageLayout.BEDS.get(bedIndex)));
                        var state=l.getBlockState(head);var foot=head.relative(state.getValue(net.minecraft.world.level.block.BedBlock.FACING).getOpposite());
                        l.setBlock(head,Blocks.AIR.defaultBlockState(),net.minecraft.world.level.block.Block.UPDATE_CLIENTS|net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
                        l.setBlock(foot,Blocks.AIR.defaultBlockState(),net.minecraft.world.level.block.Block.UPDATE_CLIENTS|net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
                    }
                    for(var field:NaturalVillageLayout.crops())l.setBlockAndUpdate(NaturalVillageLayout.at(from,field),Blocks.GLASS.defaultBlockState());
                    var farmer=NaturalVillageR1GameTests.actor(l,from,0);farmer.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_HOE));
                    KnowledgeStateData.get(s).selfReport(NaturalVillageLifeData.person(from,1),NaturalVillageLifeData.person(target,1),
                            new FactKey(target.settlementId(),"housing_work_opportunity","v7"),target.buildingKey(),PrecisionBand.EXACT,1,now,72000,DisclosurePolicy.SHAREABLE);
                }
    }
    static void observeProgress(GameTestHelper h,NaturalVillageBuilding target,NaturalVillageBuilding source1,NaturalVillageBuilding source2,long started,Set<UUID> ids){
            var l=h.getLevel();var s=l.getServer();long now=l.getGameTime();var d=SettlementDevelopmentData.existing(s);var villages=List.of(target,source1,source2);
            if((now-started)%2000==0){
                var r=d.site(target.buildingKey());
                Sagadyssey.LOGGER.info("DEVELOPMENT_PROGRESS age={} rounds={} projects={} applications={}",now-started,r.getIntOr("CompletedRounds", 0),r.get("Projects"),d.applications());
                for(var b:villages)for(int i=0;i<3;i++){
                    var n=NaturalVillageR1GameTests.actor(l,b,i);if(n!=null)Sagadyssey.LOGGER.info("DEVELOPMENT_PERSON id={} pos={} activity={} plan={}",n.getCharacterId(),n.blockPosition(),n.getWorkActivityDetail(),NpcBrainRuntime.data(s).active(n.getUUID()));
                    if(n!=null){
                        var home=HouseholdData.get(s).forMember(n.getCharacterId());var record=NpcLifeData.get(s).record(n.getCharacterId());var need=NpcLifeData.get(s).needs(n.getCharacterId());
                        Sagadyssey.LOGGER.info("DEVELOPMENT_DIAGNOSTIC id={} food={} hunger={} fatigue={} navigation={} cargo={} bag={}",
                                n.getCharacterId(),home==null?"no home":SettlementDevelopmentData.capacity(s,home),
                                need==null?-1:need.hunger(),record==null?-1:record.fatigue(),SettlementSurveyNavigation.status(n),
                                FieldworkData.get(s).pendingCargo(n.getUUID()),NaturalVillageEconomyItems(n));
                    }
                }
            }
            for(var person:ids)h.assertTrue(CharacterRegistryData.get(s).byId(person)!=null&&CharacterRegistryData.get(s).byId(person).alive(),"original person retained");
            if(d.site(target.buildingKey()).getIntOr("CompletedRounds", 0)<2)return;
            var homes=HouseholdData.get(s).households().stream().filter(home->target.settlementId().equals(home.settlementId())).toList();
            h.assertTrue(homes.size()==3&&homes.stream().mapToInt(home->home.members().size()).sum()==9,"three actual households in destination");
            h.assertTrue(homes.stream().allMatch(home->SettlementDevelopmentData.capacity(s,home).stableFloor()),"per-home stable food and beds");
            for(var from:List.of(source1,source2)){
                var app=d.application(NaturalVillageLifeData.home(from));h.assertTrue(app.getStringOr("State", "").equals("STABLE"),"original application completed normally");
                h.assertTrue(app.getLongOr("Departed", 0L)>=app.getLongOr("Applied", 0L)&&app.getLongOr("Arrived", 0L)>=app.getLongOr("Departed", 0L),"real application departure and arrival timeline");
            }
            Sagadyssey.LOGGER.info("DEVELOPMENT_TWO_ROUNDS_ACCEPT age={} targetPeople=9 distinctOriginalPeople={} worldsReset=false",now-started,ids.size());h.succeed();
    }
    private static net.minecraft.nbt.ListTag NaturalVillageEconomyItems(NpcBase n){
        return com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData.items(n.getPersonalInventory(),MinecraftApi.server(n).registryAccess());
    }
    /** Declared loaded bounded corridor, no teleport or demand/identity manipulation. */
    static void observeRoute(ServerLevel l,List<NaturalVillageBuilding> villages){
        var corners=new ArrayList<BlockPos>();
        for(var b:villages)for(int x:new int[]{0,25})for(int z:new int[]{0,SettlementDevelopmentLayout.supports(b)?39:21})
            corners.add(NaturalVillageLayout.at(b,new BlockPos(x,0,z)));
        int minX=corners.stream().mapToInt(BlockPos::getX).min().orElseThrow()-2,maxX=corners.stream().mapToInt(BlockPos::getX).max().orElseThrow()+2;
        int minZ=corners.stream().mapToInt(BlockPos::getZ).min().orElseThrow()-2,maxZ=corners.stream().mapToInt(BlockPos::getZ).max().orElseThrow()+2;
        int count=0;for(int x=minX>>4;x<=maxX>>4;x++)for(int z=minZ>>4;z<=maxZ>>4;z++){
            var c=new net.minecraft.world.level.ChunkPos(x,z);l.setChunkForced(x,z,true);l.getChunk(x,z);com.jgeted.sagadyssey.worldsim.ChunkTickets.add(l.getChunkSource(), OBSERVER,c,2,c,true);count++;
        }
        Sagadyssey.LOGGER.info("DEVELOPMENT_LOADED_CORRIDOR minX={} maxX={} minZ={} maxZ={} chunks={} testConfiguration=true unloadClaimed=false",minX,maxX,minZ,maxZ,count);
    }
    static void releaseObservation(ServerLevel l,List<NaturalVillageBuilding> villages){
        // Capture before visiting(false): it removes forced flags but not our own observer tickets.
        var observed=l.getForceLoadedChunks().toLongArray();
        for(var b:villages)NaturalVillageR1GameTests.visiting(l,b,false);
        for(long value:observed){
            var c=new net.minecraft.world.level.ChunkPos(value);l.setChunkForced(c.x,c.z,false);
            com.jgeted.sagadyssey.worldsim.ChunkTickets.remove(l.getChunkSource(), OBSERVER,c,2,c,true);
        }
        // Vanilla setChunkForced(false) calls setDirty(false) on a no-op. Bulk overlapping
        // fixture removals must not leave a stale on-disk Forced set after a true unload.
        var saved=l.getDataStorage().get(net.minecraft.world.level.TicketStorage.TYPE);
        if(saved!=null)saved.setDirty();
    }
}
