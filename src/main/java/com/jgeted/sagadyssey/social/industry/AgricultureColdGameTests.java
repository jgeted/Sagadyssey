package com.jgeted.sagadyssey.social.industry;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import net.minecraft.core.UUIDUtil;

import static com.jgeted.sagadyssey.social.industry.IndustryCompletionGameTests.*;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.industry.common.FurnaceWorkAdapter;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** A new explicit agronomic starting condition. The original 12 isolated-soil FAIL is untouched. */
@GameTestHolder("sagadyssey_industries_completion") @PrefixGameTestTemplate(false)
public final class AgricultureColdGameTests {
    @GameTestGenerator public static Collection<TestFunction> tests(){
        String phase=System.getProperty("sagadyssey.completion.phase","");
        if(!Boolean.getBoolean("sagadyssey.industry.native")
                ||!Set.of("agri-cold-row","agri-household-row").contains(phase))return List.of();
        boolean ordinary=phase.equals("agri-household-row");
        return List.of(new TestFunction("industry_completion",ordinary?"household_row_seed_to_meal":"cold_row_seed_to_meal",
                "sagadyssey_industries_completion:empty",72000,0,true,h->run(h,ordinary)));
    }
    private static void run(GameTestHelper h, boolean ordinary){
        floor(h,3);var level=h.getLevel();var server=level.getServer();long start=level.getGameTime();
        var farmer=npc(h,2,3,NpcProfession.FARMER);var cook=npc(h,11,7,NpcProfession.WORKER);
        var eater=npc(h,23,7,NpcProfession.WORKER);var courier=npc(h,17,7,NpcProfession.COURIER);
        var residents=List.of(farmer,cook,eater,courier);var identities=residents.stream().map(NpcBase::getCharacterId).toList();
        var home=family(h,"cold row agriculture",residents);hire(courier,cook,start);
        farmer.setFarmMode(FarmMode.CROP);farmer.setWorkZone(at(3,0,3),at(14,0,3));
        farmer.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_HOE));
        // These sixteen are declared initial seed capital, kept by the unchanged deposit floor.
        farmer.getPersonalInventory().setItem(0,new ItemStack(Items.POTATO,16));
        for(var npc:List.of(farmer,cook,courier))npc.getPersonalInventory().setItem(5,new ItemStack(Items.BREAD,16));
        eater.getPersonalInventory().clearContent();eater.setCommand(NpcCommand.WORK);
        for(var pair:List.of(new Object[]{farmer,2},new Object[]{cook,5},new Object[]{eater,8},new Object[]{courier,11}))
            bed(h,(NpcBase)pair[0],(int)pair[1],18);
        // Hydrated continuous support soil, one row of twelve age-zero plants. The side
        // support rows lie outside the farmer's work zone, so they are not free extra crops.
        for(int x=2;x<=15;x++)for(int z=2;z<=4;z++)level.setBlockAndUpdate(at(x,-1,z),Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7));
        for(int x=3;x<=14;x++)level.setBlockAndUpdate(at(x,0,3),Blocks.POTATOES.defaultBlockState());
        for(int x:new int[]{5,9,13})level.setBlockAndUpdate(at(x,-1,1),Blocks.WATER.defaultBlockState());
        var farmOwner=SubjectRef.character(farmer.getCharacterId());var owner=SubjectRef.character(cook.getCharacterId());var consumer=SubjectRef.household(home);
        var farm=point(h,at(2,0,10),farmOwner,TransferIntent.GIFT);var raw=point(h,at(11,0,10),owner,TransferIntent.DELIVERY);var output=point(h,at(16,0,10),owner,TransferIntent.GIFT);
        level.setBlockAndUpdate(at(23,0,10),Blocks.BARREL.defaultBlockState());
        h.assertTrue(HouseholdData.get(server).assignSharedStorage(home,raw.dimension(),at(22,0,10),at(23,0,10)),"real original pantry assignment");
        var target=new FieldworkStorage(raw.dimension(),at(23,0,10),ContainerIds.block("household_storage",raw.dimension(),at(23,0,10)),consumer,AccessPolicy.HOUSEHOLD,TransferIntent.DELIVERY,"household_supply");
        RightsStateData.get(server).ensureContext(target.containerId(),raw.dimension()+":"+target.position().asLong(),consumer,consumer,AccessPolicy.HOUSEHOLD);
        var flows=SupplyFlowData.get(server);flows.register(target);
        h.assertTrue(NpcLifeData.get(server).assignPrivateStorage(server,farmer.getCharacterId(),farm.dimension(),farm.position(),start),"original private farm output");
        FieldworkData.get(server).bindTestStorage(farmer.getUUID(),farm);
        grant(h,farm,owner,"farmer consents to real seed-protected surplus for cooking");
        grant(h,output,consumer,"cook consents to actual household meal supply");
        box(h,raw).setItem(0,new ItemStack(Items.COAL,2));
        flows.reveal(owner,farm.containerId());flows.reveal(owner,raw.containerId());flows.reveal(owner,output.containerId());flows.reveal(consumer,output.containerId());
        flows.policy(new SupplyFlowData.Policy("cold-row-raw",owner,raw.containerId(),fp("potato"),3,"workshop"));
        flows.policy(new SupplyFlowData.Policy("cold-row-cooked",owner,output.containerId(),fp("baked_potato"),3,"workshop"));
        var furnace=at(13,0,10);level.setBlockAndUpdate(furnace,Blocks.FURNACE.defaultBlockState());
        RightsStateData.get(server).ensureContext(ProductionContainerIds.resolve(server,raw.dimension(),furnace),"owned crop cooking furnace",owner,owner,AccessPolicy.PRIVATE);
        // Preserve the first diagnostic exactly: its extra six-item test request remains.
        // The new selector observes only the existing household business request.
        var need=ordinary?null:SupplyFlowService.request(server,consumer,"internal:actual-row-harvest",target,fp("baked_potato"),6,start,start+72000);
        SupplyFlowService.aggregate(server,start);SupplyFlowService.match(server,start);
        Sagadyssey.LOGGER.info("AGRI_ROW_VARIANT ordinaryHouseholdOnly={} extraTestNeed={}",ordinary,need==null?"none":need.id());
        long[] firstHarvest={-1},firstMeal={-1};int[] previousCooked={0},cookedEaten={0};
        long[] lastMeal=residents.stream().mapToLong(n->NpcLifeData.get(server).needs(n.getCharacterId()).lastAteAt()).toArray();
        Sagadyssey.LOGGER.info("AGRI_ROW_START tick={} people={} plants=12 age=0 protectedSeedInput=16 initialProcessablePotato=0 initialBaked=0 bread=48 coal=2 rawBatch=3 deadline=72000 randomTicks=3",start,identities);
        sprint(h,72500);
        h.onEachTick(()->{
            long now=level.getGameTime();
            if(now%20==0&&java.nio.file.Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("industry-stop-request.json")))h.fail("bounded wall-clock stop; preserve original world");
            for(int i=0;i<residents.size();i++)h.assertTrue(residents.get(i).isAlive()&&identities.get(i).equals(residents.get(i).getCharacterId()),"original person remains alive");
            var work=FieldworkData.get(server);var receipts=work.receiptsFor(farmer.getUUID());
            int harvested=receipts.stream().flatMap(r->r.inventoryDeltas().stream()).filter(d->d.fingerprint().equals("minecraft:potato#0")&&d.quantity()>0).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();
            int replanted=-receipts.stream().filter(r->r.action().operation()==FieldworkAction.Operation.PLANT).flatMap(r->r.inventoryDeltas().stream()).filter(d->d.fingerprint().equals("minecraft:potato#0")&&d.quantity()<0).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();
            if(harvested>0&&firstHarvest[0]<0)firstHarvest[0]=now;
            var crops=new HashMap<BlockPos,Integer>();for(var r:receipts)if(r.action().operation()==FieldworkAction.Operation.HARVEST)crops.merge(r.action().target(),1,Integer::sum);
            var machine=(AbstractFurnaceBlockEntity)level.getBlockEntity(furnace);
            int cooked=count(machine,Items.BAKED_POTATO);int rawPhysical=count(machine,Items.POTATO);
            for(var point:List.of(farm,raw,output,target)){cooked+=count(box(h,point),Items.BAKED_POTATO);rawPhysical+=count(box(h,point),Items.POTATO);}
            boolean actualMeal=false;
            for(int i=0;i<residents.size();i++){
                var npc=residents.get(i);cooked+=npc.getPersonalInventory().count(x->x.is(Items.BAKED_POTATO))+count(npc.getTransportInventory(),Items.BAKED_POTATO);
                rawPhysical+=npc.getPersonalInventory().count(x->x.is(Items.POTATO))+count(npc.getTransportInventory(),Items.POTATO);
                long ate=NpcLifeData.get(server).needs(npc.getCharacterId()).lastAteAt();
                if(ate>lastMeal[i]){actualMeal=true;lastMeal[i]=ate;}
            }
            if(cooked<previousCooked[0]){
                h.assertTrue(actualMeal,"baked inventory falls only with an actual original-person meal");
                cookedEaten[0]+=previousCooked[0]-cooked;if(firstMeal[0]<0)firstMeal[0]=now;
            }
            previousCooked[0]=cooked;
            // The 12 live initial plants and 16 protected seed items are separate initial inputs.
            // Every later raw item comes from the original fieldwork receipts; native oven output
            // accounts for raw use even while a batch is still physically in the furnace.
            h.assertTrue(16+harvested==rawPhysical+replanted+cooked+cookedEaten[0],"potato conservation: initial16+harvest="+(16+harvested)+" raw="+rawPhysical+" planted="+replanted+" baked="+cooked+" eaten="+cookedEaten[0]);
            var foodNeeds=NeedRequestData.get(server).requests().stream().filter(r->r.key().requester().equals(home)
                    &&r.key().purpose().equals("HOUSEHOLD:household:food")&&r.key().item().equals(fp("baked_potato"))).toList();
            int received=ordinary?foodNeeds.stream().mapToInt(r->r.delivered()).sum():NeedRequestData.get(server).request(need.id()).delivered();
            int routeDelivered=ordinary?flows.routes().stream().filter(r->r.stage()==SupplyFlowData.Stage.DONE
                    &&foodNeeds.stream().anyMatch(n->n.id().equals(r.need()))).mapToInt(r->r.quantity()).sum():received;
            if(now%4000==0)Sagadyssey.LOGGER.info("AGRI_ROW_OBSERVE tick={} harvested={} replanted={} raw={} baked={} eaten={} repeated={} need={}",now,harvested,replanted,rawPhysical,cooked,cookedEaten[0],crops.values().stream().filter(n->n>=2).count(),ordinary?foodNeeds:NeedRequestData.get(server).request(need.id()));
            var orders=ProductionOrderData.get(server).ordersForWorker(cook.getCharacterId()).stream().filter(o->FurnaceWorkAdapter.recognized(o)&&o.status()==ProductionOrderStatus.COMPLETED).toList();
            if(orders.size()<2||orders.stream().anyMatch(o->!FurnaceWorkAdapter.completedDelivery(server,o))
                    ||received!=6||routeDelivered!=6||cookedEaten[0]<1
                    ||crops.values().stream().noneMatch(n->n>=2))return;
            h.assertTrue(farmer.getPersonalInventory().count(x->x.is(Items.POTATO))>=16,"original seed protection remains physically available");
            Sagadyssey.LOGGER.info("AGRI_ROW_ACCEPT tick={} firstHarvest={} firstCookedMeal={} producedRaw={} replanted={} rawRemaining={} bakedRemaining={} eaten={} nativeCycles={} received=6 repeatedCells={} samePeople={} noRuntimeReplenishment=true",
                    now,firstHarvest[0],firstMeal[0],harvested,replanted,rawPhysical,cooked,cookedEaten[0],orders.size(),crops.values().stream().filter(n->n>=2).count(),identities);
            var mark=new CompoundTag();mark.putLong("Start",start);mark.putLong("FirstHarvest",firstHarvest[0]);mark.putLong("FirstCookedMeal",firstMeal[0]);mark.putInt("RawHarvested",harvested);mark.putInt("Replanted",replanted);mark.putInt("CookedConsumed",cookedEaten[0]);if(need!=null)mark.store("Need", UUIDUtil.CODEC,need.id());
            if(ordinary){
                var ids=new net.minecraft.nbt.ListTag();for(var n:foodNeeds){var entry=new CompoundTag();entry.store("Id", UUIDUtil.CODEC,n.id());entry.putInt("Delivered",n.delivered());entry.putInt("Remaining",n.remaining());entry.putString("Status",n.status().name());ids.add(entry);}
                mark.put("OrdinaryHouseholdNeeds",ids);
                Sagadyssey.LOGGER.info("AGRI_ROW_HOUSEHOLD_FINAL needHistory={} doneRouteQuantity={} outstandingPreserved=true",foodNeeds,routeDelivered);
            }
            save(h,mark);h.succeed();
        });
    }
}
