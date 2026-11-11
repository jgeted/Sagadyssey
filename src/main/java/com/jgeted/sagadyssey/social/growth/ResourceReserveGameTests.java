package com.jgeted.sagadyssey.social.growth;

import net.minecraft.world.level.gamerules.GameRules;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.household.*;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.social.settlement.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Finite, independently declared scenes; normal NPC plans execute every physical effect. */
@GameTestHolder("sagadyssey_industries_completion") @PrefixGameTestTemplate(false)
public final class ResourceReserveGameTests {
    private static final BlockPos BASE=new BlockPos(6144,80,6144);
    private static String phase(){return System.getProperty("sagadyssey.completion.phase","disabled");}
    @GameTestGenerator public static Collection<TestFunction> tests(){
        return switch(phase()){
            case "resources-stock"->List.of(test("tagged_stock_and_saturation",ResourceReserveGameTests::stock,16000));
            case "resources-resume"->List.of(test("same_world_use_and_replenish",ResourceReserveGameTests::resume,12000));
            case "resources-authority"->List.of(test("tags_title_and_reservations",ResourceReserveGameTests::authority,1000));
            case "resources-masonry"->List.of(test("mined_varieties_into_real_house",ResourceReserveGameTests::masonry,48000));
            default->List.of();};
    }
    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> fn,int limit){return new TestFunction("resource_reserves","resources."+name,"sagadyssey_industries_completion:empty",limit,0,true,h->{
        h.onEachTick(()->{if(h.getLevel().getGameTime()%20==0&&java.nio.file.Files.exists(h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("resource-test-stop-request.json")))h.fail("bounded diagnostic stop requested; preserve world, never count as pass");});
        fn.accept(h);
    });}
    private static BlockPos at(int x,int y,int z){return BASE.offset(x,y,z);}
    private static void sprint(GameTestHelper h,int ticks){try{h.getLevel().getServer().getCommands().getDispatcher().execute("tick sprint "+ticks,h.getLevel().getServer().createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSuppressedOutput());}catch(Exception e){throw new IllegalStateException(e);}}
    private static void force(ServerLevel l){var c=new ChunkPos(BASE);for(int x=c.x-1;x<=c.x+2;x++)for(int z=c.z-1;z<=c.z+2;z++){l.setChunkForced(x,z,true);l.getChunk(x,z);}}
    private static void floor(GameTestHelper h){var l=h.getLevel();force(l);l.setDayTime(1000);l.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
        l.getGameRules().set(GameRules.ADVANCE_TIME, false,l.getServer());l.getGameRules().set(GameRules.SPAWN_MOBS, false,l.getServer());l.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0,l.getServer());
        for(int x=0;x<30;x++)for(int z=0;z<24;z++)l.setBlockAndUpdate(at(x,-1,z),Blocks.STONE.defaultBlockState());}
    private static NpcBase actor(GameTestHelper h){var l=h.getLevel();var s=l.getServer();var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),l);
        n.setPos(at(2,0,14).getX()+.5,80,at(2,0,14).getZ()+.5);n.setPersistenceRequired();n.setProfession(NpcProfession.WORKER);n.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);n.setCommand(NpcCommand.WORK);
        n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();n.getPersonalInventory().setItem(5,new ItemStack(Items.BREAD,8));n.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_AXE));n.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_PICKAXE));n.setWorkZone(at(4,0,4),at(25,0,10));
        l.addFreshEntity(n);NpcLifeData.get(s).ensureRegistered(n,l.getGameTime());NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);
        var site=SettlementRegistryData.get(s).create(s,"finite internal resources",SettlementType.VILLAGE,l.dimension().identifier().toString(),BASE,40,"sagadyssey:kingdom",n.getCharacterId(),l.getGameTime());
        var home=HouseholdData.get(s).create("original resource household",l.dimension().identifier().toString(),site.id(),n.getCharacterId(),BASE,l.getGameTime());
        l.setBlockAndUpdate(at(2,0,20),Blocks.BARREL.defaultBlockState());HouseholdData.get(s).assignSharedStorage(home.id(),l.dimension().identifier().toString(),at(2,0,20),BASE);
        var foot=Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT);l.setBlockAndUpdate(at(4,0,18),foot);l.setBlockAndUpdate(at(4,0,19),foot.setValue(BedBlock.PART,BedPart.HEAD));
        h.assertTrue(NpcLifeData.get(s).assignBed(n.getCharacterId(),l.dimension().identifier().toString(),at(4,0,19),"initial actual bed",l.getGameTime()),"initial bed");
        l.setBlockAndUpdate(at(2,0,14),Blocks.BARREL.defaultBlockState());h.assertTrue(NpcLifeData.get(s).assignPrivateStorage(s,n.getCharacterId(),l.dimension().identifier().toString(),at(2,0,14),l.getGameTime()),"original lawful private output");
        var owner=SubjectRef.character(n.getCharacterId());var id=ContainerIds.npcPersonal(n.getCharacterId());RightsStateData.get(s).ensureContext(id,"npc:"+n.getCharacterId()+":personal",owner,owner,AccessPolicy.PRIVATE);
        var p=point(h,n);SupplyFlowData.get(s).register(p);SupplyFlowData.get(s).reveal(owner,p.containerId());return n;
    }
    private static FieldworkStorage point(GameTestHelper h,NpcBase n){var d=h.getLevel().dimension().identifier().toString();var pos=at(2,0,14);var id=ContainerIds.block("private_storage",d,pos);var owner=SubjectRef.character(n.getCharacterId());
        RightsStateData.get(MinecraftApi.server(n)).ensureContext(id,"resource-stock:"+pos.asLong(),owner,owner,AccessPolicy.PRIVATE);return new FieldworkStorage(d,pos,id,owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"personal_storage");}
    private static Container box(GameTestHelper h){return (Container)h.getLevel().getBlockEntity(at(2,0,14));}
    private static long actions(NpcBase n){return FieldworkData.get(MinecraftApi.server(n)).receiptsFor(n.getUUID()).stream().filter(r->r.action().operation()==FieldworkAction.Operation.MINE||r.action().operation()==FieldworkAction.Operation.CHOP).count();}
    private static Map<String,Integer> counts(NpcBase n){var out=new TreeMap<String,Integer>();for(var r:ResourceReserveRules.rules())out.put(r.id().getPath(),SettlementResourceStock.available(n,r));return out;}
    private static void save(GameTestHelper h,CompoundTag m){try{NbtIo.writeCompressed(m,h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("industry-completion-checkpoint.nbt"));h.getLevel().getServer().saveEverything(true,false,true);}catch(Exception e){throw new IllegalStateException(e);}}
    private static void stock(GameTestHelper h){floor(h);var n=actor(h);var l=h.getLevel();h.assertTrue(Blocks.CALCITE.defaultBlockState().is(net.neoforged.neoforge.common.Tags.Blocks.STONES),"supplemental common tag loaded before business execution");var types=List.of(Blocks.STONE,Blocks.DEEPSLATE,Blocks.GRANITE,Blocks.DIORITE,Blocks.ANDESITE,Blocks.TUFF,Blocks.CALCITE);int i=0;
        for(int j=0;j<42;j++){var p=at(4+i%22,0,4+2*(i/22));l.setBlockAndUpdate(p,types.get(j%7).defaultBlockState());i++;}
        for(int j=0;j<12;j++){var p=at(4+i%22,0,4+2*(i/22));l.setBlockAndUpdate(p,(j<6?Blocks.OAK_LOG:Blocks.SPRUCE_LOG).defaultBlockState());i++;}
        for(int j=0;j<18;j++){var p=at(4+i%22,0,4+2*(i/22));l.setBlockAndUpdate(p,(j<10?Blocks.COAL_ORE:Blocks.IRON_ORE).defaultBlockState());i++;}
        long[] stable={-1},settled={-1};sprint(h,16500);
        h.onEachTick(()->{if(l.getGameTime()%20!=0)return;
            boolean full=ResourceReserveRules.rules().stream().allMatch(r->SettlementResourceStock.available(n,r)>=SettlementResourceStock.target(n,r));
            if(!full){stable[0]=-1;return;}if(stable[0]<0){stable[0]=l.getGameTime();settled[0]=actions(n);}
            if(l.getGameTime()-stable[0]<400)return;
            h.assertTrue(actions(n)==settled[0],"no continued extraction after all bounded reserves are full");
            var kinds=new HashSet<Item>();for(int slot=0;slot<box(h).getContainerSize();slot++)if(SettlementMaterials.masonry(box(h).getItem(slot)))kinds.add(box(h).getItem(slot).getItem());
            h.assertTrue(kinds.size()==7,"seven real masonry drops including datapack-only calcite, not converted to cobblestone");
            h.assertTrue(FieldworkData.get(MinecraftApi.server(n)).pendingCargo(n.getUUID()).isEmpty(),"delivered stock is not still counted as cargo");
            var m=new CompoundTag();m.store("Worker", UUIDUtil.CODEC,n.getUUID());m.store("WorkerPerson", UUIDUtil.CODEC,n.getCharacterId());m.putLong("Actions",actions(n));
            Sagadyssey.LOGGER.info("RESOURCES_STOCK_ACCEPT tick={} person={} counts={} kinds={} actions={} stableTicks=400",l.getGameTime(),n.getCharacterId(),counts(n),kinds.size(),actions(n));save(h,m);h.succeed();});
    }
    private static void resume(GameTestHelper h){force(h.getLevel());final CompoundTag m;try{m=NbtIo.readCompressed(h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("industry-completion-checkpoint.nbt"),NbtAccounter.unlimitedHeap());}catch(Exception e){throw new IllegalStateException(e);}
        var n=(NpcBase)h.getLevel().getEntity(m.read("Worker", UUIDUtil.CODEC).orElseThrow());h.assertTrue(n!=null&&n.getCharacterId().equals(m.read("WorkerPerson", UUIDUtil.CODEC).orElseThrow()),"same saved body and persistent identity");
        var table=at(3,0,14);h.getLevel().setBlockAndUpdate(table,Blocks.CRAFTING_TABLE.defaultBlockState());
        var order=WorkshopTask.requestPlanks(n,"saved-stock-actual-use",point(h,n),table,h.getLevel().getGameTime());long before=actions(n);sprint(h,12500);
        h.onEachTick(()->{var current=ProductionOrderData.get(MinecraftApi.server(n)).byId(order.id()).orElseThrow();if(current.status()!=ProductionOrderStatus.COMPLETED||actions(n)<before+3)return;
            boolean full=ResourceReserveRules.rules().stream().allMatch(r->SettlementResourceStock.available(n,r)>=SettlementResourceStock.target(n,r));if(!full)return;
            h.assertTrue(box(h).countItem(Items.OAK_PLANKS)==12,"three actual accumulated logs used for twelve actual planks");
            h.assertTrue(FieldworkData.get(MinecraftApi.server(n)).pendingCargo(n.getUUID()).isEmpty(),"new logs actually returned to stock");
            Sagadyssey.LOGGER.info("RESOURCES_RESUME_ACCEPT tick={} samePerson={} completedOrder={} beforeActions={} afterActions={} stock={} planks=12",h.getLevel().getGameTime(),n.getCharacterId(),order.id(),before,actions(n),counts(n));save(h,m);h.succeed();});
    }
    private static void authority(GameTestHelper h){floor(h);var n=actor(h);var p=point(h,n);var rights=RightsStateData.get(MinecraftApi.server(n));var c=box(h);var owner=SubjectRef.character(n.getCharacterId());var foreign=SubjectRef.character(UUID.randomUUID());long now=h.getLevel().getGameTime();
        c.setItem(0,new ItemStack(Items.GRANITE,8));c.setItem(1,new ItemStack(Items.GRANITE,6));c.setItem(2,new ItemStack(Items.INFESTED_STONE,4));
        var fp=ItemFingerprint.of(c.getItem(0));rights.putClaim(new AssetClaim(UUID.randomUUID(),p.containerId(),0,fp,8,foreign,owner,Set.of(),Set.of(),"initial private deposit",0));
        h.assertTrue(rights.reserve(new AssetReservation(UUID.randomUUID(),p.containerId(),fp,2,owner,"initial-legitimate-order","existing obligation",now,now+200),6,now),"real initial reservation");
        h.runAfterDelay(20,()->{var rule=ResourceReserveRules.rules().stream().filter(r->r.id().getPath().equals("masonry")).findFirst().orElseThrow();h.assertTrue(SettlementResourceStock.available(n,rule)==4,"foreign private eight and committed two excluded exactly once");
            h.assertTrue(!SettlementMaterials.mineable(Blocks.INFESTED_STONE.defaultBlockState())&&!SettlementMaterials.masonry(new ItemStack(Items.INFESTED_STONE)),"bad public-tag member cannot become safe masonry");
            h.assertTrue(!FieldworkDiscovery.allowed(n,at(27,0,4)),"tag membership does not expand work permission");});
        h.runAfterDelay(240,()->{var rule=ResourceReserveRules.rules().stream().filter(r->r.id().getPath().equals("masonry")).findFirst().orElseThrow();h.assertTrue(SettlementResourceStock.available(n,rule)==6,"expired legitimate reservation is rechecked, private eight remain excluded");
            h.assertTrue(c.getItem(0).getCount()==8&&c.getItem(2).getCount()==4&&actions(n)==0,"no private goods consumed and no unsupported target mined");Sagadyssey.LOGGER.info("RESOURCES_AUTHORITY_ACCEPT freeBefore=4 freeAfter=6 private=8 unchanged=true");h.succeed();});sprint(h,1100);
    }
    private static void masonry(GameTestHelper h){var b=NaturalVillageR1GameTests.fixture(h,0,GrowthLayout.COORDINATED);var l=h.getLevel();boolean[] prepared={false};long[] baseActions={0};sprint(h,48500);
        h.onEachTick(()->{
            var worker=NaturalVillageR1GameTests.actor(l,b,2);if(worker==null)return;
            if(!prepared[0]){
                h.assertTrue(actions(worker)==0,"initial fixture declared before any production effect");
                for(int index=0;index<=3;index++){var c=SettlementCoordinatorData.store(l.getServer(),b,index);if(c==null)continue;for(int slot=0;slot<c.getContainerSize();slot++)if(SettlementMaterials.masonry(c.getItem(slot)))c.setItem(slot,ItemStack.EMPTY);}
                var foods=SettlementCoordinatorData.store(l.getServer(),b,1);foods.setItem(0,new ItemStack(Items.CARROT,64));
                var rocks=List.of(Blocks.STONE,Blocks.DEEPSLATE,Blocks.GRANITE,Blocks.DIORITE,Blocks.ANDESITE,Blocks.TUFF,Blocks.CALCITE);int i=0;
                for(var q:BlockPos.betweenClosed(new BlockPos(21,1,15),new BlockPos(24,3,19)))l.setBlockAndUpdate(NaturalVillageLayout.at(b,q),rocks.get(i++%7).defaultBlockState());
                prepared[0]=true;baseActions[0]=actions(worker);Sagadyssey.LOGGER.info("RESOURCES_MASONRY_INITIAL sourceBlocks=60 householdFood=64 storedMasonry=0 originalPeople=3");return;
            }
            if(l.getGameTime()%100!=0)return;
            var d=SettlementCoordinatorData.existing(l.getServer());var row=d==null?null:d.site(b.buildingKey());if(row==null)return;
            int placed=row.getCompoundOrEmpty("Jobs").getCompoundOrEmpty("HOUSE").getIntOr("Placed", 0);
            if(l.getGameTime()%2000==0)Sagadyssey.LOGGER.info("RESOURCES_MASONRY_PROGRESS tick={} placed={} actions={} household={} public={} workerStore={}",l.getGameTime(),placed,actions(worker),SettlementMaterials.available(l.getServer(),b,1),SettlementMaterials.available(l.getServer(),b,3),SettlementMaterials.available(l.getServer(),b,2));
            if(placed<24)return;
            var types=new HashSet<Block>();for(int j=0;j<Math.min(placed,55);j++)for(var pos:GrowthLayout.house(b).get(j).positions()){var state=l.getBlockState(pos);h.assertTrue(SettlementMaterials.masonry(state),"actual accepted masonry in original blueprint position");types.add(state.getBlock());}
            h.assertTrue(types.size()>=3&&actions(worker)>baseActions[0]+placed,"multiple real mined materials used, no initial masonry stock");
            Sagadyssey.LOGGER.info("RESOURCES_MASONRY_ACCEPT tick={} placed={} differentMaterials={} actualActions={}",l.getGameTime(),placed,types.size(),actions(worker));
            var mark=new CompoundTag();mark.put("Village",NaturalVillageLifeData.get(l.getServer()).village(b.buildingKey()));mark.put("Coordinator",row);save(h,mark);h.succeed();});
    }
}
