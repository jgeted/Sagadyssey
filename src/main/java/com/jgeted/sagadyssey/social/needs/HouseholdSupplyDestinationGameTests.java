package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import static com.jgeted.sagadyssey.social.needs.HouseholdStockReconciliationGameTests.*;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

@GameTestHolder("sagadyssey_stock")
@PrefixGameTestTemplate(false)
public final class HouseholdSupplyDestinationGameTests {
    private static UUID movePantry(GameTestHelper h,Fixture f) {
        var relative=new BlockPos(14,1,11);h.setBlock(relative,Blocks.BARREL);
        var data=HouseholdData.get(h.getLevel().getServer());var home=data.byId(f.household());
        h.assertTrue(data.assignSharedStorage(home.id(),home.dimension(),home.homeAnchor(),h.absolutePos(relative)),
                "move household registration to a different physical pantry");
        return ContainerIds.block("household_storage",home.dimension(),h.absolutePos(relative));
    }
    private static NeedRequest movedNeed(GameTestHelper h,Fixture f,UUID destination) {
        return NeedRequestData.get(h.getLevel().getServer()).requests().stream()
                .filter(r->r.key().requester().equals(f.household())&&r.key().destination().equals(destination)
                        &&r.key().purpose().equals("HOUSEHOLD:household:food")&&!r.status().terminal())
                .findFirst().orElseThrow();
    }
    private static SupplyFlowData.Route route(GameTestHelper h,NeedRequest request) {
        return SupplyFlowData.get(h.getLevel().getServer()).routes().stream()
                .filter(r->r.need().equals(request.id())).findFirst().orElseThrow();
    }
    private static void full(Container inventory) {
        for(int i=0;i<inventory.getContainerSize();i++)inventory.setItem(i,new ItemStack(Items.COBBLESTONE,64));
    }
    private static void conserved(GameTestHelper h,Fixture f,Container source) {
        var bread=new ItemFingerprint("minecraft:bread",0);
        h.assertTrue(SupplyFlowService.count(source,bread)==0&&SupplyFlowService.count(f.pantry(),bread)==3
                        &&SupplyFlowService.count(f.npc().getTransportInventory(),bread)==0,
                "original source/carrier/pantry conserve exactly three physical loaves");
        h.assertTrue(h.getLevel().getEntity(f.npc().getUUID())==f.npc(),"delivery uses the original physical actor");
    }

