package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.household.*;
import com.jgeted.sagadyssey.social.production.ProductionContainerIds;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

@GameTestHolder("sagadyssey_stock")
@PrefixGameTestTemplate(false)
public final class HouseholdStockReconciliationGameTests {
    record Fixture(UUID household, NpcBase npc, Container pantry) {}

    static Fixture fixture(GameTestHelper h, int food, int fuel) {
        var level = h.getLevel(); var server = level.getServer();
        level.setDayTime(1000);
        for (int x=0; x<16; x++) for (int z=0; z<16; z++) h.setBlock(x,0,z,Blocks.STONE);
        var npc=h.spawn(NpcEntityTypes.NPC_BASE.get(),new BlockPos(3,1,5));
        npc.setNoAi(true); npc.setProfession(NpcProfession.COURIER);
        npc.getPersonalInventory().clearContent(); npc.getTransportInventory().clearContent();
        npc.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);
        npc.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
        npc.setPersistenceRequired(); npc.setCommand(NpcCommand.WORK);
        NpcBrainRuntime.setMode(npc,NpcBehaviorMode.PLANNED);
        var households=HouseholdData.get(server);
        var home=households.create("stock-test:"+UUID.randomUUID(),level.dimension().identifier().toString(),
                null,npc.getCharacterId(),h.absolutePos(new BlockPos(3,1,5)),level.getGameTime());
        h.assertTrue(home!=null,"original character can form a household");
        var relative=new BlockPos(12,1,5); h.setBlock(relative,Blocks.BARREL);
        h.assertTrue(households.assignSharedStorage(home.id(),home.dimension(),home.homeAnchor(),h.absolutePos(relative)),
                "assign real household pantry");
        households.setProvisionPlan(new HouseholdProvisionPlan(home.id(),food,fuel,0));
        SupplyFlowService.aggregate(server,level.getGameTime());
        return new Fixture(home.id(),npc,(Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, relative));
    }
    static NeedRequest need(GameTestHelper h,Fixture f,String category) {
        return NeedRequestData.get(h.getLevel().getServer()).requests().stream()
                .filter(r->r.key().requester().equals(f.household())
                        &&r.key().purpose().equals("HOUSEHOLD:household:"+category))
                .filter(r->!r.status().terminal()).findFirst().orElseThrow();
    }
    static Container source(GameTestHelper h,Fixture f,Item item,int quantity) {
        var level=h.getLevel();var server=level.getServer();var relative=new BlockPos(2,1,5);
        h.setBlock(relative,Blocks.BARREL);var pos=h.absolutePos(relative);
        var owner=SubjectRef.household(f.household());String dim=level.dimension().identifier().toString();
        var id=ProductionContainerIds.resolve(server,dim,pos);
        RightsStateData.get(server).ensureContext(id,dim+":"+pos.asLong(),owner,owner,AccessPolicy.HOUSEHOLD);
        var point=new FieldworkStorage(dim,pos,id,owner,AccessPolicy.HOUSEHOLD,TransferIntent.DELIVERY,"household-stock-test");
        var flows=SupplyFlowData.get(server);flows.register(point);flows.reveal(owner,id);
        var inventory=(Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, relative);inventory.setItem(0,new ItemStack(item,quantity));
        return inventory;
    }

    @GameTest(template="fieldwork_empty",batch="household_stock",timeoutTicks=100)
    public static void applesReduceOriginalBreadRequestAndLaterConsumptionGrowsIt(GameTestHelper h) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();long now=h.getLevel().getGameTime();
        var original=need(h,f,"food");
        f.pantry().setItem(0,new ItemStack(Items.APPLE,4));
        SupplyFlowService.aggregate(server,now);var revised=need(h,f,"food");
        h.assertTrue(revised.id().equals(original.id())&&revised.requested()==2&&revised.originalRequested()==6,
                "category food stock reduces original bread deficit to two");
        f.pantry().setItem(0,new ItemStack(Items.APPLE,1));
        SupplyFlowService.aggregate(server,now);revised=need(h,f,"food");
        h.assertTrue(revised.id().equals(original.id())&&revised.requested()==5&&revised.delivered()==0,
                "consumption grows the same demand without inventing delivery");
        h.assertTrue(NeedRequestData.get(server).requests().stream().filter(r->r.key().requester().equals(f.household())).count()==1,
                "no duplicate household request");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="household_stock",timeoutTicks=100)
    public static void charcoalFillCancelsCoalNeedAndReloadPreservesNewWindow(GameTestHelper h) {
        var f=fixture(h,0,8);var server=h.getLevel().getServer();long now=h.getLevel().getGameTime();
        var original=need(h,f,"fuel");f.pantry().setItem(0,new ItemStack(Items.CHARCOAL,8));
        SupplyFlowService.aggregate(server,now);
        var old=NeedRequestData.get(server).request(original.id());
        h.assertTrue(old.status()==NeedRequestStatus.CANCELLED&&old.delivered()==0,
                "real charcoal satisfies fuel buffer without fake coal delivery");
        h.runAtTickTime(2,()->{
            f.pantry().setItem(0,new ItemStack(Items.CHARCOAL,3));
            SupplyFlowService.aggregate(server,h.getLevel().getGameTime());var fresh=need(h,f,"fuel");
            h.assertTrue(!fresh.id().equals(original.id())&&fresh.requested()==5,"new shortage uses a new request window");
            var restored=NeedRequestData.load(NeedRequestData.get(server).save(new CompoundTag(),server.registryAccess()),server.registryAccess());
            var flows=SupplyFlowData.load(SupplyFlowData.get(server).save(new CompoundTag(),server.registryAccess()),server.registryAccess());
            h.assertTrue(restored.request(old.id()).equals(old)&&restored.request(fresh.id()).equals(fresh),"reload preserves old cancellation and new identity");
            h.assertTrue(flows.demand(fresh.id()).principal().equals(SubjectRef.household(f.household())),"reload preserves household scope");
            h.assertTrue(f.pantry().getItem(0).getCount()==3,"aggregation never consumes pantry stock");h.succeed();
        });
    }

    @GameTest(template="fieldwork_empty",batch="household_stock",timeoutTicks=100)
    public static void fullPantryKeepsRealReservedBatchWithoutExtraDemand(GameTestHelper h) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();long now=h.getLevel().getGameTime();
        var original=need(h,f,"food");var inventory=source(h,f,Items.BREAD,3);
        SupplyFlowService.match(server,now);
        h.assertTrue(NeedRequestData.get(server).request(original.id()).reserved()==3,"three real loaves reserved first");
        f.pantry().setItem(0,new ItemStack(Items.APPLE,6));SupplyFlowService.aggregate(server,now);
        var revised=NeedRequestData.get(server).request(original.id());
        h.assertTrue(revised.reserved()==3&&revised.requested()==3&&revised.remaining()==0,
                "committed batch stays while unmatched shortage disappears");
        var route=SupplyFlowData.get(server).routes().stream().filter(r->r.need().equals(original.id())).findFirst().orElseThrow();
        h.assertTrue(!RightsStateData.get(server).reservationsForToken(route.token(),now).isEmpty()
                &&SupplyFlowService.count(inventory,new ItemFingerprint("minecraft:bread",0))==3,
                "real lease and source stock survive aggregation");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="household_stock",timeoutTicks=100)
    public static void manualToolTopUpDoesNotRewriteLinkedProductionContract(GameTestHelper h) {
        var f=fixture(h,0,0);var level=h.getLevel();var server=level.getServer();long now=level.getGameTime();
        HouseholdData.get(server).setProvisionPlan(new HouseholdProvisionPlan(f.household(),0,0,1));
        h.setBlock(new BlockPos(11,1,6),Blocks.ANVIL);
        SupplyFlowService.aggregate(server,now);var original=need(h,f,"tools");
        SupplyFlowService.match(server,now);
        var flow=SupplyFlowData.get(server);var orderId=flow.production(original.id());
        h.assertTrue(orderId!=null,"real tool deficit creates an actual production order");
        var orders=com.jgeted.sagadyssey.social.production.ProductionOrderData.get(server);
        var order=orders.byId(orderId).orElseThrow();
        f.pantry().setItem(0,new ItemStack(Items.IRON_PICKAXE));
        SupplyFlowService.aggregate(server,now);
        h.assertTrue(NeedRequestData.get(server).request(original.id()).equals(original)
                &&orders.byId(orderId).orElseThrow().equals(order),
                "manual tool supply preserves fixed linked request and production inputs/output");
        h.assertTrue(SupplyFlowService.count(f.pantry(),new ItemFingerprint("minecraft:iron_pickaxe",0))==1,
                "no production or phantom output occurred");h.succeed();
    }
    @GameTest(template="fieldwork_empty",batch="household_stock",timeoutTicks=1600)
    public static void carriedHouseholdBreadSurvivesExternalFillAndStillDelivers(GameTestHelper h) {
        var f=fixture(h,6,0);var level=h.getLevel();var server=level.getServer();
        var original=need(h,f,"food");var inventory=source(h,f,Items.BREAD,3);
        for(int i=0;i<f.pantry().getContainerSize();i++)f.pantry().setItem(i,new ItemStack(Items.COBBLESTONE,64));
        SupplyFlowService.match(server,level.getGameTime());f.npc().setNoAi(false);
        boolean[] observed={false};
        h.succeedWhen(()->{
            var data=NeedRequestData.get(server);
            if(!observed[0]){
                h.assertTrue(SupplyFlowService.count(f.npc().getTransportInventory(),new ItemFingerprint("minecraft:bread",0))==3,
                        "original family member actually carries three loaves before pantry fill");
                f.pantry().setItem(0,new ItemStack(Items.APPLE,6));
                SupplyFlowService.aggregate(server,level.getGameTime());var revised=data.request(original.id());
                h.assertTrue(revised.inTransit()==3&&revised.requested()==3&&revised.remaining()==0,
                        "real carried obligation remains and uncommitted deficit shrinks");
                f.pantry().setItem(1,ItemStack.EMPTY);observed[0]=true;
            }
            var revised=data.request(original.id());
            h.assertTrue(revised.status()==NeedRequestStatus.DELIVERED&&revised.delivered()==3,
                    "same request settles actual bread once");
            h.assertTrue(SupplyFlowService.count(inventory,new ItemFingerprint("minecraft:bread",0))==0
                    &&SupplyFlowService.count(f.pantry(),new ItemFingerprint("minecraft:bread",0))==3
                    &&SupplyFlowService.count(f.npc().getTransportInventory(),new ItemFingerprint("minecraft:bread",0))==0,
                    "source, original carrier and pantry conserve the three loaves");
            h.assertTrue(SupplyFlowService.count(f.pantry(),new ItemFingerprint("minecraft:apple",0))==6,
                    "manual top-up remains intact");
        });
    }
}
