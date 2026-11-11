package com.jgeted.sagadyssey.social.industry.food;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.config.SagadysseyConfig;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.social.growth.SettlementDevelopmentLayout;
import com.jgeted.sagadyssey.social.industry.common.*;
import com.jgeted.sagadyssey.social.life.NpcFoodItems;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.ProductionContainerIds;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.neoforged.neoforge.gametest.*;

import java.util.*;

/** Controlled initial shortage; no product intent or fulfillment is injected after initialization. */
@GameTestHolder("sagadyssey_playability_fix")
@PrefixGameTestTemplate(false)
public final class NaturalFoodGameTests {
    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        if (!System.getProperty("sagadyssey.playability.phase","").equals("food-fishing-loop")) return List.of();
        return List.of(new TestFunction("playability_fix","food-fishing-loop","sagadyssey_playability_fix:empty",
                11200,0,true,NaturalFoodGameTests::fishingMeals));
    }

    private static void fishingMeals(GameTestHelper h) {
        var level=h.getLevel();var server=level.getServer();
        var village=NaturalVillageR1GameTests.fixture(h,0,SettlementDevelopmentLayout.INDUSTRY_TEMPLATE);
        level.setDayTime(6000);level.getGameRules().set(GameRules.ADVANCE_TIME, false,server);
        BlockPos water=NaturalVillageLayout.at(village,new BlockPos(17,0,18));
        BlockPos shore=NaturalVillageLayout.at(village,new BlockPos(16,1,18));
        level.setBlockAndUpdate(water.below(2),Blocks.STONE.defaultBlockState());
        for(var side:Direction.Plane.HORIZONTAL){
            level.setBlockAndUpdate(water.relative(side),Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(water.relative(side).below(),Blocks.STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(shore,Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(shore.above(),Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(water,Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(water.below(),Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(water.above(),Blocks.AIR.defaultBlockState());
        var river=level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.RIVER);
        for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){
            var at=water.offset(x*4,y*4,z*4);if(level.isOutsideBuildHeight(at))continue;
            var biomes=(PalettedContainer<Holder<Biome>>)level.getChunk(at).getSection(level.getSectionIndex(at.getY())).getBiomes();
            biomes.set((at.getX()&15)>>2,(at.getY()&15)>>2,(at.getZ()&15)>>2,river);
        }
        long[] start={-1},lastMeal={-1,-1,-1};int[] meals={0};
        UUID[] identities=new UUID[3];
        server.tickRateManager().requestGameToSprint(11400);
        h.onEachTick(()->{
            if(level.getGameTime()%20==0&&java.nio.file.Files.exists(server.getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT).resolve("industry-stop-request.json")))
                h.fail("bounded wall-clock stop; preserve original world");
            var worker=NaturalVillageR1GameTests.actor(level,village,2);
            var farmer=NaturalVillageR1GameTests.actor(level,village,0);
            var courier=NaturalVillageR1GameTests.actor(level,village,1);
            var data=NaturalVillageLifeData.get(server).village(village.buildingKey());
            if(worker==null||farmer==null||courier==null||data==null||!data.getBooleanOr("Ready", false)){
                h.assertTrue(h.getTick()<1200,"initialization budget 1200 ticks");return;
            }
            var life=NpcLifeData.get(server);long now=level.getGameTime();
            var flows=SupplyFlowData.get(server);
            var output=flows.pointOrNull(ProductionContainerIds.resolve(server,village.dimension(),NaturalVillageLayout.at(village,NaturalVillageLayout.WOOD)));
            if(start[0]<0){
                if(!worker.onGround())return;
                h.assertTrue(FieldworkRoutes.walkable(worker,shore),"original worker and valid bank");
                h.assertTrue(NeedRequestData.get(server).requests().stream().allMatch(r->r.reserved()==0&&r.inTransit()==0),
                        "controlled initial configuration must precede any inventory custody");
                // First let the real template's farmland pass its initialization prerequisites.
                // This formal initial state is a controlled crop-loss scenario, not a naturally
                // foodless founding template. Existing carrot needs and all original actors remain.
                for(var relative:NaturalVillageLayout.crops()){
                    var cell=NaturalVillageLayout.at(village,relative);
                    level.setBlockAndUpdate(cell,Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(cell.below(),Blocks.DIRT.defaultBlockState());
                }
                var excluded=new TreeMap<String,Integer>();
                for(var relative:List.of(NaturalVillageLayout.RAW,NaturalVillageLayout.PANTRY,NaturalVillageLayout.WOOD,NaturalVillageLayout.PUBLIC)){
                    var at=NaturalVillageLayout.at(village,relative);
                    var box=com.jgeted.sagadyssey.social.logistics.WarehouseContainers.asContainer(level.getBlockEntity(at));
                    h.assertTrue(box!=null,"initial stock fixture exists");
                    clearInitialFood(box,excluded);
                    var id=ProductionContainerIds.resolve(server,village.dimension(),at);
                    if(RightsStateData.get(server).context(id)!=null)RightsStateData.get(server).bumpContext(id);
                }
                var residents=List.of(farmer,courier,worker);
                for(int i=0;i<3;i++){
                    var npc=residents.get(i);identities[i]=npc.getCharacterId();
                    clearInitialFood(npc.getPersonalInventory(),excluded);
                    life.resetTransientState(npc.getCharacterId(),i==0?600:120,0,now);
                    lastMeal[i]=life.needs(npc.getCharacterId()).lastAteAt();
                }
                h.assertTrue(worker.getMainHandItem().isEmpty(),"startup tools do not overwrite an item");
                int rodSlot=java.util.stream.IntStream.range(0,worker.getPersonalInventory().getContainerSize())
                        .filter(i->worker.getPersonalInventory().getItem(i).isEmpty()).findFirst().orElse(-1);
                h.assertTrue(rodSlot>=0,"initial owned rod has a real free bag slot");
                worker.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.SHEARS));
                worker.getPersonalInventory().setItem(rodSlot,new ItemStack(Items.FISHING_ROD));
                SagadysseyConfig.ENABLE_SETTLEMENT_INDUSTRIES.set(true);
                h.assertTrue(!SettlementIndustryAccess.fixture(worker,"sagadyssey.industry.native"),"ordinary industry admission");
                h.assertTrue(NeedRequestData.get(server).requests().stream().noneMatch(r->r.key().purpose().equals("HOUSEHOLD:"+NaturalFoodDemand.PURPOSE)),"no test-created fishing demand");
                start[0]=now;
                Sagadyssey.LOGGER.info("FOOD_LOOP_INITIAL_EXCLUDED templateFood={} cropCells={} reason=controlled-initial-crop-loss-before-formal-start",excluded,NaturalVillageLayout.crops().size());
                Sagadyssey.LOGGER.info("FOOD_LOOP_START tick={} farmer={} courier={} worker={} initialFood=0 initialFish=0 initialBagRod=1 initialHeldShears=1 seed7019 river={} naturalDemand=true",
                        now,identities[0],identities[1],identities[2],water);
                return;
            }
            h.assertTrue(now-start[0]<=10000,"formal fishing-to-meal budget 10000 ticks");
            var original=List.of(farmer,courier,worker);
            for(int i=0;i<3;i++)h.assertTrue(original.get(i).isAlive()&&original.get(i).getCharacterId().equals(identities[i]),"same original living persons");
            var needs=life.needs(farmer.getCharacterId());
            for(int i=0;i<3;i++){
                long ate=life.needs(original.get(i).getCharacterId()).lastAteAt();
                if(ate>lastMeal[i]){lastMeal[i]=ate;meals[0]++;}
            }
            var nativeData=EntityResourceActionData.existing(server);
            var requests=NeedRequestData.get(server).requests().stream().filter(r->r.key().purpose().equals("HOUSEHOLD:"+NaturalFoodDemand.PURPOSE)).toList();
            var completed=requests.stream().filter(r->r.status()==NeedRequestStatus.DELIVERED||r.status()==NeedRequestStatus.CLOSED||r.status()==NeedRequestStatus.CONSUMED).toList();
            if(now%400==0)Sagadyssey.LOGGER.info("FOOD_LOOP_OBSERVE tick={} meals={} hunger={} requests={} observation={}",now,meals[0],needs.hunger(),requests,NaturalFoodDemand.observe(worker,now));
            if(nativeData==null||completed.size()<2||meals[0]<2)return;
            var ids=requests.stream().map(NeedRequest::id).collect(java.util.stream.Collectors.toSet());
            var actions=nativeData.actions().stream().filter(a->ids.contains(a.read("Need", UUIDUtil.CODEC).orElseThrow())).toList();
            if(actions.stream().anyMatch(a->!EntityResourceActionData.settled(a.getStringOr("State", ""))))return;
            int caught=actions.stream().filter(a->a.getStringOr("State", "").equals("DONE")).mapToInt(a->{
                int n=0;for(var row:MinecraftApi.listOfType(a, "Drops",Tag.TAG_COMPOUND)){
                    var t=(net.minecraft.nbt.CompoundTag)row;
                    if(ItemFingerprint.load(t.getCompoundOrEmpty("Item")).equals(NaturalFoodDemand.COD))n+=t.getIntOr("Quantity", 0);
                }return n;
            }).sum();
            int physical=0;
            for(var point:List.of(output,flows.pointOrNull(ProductionContainerIds.resolve(server,village.dimension(),NaturalVillageLayout.at(village,NaturalVillageLayout.PANTRY)))))
                if(point!=null)physical+=SupplyFlowService.count(SupplyFlowService.container(server,point),NaturalFoodDemand.COD);
            for(var npc:original){physical+=SupplyFlowService.count(npc.getPersonalInventory(),NaturalFoodDemand.COD);physical+=SupplyFlowService.count(npc.getTransportInventory(),NaturalFoodDemand.COD);}
            h.assertTrue(caught==physical+meals[0],"all actual cod is in stock/custody or actually eaten: caught="+caught+" physical="+physical+" meals="+meals[0]);
            h.assertTrue(completed.stream().allMatch(r->r.delivered()==r.requested()&&r.reserved()==0&&r.inTransit()==0),"real delivered quantities and custody settled");
            h.assertTrue(needs.hunger()<350,"original hungry farmer ate the real catch");
            h.assertTrue(actions.stream().filter(a->a.getStringOr("State", "").equals("DONE")).allMatch(a->a.read("DeliveryReceipt", UUIDUtil.CODEC).isPresent()),"real separate deliveries from original worker");
            int shears=worker.getPersonalInventory().count(i->i.is(Items.SHEARS))+(worker.getMainHandItem().is(Items.SHEARS)?1:0);
            int rods=worker.getPersonalInventory().count(i->i.is(Items.FISHING_ROD))+(worker.getMainHandItem().is(Items.FISHING_ROD)?1:0);
            int wear=worker.getPersonalInventory().count(i->i.is(Items.FISHING_ROD)&&i.getDamageValue()>=2)
                    +(worker.getMainHandItem().is(Items.FISHING_ROD)&&worker.getMainHandItem().getDamageValue()>=2?1:0);
            h.assertTrue(shears==1&&rods==1&&wear==1,"original shears retained and original bag rod legally equipped and actually worn; no tool overwritten");
            Sagadyssey.LOGGER.info("FOOD_LOOP_ACCEPT tick={} requests={} catches={} codCaught={} codRemaining={} actualMeals={} hunger={} noRuntimeReplenishment=true",
                    now,completed.stream().map(NeedRequest::id).toList(),actions.size(),caught,physical,meals[0],needs.hunger());
            h.succeed();
        });
    }

    private static void clearInitialFood(Container stock,Map<String,Integer> excluded){
        for(int i=0;i<stock.getContainerSize();i++)if(NpcFoodItems.rationValue(stock.getItem(i))>0){
            var item=stock.getItem(i);
            excluded.merge(ItemFingerprint.of(item).itemId(),item.getCount(),Integer::sum);
            stock.setItem(i,ItemStack.EMPTY);
        }
        stock.setChanged();
    }
}
