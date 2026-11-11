package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Component evidence only: no claim of physical NPC work or a complete world restart. */
class SupplyExistingStockRecoveryTest {
    @Test void reserveRealFiniteStockOnceWithoutRenewingIdentityOrBudget() {
        var f=new Fixture();var original=f.needs.request(f.need.id());
        assertTrue(f.publish(2));assertFalse(f.publish(2));
        var after=f.needs.request(f.need.id());
        assertEquals(original.id(),after.id());assertEquals(original.createdAt(),after.createdAt());
        assertEquals(original.key(),after.key());assertEquals(32,after.attempts());assertEquals(32,after.maxAttempts());
        assertEquals(1,after.reserved());assertEquals(1,after.remaining());assertEquals(0,after.delivered());
        assertEquals(1,f.rights.reservations().size());assertEquals(1,f.flows.routes().size());assertEquals(1,f.logistics.orders().size());
        assertEquals(f.principal,f.rights.reservations().iterator().next().reservedFor());
    }

    @Test void missingStockAndFailedPublisherLeaveNoNewEffects() {
        var f=new Fixture();var before=f.needs.save(new CompoundTag(),null);
        assertFalse(f.publish(0));assertEquals(before,f.needs.save(new CompoundTag(),null));
        assertTrue(f.rights.reservations().isEmpty());assertTrue(f.logistics.orders().isEmpty());
        for(int i=0;i<SupplyFlowData.MAX_ENTRIES;i++) f.flows.route(new SupplyFlowData.Route(UUID.randomUUID(),UUID.randomUUID(),
                f.source,f.destination,f.principal,1,null,null,SupplyFlowData.Stage.CANCELLED,1,0,null,"unrelated historical capacity fixture"));
        assertThrows(IllegalStateException.class,()->f.publish(2));
        assertEquals(before,f.needs.save(new CompoundTag(),null));assertTrue(f.rights.reservations().isEmpty());
        assertTrue(f.logistics.orders().isEmpty());assertNull(f.flows.routeOrNull(f.route.id()));
    }

    @Test void changedQuantityOrSourceNeverConsumesAnotherLease() {
        var f=new Fixture();assertTrue(f.publish(2));var before=f.needs.save(new CompoundTag(),null);
        var changed=f.route(f.route.id(),2);
        assertThrows(IllegalStateException.class,()->SupplyFlowService.reserveExistingStockRoute(f.rights,f.needs,f.flows,
                f.logistics,f.lease(changed),changed,2,600));
        UUID foreignSource=UUID.randomUUID();
        f.flows.register(new FieldworkStorage("minecraft:overworld",new BlockPos(2,0,0),foreignSource,f.principal,
                AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"different source"));
        var alien=new SupplyFlowData.Route(f.route.id(),f.need.id(),foreignSource,f.destination,f.principal,1,null,null,
                SupplyFlowData.Stage.HELD,600,0,null,"changed source");
        assertThrows(IllegalStateException.class,()->SupplyFlowService.reserveExistingStockRoute(f.rights,f.needs,f.flows,
                f.logistics,f.lease(alien),alien,2,600));
        assertEquals(before,f.needs.save(new CompoundTag(),null));assertEquals(1,f.rights.reservations().size());
    }

    @Test void cancelledRouteReplayAfterNbtRoundTripDoesNotRecreateReleasedStock() {
        var f=new Fixture();assertTrue(f.publish(2));
        f.needs.acknowledge(SupplyFlowService.receipt(f.route.id(),"release"),f.need.id(),NeedRequestData.Action.RELEASE_RESERVED,1,601);
        f.rights.releaseReservations(f.route.token());
        f.flows.route(f.route.progress(SupplyFlowData.Stage.CANCELLED,null,601,1,"real reservation release component"));
        assertTrue(f.logistics.cancelManagedOrder(f.route.id(),f.need.id(),601));
        var needs=NeedRequestData.load(f.needs.save(new CompoundTag(),null),null);
        var flows=SupplyFlowData.load(f.flows.save(new CompoundTag(),null),null);
        assertFalse(SupplyFlowService.reserveExistingStockRoute(f.rights,needs,flows,f.logistics,f.lease(f.route),f.route,2,602));
        assertTrue(f.rights.reservations().isEmpty());assertEquals(0,needs.request(f.need.id()).reserved());
        assertEquals(32,needs.request(f.need.id()).attempts());assertEquals(SupplyFlowData.Stage.CANCELLED,flows.route(f.route.id()).stage());
    }

