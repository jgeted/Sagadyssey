package com.jgeted.sagadyssey.social.production;

import com.jgeted.sagadyssey.worldsim.rights.AssetReservation;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProductionCommitmentServiceTest {
    @Test
    void missingInputExpiryClosesOrderAndRequestWithoutCreatingABatch() {
        Fixture f = fixture();
        f.orders.put(f.claimed.block(new ProductionOrderBlocker("MISSING_INPUT", "缺料", 200L), 12L, "缺料"));

        close(f, 200L);

        assertEquals(ProductionOrderStatus.CANCELLED, f.orders.byId(f.claimed.id()).orElseThrow().status());
        assertTrue(f.requests.openRequests().isEmpty());
        assertTrue(f.batches.batches().isEmpty());
        assertEquals(ProductionMaterialRequest.Status.CANCELLED, f.requests.forOrder(f.claimed.id()).getFirst().status());
    }

    @Test
    void incompleteBatchAndItsMaterialReservationAreReleased() {
        Fixture f = fixture();
        ProductionBatch batch = batch(f.claimed);
        f.batches.put(batch);
        reserve(f, batch.id());

        close(f, 200L);

        assertEquals(ProductionBatchStatus.CANCELLED, f.batches.batch(batch.id()).status());
        assertTrue(f.rights.reservations().isEmpty());
        assertEquals(batch.inputs(), f.batches.batch(batch.id()).inputs());
    }

    @Test
    void missingBatchDoesNotHideAnOrphanMaterialReservation() {
        Fixture f = fixture();
        reserve(f, f.claimed.batchId());

        close(f, 200L);

        assertTrue(f.rights.reservations().isEmpty());
    }

    @Test
    void completedBatchIsNeverCancelledAndBlockedSettlementIsReconciled() {
        Fixture f = fixture();
        ProductionOrder settling = advanceToSettlement(f);
        f.orders.put(settling.block(new ProductionOrderBlocker("OUTPUT_FULL", "等待", 40L), 16L, "等待"));
        ProductionBatch completed = batch(f.claimed).finish(ProductionBatchStatus.COMPLETED, 17L, "完成");
        f.batches.put(completed);
        reserve(f, completed.id());

        close(f, 200L);

        assertEquals(ProductionOrderStatus.COMPLETED, f.orders.byId(f.claimed.id()).orElseThrow().status());
        assertEquals(completed, f.batches.batch(completed.id()));
        assertTrue(f.rights.reservations().isEmpty());
    }

    @Test
    void repeatedClosureAndOrderRequestSaveReloadAreIdempotent() {
        Fixture f = fixture();
        close(f, 200L);
        ProductionOrder first = f.orders.byId(f.claimed.id()).orElseThrow();
        ProductionMaterialRequest request = f.requests.forOrder(first.id()).getFirst();
        ProductionOrderData loadedOrders = ProductionOrderData.load(f.orders.save(new CompoundTag(), null), null);
        ProductionMaterialRequestData loadedRequests = ProductionMaterialRequestData.load(f.requests.save(new CompoundTag(), null), null);

        ProductionCommitmentService.close(first.id(), loadedOrders, f.batches, loadedRequests,
                f.rights, 300L, "重载再次收尾");

        assertEquals(first, loadedOrders.byId(first.id()).orElseThrow());
        assertEquals(request, loadedRequests.forOrder(first.id()).getFirst());
        assertTrue(loadedRequests.openRequests().isEmpty());
    }

    @Test
    void closureDoesNotTouchAnotherOrdersRequestOrReservations() {
        Fixture f = fixture();
        UUID otherOrder = UUID.randomUUID();
        f.requests.openOrGet(otherOrder, f.claimed.targetContainerId(), f.claimed.inputs().getFirst(), 12L, "另一订单");
        UUID foreignBatch = UUID.randomUUID();
        reserve(f, foreignBatch);

        close(f, 200L);

        assertEquals(otherOrder, f.requests.openRequests().getFirst().orderId());
        assertEquals("production:" + foreignBatch, f.rights.reservations().iterator().next().token());
    }

    private static Fixture fixture() {
        ProductionOrderData orders = new ProductionOrderData();
        ProductionOrder open = ProductionOrderTest.open(10L);
        orders.put(open);
        ProductionOrder claimed = open.claim(UUID.randomUUID(), 11L, "认领");
        orders.put(claimed);
        ProductionMaterialRequestData requests = new ProductionMaterialRequestData();
        requests.openOrGet(claimed.id(), claimed.targetContainerId(), claimed.inputs().getFirst(), 12L, "缺料");
        return new Fixture(orders, new ProductionBatchData(), requests, new RightsStateData(), claimed);
    }

    private static ProductionBatch batch(ProductionOrder order) {
        return new ProductionBatch(order.batchId(), order.recipeId(), order.assignedWorkerId(),
                order.outputOwner(), order.targetContainerId(), order.inputs(), order.output(),
                order.requiredWorkTicks(), 12L, 0L, ProductionBatchStatus.RESERVED, "");
    }

    private static void reserve(Fixture f, UUID batchId) {
        var input = f.claimed.inputs().getFirst();
        assertTrue(f.rights.reserve(new AssetReservation(UUID.randomUUID(),
                f.claimed.targetContainerId(), input.fingerprint(), input.quantity(),
                f.claimed.outputOwner(), "production:" + batchId, "生产", 12L, 1000L), 30, 12L));
    }

    private static ProductionOrder advanceToSettlement(Fixture f) {
        ProductionOrder order = f.claimed;
        long now = 12L;
        for (var status : new ProductionOrderStatus[]{ProductionOrderStatus.RESERVED,
                ProductionOrderStatus.IN_PROGRESS, ProductionOrderStatus.SETTLING}) {
            order = order.transition(status, now++, "推进");
            f.orders.put(order);
        }
        return order;
    }

    private static void close(Fixture f, long now) {
        ProductionCommitmentService.close(f.claimed.id(), f.orders, f.batches, f.requests, f.rights, now, "计划已到期");
    }

    private record Fixture(ProductionOrderData orders, ProductionBatchData batches,
                           ProductionMaterialRequestData requests, RightsStateData rights,
                           ProductionOrder claimed) {}
}