    @GameTest(template="fieldwork_empty",batch="household_destination",timeoutTicks=100)
    public static void moveStopsDirectOldMatchingAndCreatesSeparateNewRequest(GameTestHelper h) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();long now=h.getLevel().getGameTime();
        var original=need(h,f,"food");source(h,f,Items.BREAD,3);var destination=movePantry(h,f);
        // Matching can be called independently of aggregation: it must validate current metadata.
        SupplyFlowService.match(server,now);
        h.assertTrue(NeedRequestData.get(server).request(original.id()).reserved()==0
                &&SupplyFlowData.get(server).routes().stream().noneMatch(r->r.need().equals(original.id())),
                "direct matching cannot promise stock to the former pantry");
        SupplyFlowService.aggregate(server,now);var fresh=movedNeed(h,f,destination);
        var retired=NeedRequestData.get(server).request(original.id());
        h.assertTrue(retired.status()==NeedRequestStatus.CANCELLED&&retired.key().equals(original.key()),
                "retired request keeps its original destination and history");
        h.assertTrue(!fresh.id().equals(original.id())&&fresh.requested()==6,"new pantry receives a distinct request");
        h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="household_destination",timeoutTicks=100)
    public static void moveReleasesOnlyUnpickedLeaseBeforeNewMatching(GameTestHelper h) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();long now=h.getLevel().getGameTime();
        var original=need(h,f,"food");var inventory=source(h,f,Items.BREAD,3);SupplyFlowService.match(server,now);
        var held=route(h,original);h.assertTrue(NeedRequestData.get(server).request(original.id()).reserved()==3,"real original lease");
        var destination=movePantry(h,f);SupplyFlowService.aggregate(server,now);
        h.assertTrue(SupplyFlowData.get(server).route(held.id()).stage()==SupplyFlowData.Stage.CANCELLED
                &&RightsStateData.get(server).reservationsForToken(held.token(),now).isEmpty(),
                "old managed order and actual source lease are formally released");
        h.assertTrue(NeedRequestData.get(server).request(original.id()).status()==NeedRequestStatus.CANCELLED,
                "old request ends only after release");
        var fresh=movedNeed(h,f,destination);SupplyFlowService.match(server,now);
        h.assertTrue(NeedRequestData.get(server).request(fresh.id()).reserved()==3
                &&SupplyFlowService.count(inventory,new ItemFingerprint("minecraft:bread",0))==3,
                "same physical batch can now be reserved once for the new pantry");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="household_destination",timeoutTicks=100)
    public static void movePreservesUncertainPickupIntentAndBlocksNewDemand(GameTestHelper h) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();long now=h.getLevel().getGameTime();
        var original=need(h,f,"food");var inventory=source(h,f,Items.BREAD,3);SupplyFlowService.match(server,now);
        var flows=SupplyFlowData.get(server);var held=route(h,original).claim(f.npc().getUUID(),f.npc().getCharacterId());
        var rights=RightsStateData.get(server);var bag=ContainerIds.npcCargo(f.npc().getCharacterId());
        var owner=SubjectRef.character(f.npc().getCharacterId());rights.ensureContext(bag,"npc:"+owner.id()+":cargo",owner,owner,AccessPolicy.PRIVATE);
        var intent=new SupplyFlowData.Pending(UUID.randomUUID(),true,bag,3,original.key().item(),
                rights.context(held.source()).revision(),rights.context(bag).revision(),now);
        // Persist an actual prepared intent, deliberately without submitting or inventing a receipt.
        held=held.progress(SupplyFlowData.Stage.HELD,intent,now,0,"prepared pickup without receipt");flows.route(held);
        var destination=movePantry(h,f);SupplyFlowService.aggregate(server,now);
        var kept=flows.route(held.id());
        h.assertTrue(kept.equals(held)&&TransferLedgerData.get(server).receipt(intent.id())==null,
                "migration does not erase or submit an uncertain transfer");
        h.assertTrue(NeedRequestData.get(server).request(original.id()).reserved()==3
                        &&!rights.reservationsForToken(held.token(),now).isEmpty(),"unresolved original lease remains");
        h.assertTrue(NeedRequestData.get(server).requests().stream().noneMatch(r->r.key().requester().equals(f.household())
                &&r.key().destination().equals(destination)&&!r.status().terminal()),"new request waits for uncertain custody reconciliation");
        var restored=SupplyFlowData.load(flows.save(new CompoundTag(),server.registryAccess()),server.registryAccess());
        h.assertTrue(restored.route(held.id()).equals(held)&&SupplyFlowService.count(inventory,original.key().item())==3,
                "serialized original intent and physical stock are preserved");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="household_destination",timeoutTicks=100)
    public static void moveKeepsLinkedProductionFixedAndDefersReplacementRequest(GameTestHelper h) {
        var f=fixture(h,0,0);var level=h.getLevel();var server=level.getServer();long now=level.getGameTime();
        HouseholdData.get(server).setProvisionPlan(new com.jgeted.sagadyssey.social.household.HouseholdProvisionPlan(f.household(),0,0,1));
        h.setBlock(new BlockPos(11,1,6),Blocks.ANVIL);
        SupplyFlowService.aggregate(server,now);var original=need(h,f,"tools");SupplyFlowService.match(server,now);
        var flows=SupplyFlowData.get(server);var orderId=flows.production(original.id());
        h.assertTrue(orderId!=null,"original tool need has a real fixed production order");
        var orders=com.jgeted.sagadyssey.social.production.ProductionOrderData.get(server);
        var originalOrder=orders.byId(orderId).orElseThrow();var destination=movePantry(h,f);
        SupplyFlowService.aggregate(server,now);SupplyFlowService.match(server,now);
        h.assertTrue(NeedRequestData.get(server).request(original.id()).equals(original)
                &&orders.byId(orderId).orElseThrow().equals(originalOrder),"migration never rewrites the fixed original production contract");
        h.assertTrue(NeedRequestData.get(server).requests().stream().noneMatch(r->r.key().requester().equals(f.household())
                &&r.key().destination().equals(destination)&&!r.status().terminal()),"new request waits for existing production obligation");
        var restored=SupplyFlowData.load(flows.save(new CompoundTag(),server.registryAccess()),server.registryAccess());
        h.assertTrue(orderId.equals(restored.production(original.id())),"serialized production link keeps original identity");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="household_destination",timeoutTicks=1600)
    public static void moveClosesFullyDeliveredHistoryWithoutCancellingCompletedGoods(GameTestHelper h) {
        var f=fixture(h,6,0);var level=h.getLevel();var server=level.getServer();var original=need(h,f,"food");
        var inventory=source(h,f,Items.BREAD,6);SupplyFlowService.match(server,level.getGameTime());
        var originalRoute=route(h,original);f.npc().setNoAi(false);
        h.succeedWhen(()->{
            var data=NeedRequestData.get(server);
            h.assertTrue(SupplyFlowData.get(server).route(originalRoute.id()).stage()==SupplyFlowData.Stage.DONE,
                    "original six loaves are really delivered before relocation");
            h.assertTrue(data.request(original.id()).status()==NeedRequestStatus.DELIVERED,"original request was fully fulfilled");
            var destination=movePantry(h,f);SupplyFlowService.aggregate(server,level.getGameTime());
            var history=data.request(original.id());var fresh=movedNeed(h,f,destination);
            h.assertTrue(history.status()==NeedRequestStatus.CLOSED&&history.delivered()==6
                    &&history.key().equals(original.key()),"full delivery closes as immutable history");
            h.assertTrue(!fresh.id().equals(original.id())&&fresh.requested()==6,"replacement pantry has its own measured need");
            h.assertTrue(SupplyFlowService.count(inventory,original.key().item())==0
                    &&SupplyFlowService.count(f.pantry(),original.key().item())==6
                    &&SupplyFlowService.count(f.npc().getTransportInventory(),original.key().item())==0,
                    "completed goods remain in the original physical pantry");
        });
    }
    @GameTest(template="fieldwork_empty",batch="household_destination",timeoutTicks=1600)
    public static void movingWhileCarryingFinishesOriginalDestinationBeforeNewRequest(GameTestHelper h) {
        var f=fixture(h,6,0);var level=h.getLevel();var server=level.getServer();var original=need(h,f,"food");
        var inventory=source(h,f,Items.BREAD,3);full(f.pantry());SupplyFlowService.match(server,level.getGameTime());
        var originalRoute=route(h,original);f.npc().setNoAi(false);UUID[] destination={null};
        h.succeedWhen(()->{
            var data=NeedRequestData.get(server);var flows=SupplyFlowData.get(server);
            if(destination[0]==null){
                h.assertTrue(SupplyFlowService.count(f.npc().getTransportInventory(),original.key().item())==3,
                        "original household member actually picked up bread");
                var next=movePantry(h,f);SupplyFlowService.aggregate(server,level.getGameTime());
                h.assertTrue(data.request(original.id()).status()==NeedRequestStatus.UNREACHABLE
                        &&data.request(original.id()).inTransit()==3,"stale demand waits without losing original cargo");
                h.assertTrue(data.requests().stream().noneMatch(r->r.key().destination().equals(next)&&!r.status().terminal()),
                        "no second demand while original obligation is carried");
                h.assertTrue(flows.route(originalRoute.id()).destination().equals(original.key().destination()),
                        "carried route never silently changes destination");
                f.pantry().setItem(0,ItemStack.EMPTY);destination[0]=next;
            }
            h.assertTrue(flows.route(originalRoute.id()).stage()==SupplyFlowData.Stage.DONE,"original route physically delivers first");
            SupplyFlowService.aggregate(server,level.getGameTime());var retired=data.request(original.id());
            h.assertTrue(retired.status()==NeedRequestStatus.CANCELLED&&retired.delivered()==3&&retired.inTransit()==0,
                    "preserve partial delivery while ending remaining former-pantry demand");
            var fresh=movedNeed(h,f,destination[0]);h.assertTrue(fresh.requested()==6&&!fresh.id().equals(original.id()),"new pantry measures its own shortage");
            var restored=SupplyFlowData.load(flows.save(new CompoundTag(),server.registryAccess()),server.registryAccess());
            h.assertTrue(restored.route(originalRoute.id()).destination().equals(original.key().destination()),"serialized route still identifies original destination");
            conserved(h,f,inventory);
            h.assertTrue(SupplyFlowService.count((Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(14,1,11)),original.key().item())==0,
                    "new pantry receives no remote or copied cargo");
        });
    }

    @GameTest(template="fieldwork_empty",batch="household_destination",timeoutTicks=100)
    public static void dissolvedHouseholdReleasesUnpickedStockAndStopsMatching(GameTestHelper h) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();long now=h.getLevel().getGameTime();var original=need(h,f,"food");
        var inventory=source(h,f,Items.BREAD,3);SupplyFlowService.match(server,now);var held=route(h,original);
        h.assertTrue(HouseholdData.get(server).disband(f.household()),"dissolve the actual household");
        SupplyFlowService.aggregate(server,now);SupplyFlowService.match(server,now);
        h.assertTrue(NeedRequestData.get(server).request(original.id()).status()==NeedRequestStatus.CANCELLED
                &&SupplyFlowData.get(server).route(held.id()).stage()==SupplyFlowData.Stage.CANCELLED,
                "dissolution formally ends unpicked obligations");
        h.assertTrue(RightsStateData.get(server).reservationsForToken(held.token(),now).isEmpty()
                &&SupplyFlowService.count(inventory,original.key().item())==3,"lease released without consuming source stock");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="household_destination",timeoutTicks=1600)
    public static void dissolvedHouseholdStillAllowsOriginalCarrierToSettleCustody(GameTestHelper h) {
        var f=fixture(h,6,0);var level=h.getLevel();var server=level.getServer();var original=need(h,f,"food");
        var inventory=source(h,f,Items.BREAD,3);full(f.pantry());SupplyFlowService.match(server,level.getGameTime());
        var originalRoute=route(h,original);f.npc().setNoAi(false);boolean[] dissolved={false};
        h.succeedWhen(()->{
            var data=NeedRequestData.get(server);
            if(!dissolved[0]){
                h.assertTrue(SupplyFlowService.count(f.npc().getTransportInventory(),original.key().item())==3,"original body actually carries the bread");
                h.assertTrue(HouseholdData.get(server).disband(f.household()),"dissolve only after pickup");
                SupplyFlowService.aggregate(server,level.getGameTime());
                h.assertTrue(data.request(original.id()).status()==NeedRequestStatus.UNREACHABLE
                        &&data.request(original.id()).inTransit()==3,"dissolution does not cancel carried assets");
                f.pantry().setItem(0,ItemStack.EMPTY);dissolved[0]=true;
            }
            h.assertTrue(SupplyFlowData.get(server).route(originalRoute.id()).stage()==SupplyFlowData.Stage.DONE,
                    "same carrier settles original physical obligation after membership ends");
            SupplyFlowService.aggregate(server,level.getGameTime());var retired=data.request(original.id());
            h.assertTrue(retired.status()==NeedRequestStatus.CANCELLED&&retired.delivered()==3&&retired.inTransit()==0,
                    "finish custody and cancel only the outstanding former-household shortage");
            h.assertTrue(data.requests().stream().noneMatch(r->r.key().requester().equals(f.household())&&!r.status().terminal()),
                    "dissolved household generates no new request");conserved(h,f,inventory);
        });
    }
}