    @Test void orphanReceiptOrManagedOrderRequiresReviewWithoutNewEffects() {
        var f=new Fixture();f.needs.acknowledgeExistingStockReserveAndPublish(SupplyFlowService.receipt(f.route.id(),"reserve"),
                f.need.id(),1,600,()->{});
        assertThrows(IllegalStateException.class,()->f.publish(2));assertTrue(f.rights.reservations().isEmpty());
        var g=new Fixture();g.logistics.createManagedOrder(g.route.id(),g.source,g.destination,g.item,1,g.principal.id(),g.need.id(),600);
        assertThrows(IllegalStateException.class,()->g.publish(2));assertTrue(g.rights.reservations().isEmpty());
        assertEquals(0,g.needs.request(g.need.id()).reserved());
    }

    @Test void heldReviewAndPendingRouteNeverPermitParallelRecovery() {
        for(var state:List.of(SupplyFlowData.Stage.HELD,SupplyFlowData.Stage.REVIEW,SupplyFlowData.Stage.CARRYING)) {
            var f=new Fixture();var old=f.route(UUID.randomUUID(),1).claim(UUID.randomUUID(),UUID.randomUUID())
                    .progress(state,null,500,0,"unresolved original duty");
            f.flows.route(old);var before=f.needs.save(new CompoundTag(),null);
            assertFalse(f.publish(2));assertEquals(before,f.needs.save(new CompoundTag(),null));assertTrue(f.rights.reservations().isEmpty());
        }
    }

    @Test void expiryCancellationReviewAndGeneralBudgetRemainClosed() {
        var f=new Fixture();var r=f.needs.request(f.need.id());
        assertThrows(IllegalStateException.class,()->r.reserve(1,600));
        assertFalse(r.existingStockRecoveryEligible(r.key().deadline()));
        assertFalse(r.transition(NeedRequestStatus.CANCELLED,600,"cancelled").existingStockRecoveryEligible(601));
        assertFalse(r.transition(NeedRequestStatus.OPEN,600,"inspect").transition(NeedRequestStatus.UNREACHABLE,601,
                "PRODUCTION_REVIEW: evidence conflict").existingStockRecoveryEligible(602));
        var generic=NeedRequest.open(new NeedRequest.Key(UUID.randomUUID(),"generic",f.item,UUID.randomUUID(),1,72001),2,32,1);
        for(int i=2;i<=33;i++)generic=generic.failedAttempt(i,"finite");
        assertFalse(generic.existingStockRecoveryEligible(600));
        var finalGeneric=generic;assertThrows(IllegalStateException.class,()->finalGeneric.reserveExistingStock(1,600));
    }

    @Test void naturalCarrotRetainsItsSeparateOnceOnlyEmergencyEntry() {
        var f=new Fixture();UUID home=UUID.randomUUID();
        var carrot=NeedRequest.open(new NeedRequest.Key(home,"HOUSEHOLD:household:natural-diet:"+home,
                new ItemFingerprint("minecraft:carrot",0),f.destination,1,72001),2,32,1);
        for(int i=2;i<=33;i++)carrot=carrot.failedAttempt(i,"finite");
        assertFalse(carrot.existingStockRecoveryEligible(600));
        assertEquals(1,carrot.reserveNaturalFoodEmergency(1,600).reserved());
        var ordinary=NeedRequest.open(new NeedRequest.Key(home,"HOUSEHOLD:household:food",
                new ItemFingerprint("minecraft:bread",0),f.destination,1,72001),2,32,1);
        for(int i=2;i<=33;i++)ordinary=ordinary.failedAttempt(i,"finite");
        assertFalse(ordinary.existingStockRecoveryEligible(600));
    }

