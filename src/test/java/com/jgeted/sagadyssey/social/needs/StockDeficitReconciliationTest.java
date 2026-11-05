package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class StockDeficitReconciliationTest {
    private final NeedRequestData data = new NeedRequestData();
    private NeedRequest open(int quantity) {
        return data.openOrUpdate(new NeedRequest.Key(UUID.randomUUID(), "workshop:buffer",
                new ItemFingerprint("minecraft:coal", 0), UUID.randomUUID(), 0, 1000), quantity, 32, 10);
    }
    private NeedRequest ack(NeedRequest r, NeedRequestData.Action action, int quantity, long now) {
        return data.acknowledge(UUID.randomUUID(), r.id(), action, quantity, now);
    }
    @Test void externalPartialTopUpShrinksUnmatchedDemandInPlace() {
        var original = open(10);
        var revised = data.reconcileStockDeficit(original.id(), 3, 11);
        assertEquals(original.id(), revised.id());
        assertEquals(10, revised.originalRequested());
        assertEquals(3, revised.remaining());
        assertEquals(0, revised.delivered());
        assertEquals(1, data.requests().size());
    }
    @Test void externalFullTopUpCancelsOutstandingDemandWithoutDeliveryReceipt() {
        var r = open(10);
        data.transition(r.id(), NeedRequestStatus.WAITING_EXTERNAL, 11, "no supply");
        r = data.reconcileStockDeficit(r.id(), 0, 12);
        assertEquals(NeedRequestStatus.CANCELLED, r.status());
        assertEquals(0, r.delivered());
        assertTrue(data.save(new CompoundTag(), null).getList("Receipts", 10).isEmpty());
    }
    @Test void heldAndCarriedGoodsRemainCommittedAfterTopUp() {
        var r = open(10);
        r = ack(r, NeedRequestData.Action.RESERVE, 5, 11);
        r = ack(r, NeedRequestData.Action.DISPATCH, 3, 12);
        r = data.reconcileStockDeficit(r.id(), 0, 13);
        assertEquals(2, r.reserved()); assertEquals(3, r.inTransit());
        assertEquals(5, r.requested()); assertEquals(0, r.remaining());
        r = ack(r, NeedRequestData.Action.DELIVER, 3, 14);
        r = ack(r, NeedRequestData.Action.DISPATCH, 2, 15);
        r = ack(r, NeedRequestData.Action.DELIVER, 2, 16);
        assertEquals(NeedRequestStatus.DELIVERED, r.status());
    }
    @Test void previousDeliveryIsHistoricalAndNotCountedTwiceInCurrentDeficit() {
        var r = open(10);
        r = ack(r, NeedRequestData.Action.RESERVE, 4, 11);
        r = ack(r, NeedRequestData.Action.DISPATCH, 4, 12);
        r = ack(r, NeedRequestData.Action.DELIVER, 4, 13);
        r = data.reconcileStockDeficit(r.id(), 2, 14);
        assertEquals(6, r.requested()); assertEquals(2, r.remaining());
        var saved = NeedRequestData.load(data.save(new CompoundTag(), null), null);
        assertEquals(r, saved.request(r.id()));
        r = saved.reconcileStockDeficit(r.id(), 0, 15);
        assertEquals(NeedRequestStatus.CANCELLED, r.status());
        assertEquals(4, r.delivered()); assertEquals(10, r.originalRequested());
    }
    @Test void largerDeficitGrowsSameRequestAndRepeatedMeasurementIsNoOp() {
        var r = open(3);
        r = data.reconcileStockDeficit(r.id(), 8, 11);
        assertEquals(8, r.remaining());
        assertSame(r, data.reconcileStockDeficit(r.id(), 8, 12));
    }
    @Test void settledDemandDoesNotReopen() {
        var r = open(2);
        r = ack(r, NeedRequestData.Action.RESERVE, 2, 11);
        r = ack(r, NeedRequestData.Action.DISPATCH, 2, 12);
        r = ack(r, NeedRequestData.Action.DELIVER, 2, 13);
        assertSame(r, data.reconcileStockDeficit(r.id(), 8, 14));
    }
    @Test void invalidInputAndOverflowLeaveLedgerUnchanged() {
        var r = open(3);
        r = ack(r, NeedRequestData.Action.RESERVE, 1, 11);
        r = ack(r, NeedRequestData.Action.DISPATCH, 1, 12);
        r = ack(r, NeedRequestData.Action.DELIVER, 1, 13);
        final var before = r;
        assertThrows(IllegalArgumentException.class, () -> data.reconcileStockDeficit(before.id(), -1, 14));
        assertThrows(IllegalArgumentException.class, () -> data.reconcileStockDeficit(before.id(), 2, 12));
        assertThrows(ArithmeticException.class, () -> data.reconcileStockDeficit(before.id(), Integer.MAX_VALUE, 14));
        assertEquals(before, data.request(before.id()));
    }
}
