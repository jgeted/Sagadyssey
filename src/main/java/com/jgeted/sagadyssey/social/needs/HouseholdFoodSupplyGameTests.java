package com.jgeted.sagadyssey.social.needs;

import static com.jgeted.sagadyssey.social.needs.HouseholdStockReconciliationGameTests.*;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.npc.brain.life.LifeActivityData;
import com.jgeted.sagadyssey.npc.brain.life.LifePlanFactory;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.social.household.*;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.production.ProductionContainerIds;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("sagadyssey_stock")
@PrefixGameTestTemplate(false)
public final class HouseholdFoodSupplyGameTests {
    private static final ItemFingerprint APPLE = new ItemFingerprint("minecraft:apple",0);
    private static final ItemFingerprint BREAD = new ItemFingerprint("minecraft:bread",0);
    private static List<NeedRequest> active(GameTestHelper h, Fixture f) {
        return NeedRequestData.get(h.getLevel().getServer()).requests().stream()
                .filter(r->r.key().requester().equals(f.household())
                        &&r.key().purpose().equals("HOUSEHOLD:household:food")&&!r.status().terminal()).toList();
    }
    private static NeedRequest only(GameTestHelper h, Fixture f) {
        var rows=active(h,f);h.assertTrue(rows.size()==1,"exactly one active household food request");return rows.getFirst();
    }
    private record Site(FieldworkStorage point,Container inventory) {}
    private static Site site(GameTestHelper h,Fixture f,BlockPos relative,ItemStack stock,SubjectRef owner,boolean known) {
        h.setBlock(relative,Blocks.BARREL);var server=h.getLevel().getServer();var pos=h.absolutePos(relative);
        String dimension=h.getLevel().dimension().location().toString();
        var id=ProductionContainerIds.resolve(server,dimension,pos);
        RightsStateData.get(server).ensureContext(id,dimension+":"+pos.asLong(),owner,owner,AccessPolicy.PRIVATE);
        var point=new FieldworkStorage(dimension,pos,id,owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"food-selection-test");
        var flows=SupplyFlowData.get(server);flows.register(point);
        if(known)flows.reveal(SubjectRef.household(f.household()),id);
        var inventory=(Container)h.getBlockEntity(relative);inventory.setItem(0,stock);
        return new Site(point,inventory);
    }
    private static void aggregate(GameTestHelper h) {SupplyFlowService.aggregate(h.getLevel().getServer(),h.getLevel().getGameTime());}
    private static SupplyFlowData.Route route(GameTestHelper h,NeedRequest r) {
        return SupplyFlowData.get(h.getLevel().getServer()).routes().stream().filter(x->x.need().equals(r.id())).findFirst().orElseThrow();
    }
    private static void full(Container c) {for(int i=0;i<c.getContainerSize();i++)c.setItem(i,new ItemStack(Items.COBBLESTONE,64));}