    @Test void partialReceiptRetainsRemainderAndNoFalseCompletion() {
        var f=new Fixture();UUID reserve=UUID.randomUUID();
        f.needs.acknowledgeExistingStockReserveAndPublish(reserve,f.need.id(),1,600,()->{});
        f.needs.acknowledge(UUID.randomUUID(),f.need.id(),NeedRequestData.Action.DISPATCH,1,601);
        f.needs.acknowledge(UUID.randomUUID(),f.need.id(),NeedRequestData.Action.DELIVER,1,602);
        var n=f.needs.request(f.need.id());assertEquals(1,n.delivered());assertEquals(1,n.remaining());
        assertEquals(NeedRequestStatus.OPEN,n.status());assertTrue(n.existingStockRecoveryEligible(603));
        assertEquals(32,n.attempts());assertEquals(72001,n.key().deadline());
        assertThrows(IllegalStateException.class,()->f.needs.acknowledgeExistingStockReserveAndPublish(UUID.randomUUID(),n.id(),2,603,()->fail("must validate first")));
    }

    @Test void materialRecoveryRequiresExactLiveOriginalOrderAndMaterial() {
        var f=new Fixture();var r=f.needs.request(f.need.id());var scope=f.flows.demand(r.id());
        assertTrue(SupplyFlowService.materialRecoveryMatches(r,scope,f.order,f.material,null,600));
        assertFalse(SupplyFlowService.materialRecoveryMatches(r,scope,f.order.transition(ProductionOrderStatus.CANCELLED,40,"cancel"),f.material,null,600));
        assertFalse(SupplyFlowService.materialRecoveryMatches(r,scope,f.order,f.material.cancel(40,"old material cancelled"),null,600));
        var foreign=ProductionMaterialRequest.open(f.order.id(),UUID.randomUUID(),f.material.material(),1,"wrong facility");
        assertFalse(SupplyFlowService.materialRecoveryMatches(r,scope,f.order,foreign,null,600));
        var started=f.order.claim(UUID.randomUUID(),40,"worker");
        var batch=new ProductionBatch(started.batchId(),started.recipeId(),started.assignedWorkerId(),started.outputOwner(),
                started.targetContainerId(),started.inputs(),started.output(),80,40,40,ProductionBatchStatus.RESERVED,"already held");
        assertFalse(SupplyFlowService.materialRecoveryMatches(r,scope,started,f.material,batch,600));
        var complete=complete(started);assertFalse(SupplyFlowService.materialRecoveryMatches(r,scope,complete,f.material,null,600));
    }