    @GameTest(template="fieldwork_empty",batch="household_food",timeoutTicks=1800)
    public static void applesActuallyTravelToPantryAndAreConsumedOnce(GameTestHelper h) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();var old=only(h,f);
        var source=source(h,f,Items.APPLE,6);aggregate(h);var selected=only(h,f);
        h.assertTrue(selected.key().item().equals(APPLE),"apple-only legal source must select apples, not wait for bread");
        h.assertTrue(NeedRequestData.get(server).request(old.id()).status()==NeedRequestStatus.CANCELLED
                &&NeedRequestData.get(server).request(old.id()).key().equals(old.key()),"retired bread retains exact original identity");
        SupplyFlowService.match(server,h.getLevel().getGameTime());var delivery=route(h,selected);
        f.npc().setNoAi(false);boolean[] delivered={false};long[] mealStart={0};
        h.succeedWhen(()->{
            var flows=SupplyFlowData.get(server);var life=NpcLifeData.get(server);
            if(!delivered[0]) {
                h.assertTrue(flows.route(delivery.id()).stage()==SupplyFlowData.Stage.DONE,"original actor physically delivers apples");
                h.assertTrue(SupplyFlowService.count(source,APPLE)==0&&SupplyFlowService.count(f.npc().getTransportInventory(),APPLE)==0
                        &&SupplyFlowService.count(f.pantry(),APPLE)==6,"source/cargo/pantry reconcile 0/0/6 before eating");
                life.ensureRegistered(f.npc(),h.getLevel().getGameTime());
                mealStart[0]=h.getLevel().getGameTime();life.setHunger(f.npc().getCharacterId(),500,mealStart[0]);
                delivered[0]=true;
            }
            var needs=life.needs(f.npc().getCharacterId());
            h.assertTrue(SupplyFlowService.count(f.pantry(),APPLE)==5,"exactly one delivered apple physically consumed");
            h.assertTrue(needs.lastAteAt()>=mealStart[0]&&needs.hunger()>=240&&needs.hunger()<270,
                    "500 hunger less apple restoration 260, allowing only bounded elapsed metabolism: "+needs.hunger());
            var completed=NpcBrainRuntime.data(server).history(f.npc().getUUID()).stream()
                    .filter(p->p.providerId().equals(LifePlanFactory.PROVIDER)&&p.status()==NpcPlanStatus.SUCCEEDED
                            &&"EAT".equals(p.steps().getFirst().params().get("operation"))).findFirst().orElseThrow();
            h.assertTrue("DONE".equals(LifeActivityData.get(server).settlement(completed.id()+":2")),"actual meal has completed receipt");
            h.assertTrue(NeedRequestData.get(server).request(selected.id()).delivered()==6
                    &&h.getLevel().getEntity(f.npc().getUUID())==f.npc(),"original demand and original physical actor delivered");
            f.npc().setNoAi(true);
        });
    }

    @GameTest(template="fieldwork_empty",batch="household_food",timeoutTicks=100)
    public static void emergencyAndUnsupportedFoodsDoNotFillOrdinaryBuffer(GameTestHelper h) {
        var f=fixture(h,6,0);f.pantry().setItem(0,new ItemStack(Items.HONEY_BOTTLE,6));
        f.pantry().setItem(1,new ItemStack(Items.CARROT,6));f.pantry().setItem(2,new ItemStack(Items.ROTTEN_FLESH,6));
        var plan=HouseholdData.get(h.getLevel().getServer()).provisionPlan(f.household());
        h.assertTrue(plan.inspect(f.pantry()).food()==0&&plan.inspect(f.pantry()).foodDeficit()==6,
                "honey vessels, emergency-only carrots and unsupported FOOD do not satisfy ordinary reserve");
        aggregate(h);h.assertTrue(only(h,f).requested()==6,"physical ineligible food never cancels ordinary demand");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="household_food",timeoutTicks=100)
    public static void reviewWithZeroCountersSurvivesFullPantry(GameTestHelper h) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();var original=only(h,f);
        var source=site(h,f,new BlockPos(2,1,9),new ItemStack(Items.APPLE,6),SubjectRef.household(f.household()),true);
        // Protocol seam: an unresolved persisted route is not settled merely because counters are zero.
        var review=new SupplyFlowData.Route(UUID.randomUUID(),original.id(),source.point().containerId(),original.key().destination(),
                SubjectRef.household(f.household()),1,null,null,SupplyFlowData.Stage.REVIEW,h.getLevel().getGameTime(),0,null,"unresolved persisted route");
        SupplyFlowData.get(server).route(review);f.pantry().setItem(0,new ItemStack(Items.BREAD,6));aggregate(h);
        h.assertTrue(only(h,f).id().equals(original.id())&&SupplyFlowData.get(server).route(review.id()).equals(review),
                "zero counters and a full pantry cannot erase REVIEW obligation");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="household_food",timeoutTicks=100)
    public static void unknownForeignUnloadedAndUnsupportedSourcesAreExcluded(GameTestHelper h) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();var original=only(h,f);var owner=SubjectRef.household(f.household());
        var unknown=site(h,f,new BlockPos(2,1,8),new ItemStack(Items.APPLE,6),owner,false);
        var foreign=site(h,f,new BlockPos(4,1,8),new ItemStack(Items.APPLE,6),SubjectRef.character(UUID.randomUUID()),true);
        var unsupported=site(h,f,new BlockPos(6,1,8),new ItemStack(Items.HONEY_BOTTLE,6),owner,true);
        unsupported.inventory().setItem(1,new ItemStack(Items.CARROT,6));
        unsupported.inventory().setItem(2,new ItemStack(Items.ROTTEN_FLESH,6));
        var far=h.absolutePos(new BlockPos(1000000,1,1000000));String dim=h.getLevel().dimension().location().toString();
        h.assertTrue(!h.getLevel().hasChunkAt(far),"negative control really is unloaded");
        UUID farId=ContainerIds.block("food-unloaded",dim,far);
        RightsStateData.get(server).ensureContext(farId,dim+":"+far.asLong(),owner,owner,AccessPolicy.PRIVATE);
        var flows=SupplyFlowData.get(server);flows.register(new FieldworkStorage(dim,far,farId,owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"unloaded-test"));flows.reveal(owner,farId);
        for(int i=0;i<6;i++){aggregate(h);SupplyFlowService.match(server,h.getLevel().getGameTime());}
        h.assertTrue(only(h,f).id().equals(original.id())&&only(h,f).status()==NeedRequestStatus.WAITING_EXTERNAL,
                "no eligible food keeps one explained waiting request");
        h.assertTrue(flows.routes().stream().noneMatch(r->r.need().equals(original.id()))&&!h.getLevel().hasChunkAt(far),
                "no false lease and no force-loading");
        h.assertTrue(SupplyFlowService.count(unknown.inventory(),APPLE)==6&&SupplyFlowService.count(foreign.inventory(),APPLE)==6,
                "unknown and foreign goods untouched");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="household_food",timeoutTicks=100)
    public static void heldBreadAndPreparedPickupNeverOpenAppleRemainder(GameTestHelper h) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();var original=only(h,f);source(h,f,Items.BREAD,3);
        SupplyFlowService.match(server,h.getLevel().getGameTime());var held=route(h,original);
        site(h,f,new BlockPos(2,1,9),new ItemStack(Items.APPLE,6),SubjectRef.household(f.household()),true);
        aggregate(h);h.assertTrue(only(h,f).id().equals(original.id())&&only(h,f).reserved()==3,
                "partial reservation does not open a second apple demand");
        var rights=RightsStateData.get(server);var bag=ContainerIds.npcCargo(f.npc().getCharacterId());
        var actor=SubjectRef.character(f.npc().getCharacterId());rights.ensureContext(bag,"npc:"+actor.id()+":cargo",actor,actor,AccessPolicy.PRIVATE);
        var pending=new SupplyFlowData.Pending(UUID.randomUUID(),true,bag,3,BREAD,rights.context(held.source()).revision(),rights.context(bag).revision(),h.getLevel().getGameTime());
        var prepared=held.claim(f.npc().getUUID(),f.npc().getCharacterId()).progress(SupplyFlowData.Stage.HELD,pending,h.getLevel().getGameTime(),0,"prepared but unsubmitted pickup");
        SupplyFlowData.get(server).route(prepared);f.pantry().setItem(0,new ItemStack(Items.APPLE,6));aggregate(h);
        h.assertTrue(only(h,f).id().equals(original.id())&&only(h,f).requested()==6
                        &&SupplyFlowData.get(server).route(held.id()).equals(prepared)&&TransferLedgerData.get(server).receipt(pending.id())==null,
                "pending intent retains exact old payload and cannot be cancelled by pantry top-up");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="household_food",timeoutTicks=100)
    public static void expiryCannotReopenAnUncertainZeroCounterRequest(GameTestHelper h) {
        var f=fixture(h,0,0);var server=h.getLevel().getServer();var owner=SubjectRef.household(f.household());var flows=SupplyFlowData.get(server);
        var home=HouseholdData.get(server).byId(f.household());var target=flows.point(ContainerIds.block("household_storage",home.dimension(),home.sharedStorage()));
        long now=h.getLevel().getGameTime();var original=SupplyFlowService.request(server,owner,"household:food",target,BREAD,6,now,now+2);
        HouseholdData.get(server).setProvisionPlan(new HouseholdProvisionPlan(f.household(),6,0,0));
        var source=site(h,f,new BlockPos(2,1,9),new ItemStack(Items.APPLE,6),owner,true);
        var pending=new SupplyFlowData.Pending(UUID.randomUUID(),true,ContainerIds.npcCargo(f.npc().getCharacterId()),1,BREAD,0,0,now);
        var prepared=new SupplyFlowData.Route(UUID.randomUUID(),original.id(),source.point().containerId(),target.containerId(),owner,1,
                f.npc().getUUID(),f.npc().getCharacterId(),SupplyFlowData.Stage.HELD,now,0,pending,"persisted uncertain pickup with zero counters");
        flows.route(prepared);
        h.runAtTickTime(5,()->{
            aggregate(h);SupplyFlowService.match(server,h.getLevel().getGameTime());
            h.assertTrue(only(h,f).id().equals(original.id())&&flows.route(prepared.id()).equals(prepared),
                    "expiry path obeys the same unresolved-obligation gate despite zero counters");h.succeed();
        });
    }

    @GameTest(template="fieldwork_empty",batch="household_food",timeoutTicks=100)
    public static void terminalHistoryWithReviewStillBlocksNewFoodRequest(GameTestHelper h) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();var original=only(h,f);var owner=SubjectRef.household(f.household());
        var source=site(h,f,new BlockPos(2,1,9),new ItemStack(Items.APPLE,6),owner,true);
        var review=new SupplyFlowData.Route(UUID.randomUUID(),original.id(),source.point().containerId(),original.key().destination(),owner,1,null,null,
                SupplyFlowData.Stage.REVIEW,h.getLevel().getGameTime(),0,null,"persisted terminal request with unresolved route");
        SupplyFlowData.get(server).route(review);
        NeedRequestData.get(server).transition(original.id(),NeedRequestStatus.CANCELLED,h.getLevel().getGameTime(),"protocol seam: terminal status is not settlement evidence");
        aggregate(h);h.assertTrue(active(h,f).isEmpty()&&SupplyFlowData.get(server).route(review.id()).equals(review),
                "historical unresolved custody blocks fresh apple demand even though request is terminal");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="household_food",timeoutTicks=100)
    public static void fixedFoodContractCannotBeReplacedOrResized(GameTestHelper h) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();var original=only(h,f);var owner=SubjectRef.household(f.household());
        var target=SupplyFlowData.get(server).point(original.key().destination());
        var order=ProductionOrder.open(UUID.randomUUID(),owner,owner,"sagadyssey:food_contract_fixture",
                List.of(new ProductionMaterial(new ItemFingerprint("minecraft:wheat",0),18)),new ProductionMaterial(BREAD,6),80,UUID.randomUUID(),
                target.dimension(),target.position(),target.containerId(),target.dimension(),target.position(),h.getLevel().getGameTime(),"fixed contract fixture, no fabricated production");
        ProductionOrderData.get(server).put(order);SupplyFlowData.get(server).production(original.id(),order.id());
        source(h,f,Items.APPLE,6);f.pantry().setItem(0,new ItemStack(Items.APPLE,6));aggregate(h);
        h.assertTrue(only(h,f).equals(original)&&ProductionOrderData.get(server).byId(order.id()).orElseThrow().equals(order),
                "fixed inputs/output and original demand are not rewritten by alternative supply or a full pantry");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="household_food",timeoutTicks=100)
    public static void historicalMultipleActiveRequestsBlockNewMatching(GameTestHelper h) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();var original=only(h,f);var flows=SupplyFlowData.get(server);
        var other=SupplyFlowService.request(server,SubjectRef.household(f.household()),"household:food",flows.point(original.key().destination()),APPLE,6,
                h.getLevel().getGameTime(),h.getLevel().getGameTime()+24000);
        source(h,f,Items.BREAD,6);site(h,f,new BlockPos(2,1,9),new ItemStack(Items.APPLE,6),SubjectRef.household(f.household()),true);
        aggregate(h);SupplyFlowService.match(server,h.getLevel().getGameTime());
        h.assertTrue(active(h,f).size()==2&&active(h,f).stream().allMatch(r->r.status()==NeedRequestStatus.UNREACHABLE),
                "inconsistent old multi-request state is preserved with an explanation, not auto-merged");
        h.assertTrue(flows.routes().stream().noneMatch(r->r.need().equals(original.id())||r.need().equals(other.id())),
                "independent matching cannot make new commitments for ambiguous old requests");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="household_food",timeoutTicks=1800)
    public static void carriedBreadSettlesBeforeAppleRemainderAndRetainsHistory(GameTestHelper h) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();var original=only(h,f);var breadSource=source(h,f,Items.BREAD,3);
        SupplyFlowService.match(server,h.getLevel().getGameTime());var shipment=route(h,original);full(f.pantry());
        var apples=site(h,f,new BlockPos(2,1,9),new ItemStack(Items.APPLE,6),SubjectRef.household(f.household()),true);
        f.npc().setNoAi(false);boolean[] carrying={false};
        h.succeedWhen(()->{
            var flows=SupplyFlowData.get(server);var data=NeedRequestData.get(server);
            if(!carrying[0]) {
                h.assertTrue(SupplyFlowService.count(f.npc().getTransportInventory(),BREAD)==3,"original actor really carries bread");
                aggregate(h);h.assertTrue(only(h,f).id().equals(original.id())&&only(h,f).inTransit()==3,
                        "no apple request for unmatched remainder while bread is carried");
                f.pantry().setItem(0,ItemStack.EMPTY);carrying[0]=true;
            }
            h.assertTrue(flows.route(shipment.id()).stage()==SupplyFlowData.Stage.DONE,"original physical delivery finishes");
            aggregate(h);var next=only(h,f);var past=data.request(original.id());
            h.assertTrue(next.key().item().equals(APPLE)&&next.requested()==3&&!next.id().equals(original.id()),"only remaining actual deficit gets new apple identity");
            h.assertTrue(past.status()==NeedRequestStatus.CANCELLED&&past.delivered()==3&&past.key().equals(original.key()),"partial old delivery history retained");
            h.assertTrue(SupplyFlowService.count(breadSource,BREAD)==0&&SupplyFlowService.count(f.npc().getTransportInventory(),BREAD)==0
                    &&SupplyFlowService.count(f.pantry(),BREAD)==3&&SupplyFlowService.count(apples.inventory(),APPLE)==6,"physical old goods reconcile; new apples have not teleported");
            f.npc().setNoAi(true);
        });
    }

    @GameTest(template="fieldwork_empty",batch="household_food_reload",timeoutTicks=200)
    public static void stableSelectionAndWaitingSurviveActualLedgerReload(GameTestHelper h) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();var original=only(h,f);var owner=SubjectRef.household(f.household());
        var apple=site(h,f,new BlockPos(2,1,9),new ItemStack(Items.APPLE,6),owner,true);
        var bread=site(h,f,new BlockPos(4,1,9),new ItemStack(Items.BREAD,6),owner,true);
        for(int i=0;i<5;i++)aggregate(h);
        h.assertTrue(only(h,f).id().equals(original.id()),"legal current bread wins over available apples");
        bread.inventory().clearContent();aggregate(h);var selected=only(h,f);
        h.assertTrue(selected.key().item().equals(APPLE),"unavailable bread changes once to legal apples");
        bread.inventory().setItem(0,new ItemStack(Items.BREAD,6));
        for(int i=0;i<5;i++)aggregate(h);
        h.assertTrue(only(h,f).id().equals(selected.id()),"return of bread cannot churn a legal apple request");
        bread.inventory().clearContent();apple.inventory().clearContent();aggregate(h);
        var waiting=only(h,f);h.assertTrue(waiting.id().equals(selected.id())&&waiting.status()==NeedRequestStatus.WAITING_EXTERNAL,"zero supply waits on same identity");
        try {
            var storage=server.overworld().getDataStorage();storage.save();net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            int version=net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion();
            storage.set(NeedRequestData.NAME,NeedRequestData.load(storage.readTagFromDisk(NeedRequestData.NAME,null,version).getCompound("data"),server.registryAccess()));
            storage.set(SupplyFlowData.NAME,SupplyFlowData.load(storage.readTagFromDisk(SupplyFlowData.NAME,null,version).getCompound("data"),server.registryAccess()));
        }catch(Exception e){throw new IllegalStateException("actual food ledger reload",e);}
        for(int i=0;i<5;i++)aggregate(h);
        h.assertTrue(only(h,f).equals(waiting),"actual disk reload and repeated no-supply scheduling retain one unchanged request");
        apple.inventory().setItem(0,new ItemStack(Items.APPLE,6));aggregate(h);
        h.assertTrue(only(h,f).id().equals(selected.id())&&only(h,f).status()==NeedRequestStatus.OPEN,"same item resumes without replacement after reload");
        SupplyFlowService.match(server,h.getLevel().getGameTime());
        h.assertTrue(only(h,f).reserved()==6&&SupplyFlowData.get(server).routes().stream().filter(r->r.need().equals(selected.id())).count()==1,
                "reloaded actual ledgers make exactly one reservation");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="household_food",timeoutTicks=100)
    public static void selectedStockDisappearsBeforeReservationWithoutCreatingGoods(GameTestHelper h) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();var stock=source(h,f,Items.APPLE,6);aggregate(h);var selected=only(h,f);
        h.assertTrue(selected.key().item().equals(APPLE),"selection observed actual apple stock");stock.clearContent();
        SupplyFlowService.match(server,h.getLevel().getGameTime());
        h.assertTrue(only(h,f).id().equals(selected.id())&&only(h,f).reserved()==0
                        &&SupplyFlowData.get(server).routes().stream().noneMatch(r->r.need().equals(selected.id())),"selection cannot reserve removed stock");
        h.assertTrue(SupplyFlowService.count(f.pantry(),APPLE)==0&&SupplyFlowService.count(f.npc().getTransportInventory(),APPLE)==0,"no copied apples");h.succeed();
    }

    private static void changedBeforePickup(GameTestHelper h,boolean revoke) {
        var f=fixture(h,6,0);var server=h.getLevel().getServer();var owner=SubjectRef.character(UUID.randomUUID());
        var stock=site(h,f,new BlockPos(2,1,9),new ItemStack(Items.APPLE,3),owner,true);
        var rights=RightsStateData.get(server);UUID grant=UUID.randomUUID();
        rights.grant(new RightGrant(grant,stock.point().containerId(),SubjectRef.household(f.household()),Set.of(AssetRight.TRANSFER),
                "revocable food transfer test",h.getLevel().getGameTime(),null,true));
        aggregate(h);var selected=only(h,f);h.assertTrue(selected.key().item().equals(APPLE),"granted real stock can be selected");
        SupplyFlowService.match(server,h.getLevel().getGameTime());var shipment=route(h,selected);
        h.assertTrue(only(h,f).reserved()==3,"real lease created before source changes");
        if(revoke)rights.revoke(grant);else stock.inventory().clearContent();
        f.npc().setNoAi(false);
        h.runAtTickTime(300,()->{
            var row=SupplyFlowData.get(server).route(shipment.id());
            h.assertTrue(row.stage()==SupplyFlowData.Stage.HELD&&row.detail().contains("权限"),"original pickup executor rejects changed source: "+row.detail());
            h.assertTrue(NeedRequestData.get(server).request(selected.id()).delivered()==0
                    &&SupplyFlowService.count(stock.inventory(),APPLE)==(revoke?3:0)
                    &&SupplyFlowService.count(f.npc().getTransportInventory(),APPLE)==0&&SupplyFlowService.count(f.pantry(),APPLE)==0,
                    "failed pickup cannot fabricate cargo or delivery");
            h.assertTrue(only(h,f).id().equals(selected.id()),"failed original obligation does not open a second demand");f.npc().setNoAi(true);h.succeed();
        });
    }
    @GameTest(template="fieldwork_empty",batch="household_food",timeoutTicks=400)
    public static void revokedPermissionIsRejectedAtRealPickup(GameTestHelper h) {changedBeforePickup(h,true);}
    @GameTest(template="fieldwork_empty",batch="household_food",timeoutTicks=400)
    public static void removedStockIsRejectedAtRealPickup(GameTestHelper h) {changedBeforePickup(h,false);}
}