    @Test void completedSupplierRequiresSameParentOutputWorkerBatchAndDeliveredChild() {
        var f=new Fixture();var home=UUID.randomUUID();var item=new ItemFingerprint("minecraft:white_bed",0);
        var parent=NeedRequest.open(new NeedRequest.Key(home,"HOUSEHOLD:household:bed-policy",item,UUID.randomUUID(),1,72001),2,32,1);
        var child=NeedRequest.open(new NeedRequest.Key(f.principal.id(),"CHARACTER:local-supplier:"+parent.id()+":minecraft:white_bed",
                item,f.destination,1,72001),1,32,1).reserve(1,2).dispatch(1,3).deliver(1,4);
        var order=complete(f.order.claim(UUID.randomUUID(),40,"actual worker component"));
        var batch=new ProductionBatch(order.batchId(),order.recipeId(),order.assignedWorkerId(),order.outputOwner(),order.targetContainerId(),
                order.inputs(),order.output(),order.requiredWorkTicks(),40,44,ProductionBatchStatus.COMPLETED,"component completed batch");
        assertTrue(SupplyFlowService.completedSupplierRecoveryMatches(parent,child,order,batch));
        assertFalse(SupplyFlowService.completedSupplierRecoveryMatches(parent,child,order,null));
        assertFalse(SupplyFlowService.completedSupplierRecoveryMatches(parent,child,f.order,batch));
        assertFalse(SupplyFlowService.completedSupplierRecoveryMatches(f.need,child,order,batch));
        var other=new ProductionBatch(batch.id(),batch.recipeId(),UUID.randomUUID(),batch.outputOwner(),batch.containerId(),batch.inputs(),
                batch.output(),batch.requiredWorkTicks(),40,44,ProductionBatchStatus.COMPLETED,"wrong worker");
        assertFalse(SupplyFlowService.completedSupplierRecoveryMatches(parent,child,order,other));
    }

    private static ProductionOrder complete(ProductionOrder order) {
        return order.transition(ProductionOrderStatus.RESERVED,41,"reserved").transition(ProductionOrderStatus.IN_PROGRESS,42,"work")
                .transition(ProductionOrderStatus.SETTLING,43,"settle").transition(ProductionOrderStatus.COMPLETED,44,"complete");
    }
    private static final class Fixture {
        final RightsStateData rights=new RightsStateData(); final NeedRequestData needs=new NeedRequestData();
        final SupplyFlowData flows=new SupplyFlowData(); final LogisticsData logistics=new LogisticsData();
        final UUID source=UUID.randomUUID(),destination=UUID.randomUUID();
        final SubjectRef principal=SubjectRef.character(UUID.randomUUID());
        final ItemFingerprint item=new ItemFingerprint("minecraft:white_wool",0);
        final ProductionOrder order=ProductionOrder.open(UUID.randomUUID(),principal,principal,"test:bed",List.of(new ProductionMaterial(item,2)),
                new ProductionMaterial(new ItemFingerprint("minecraft:white_bed",0),1),80,UUID.randomUUID(),"minecraft:overworld",BlockPos.ZERO,
                destination,"minecraft:overworld",new BlockPos(1,0,0),1,"original component order");
        final ProductionMaterialRequest material=ProductionMaterialRequest.open(order.id(),destination,new ProductionMaterial(item,2),1,"missing");
        final NeedRequest need;final SupplyFlowData.Route route;
        Fixture() {
            need=needs.openOrUpdate(new NeedRequest.Key(principal.id(),"CHARACTER:production:"+order.id(),item,destination,1,72001),2,32,1);
            for(int i=2;i<=33;i++)needs.failedAttempt(need.id(),i,"original finite budget");
            needs.transition(need.id(),NeedRequestStatus.WAITING_EXTERNAL,34,"missing lawful source");
            flows.register(new FieldworkStorage("minecraft:overworld",BlockPos.ZERO,source,principal,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"source"));
            flows.register(new FieldworkStorage("minecraft:overworld",new BlockPos(1,0,0),destination,principal,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"target"));
            flows.demand(need.id(),principal,destination);route=route(UUID.randomUUID(),1);
        }
        SupplyFlowData.Route route(UUID id,int quantity) {return new SupplyFlowData.Route(id,need.id(),source,destination,principal,quantity,
                null,null,SupplyFlowData.Stage.HELD,600,0,null,"component recovery");}
        AssetReservation lease(SupplyFlowData.Route r) {return new AssetReservation(SupplyFlowService.receipt(r.id(),"lease"),r.source(),item,
                r.quantity(),principal,r.token(),"existing lawful stock",600,1800);}
        boolean publish(int physical) {return SupplyFlowService.reserveExistingStockRoute(rights,needs,flows,logistics,lease(route),route,physical,600);}
    }
}
