package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NeedRequestDataTest {
    private NeedRequest.Key key(String purpose) {
        return new NeedRequest.Key(UUID.randomUUID(), purpose,
                new ItemFingerprint("minecraft:coal", 17), UUID.randomUUID(), 0, 1_000);
    }
    private NeedRequest open(NeedRequestData data, String purpose, int count) {
        return data.openOrUpdate(key(purpose), count, 3, 10);
    }
    private NeedRequest ack(NeedRequestData data, NeedRequest r, NeedRequestData.Action action, int count, long now) {
        return data.acknowledge(UUID.randomUUID(), r.id(), action, count, now);
    }

    @Test void identicalIdentityUpdatesQuantityWithoutCreatingAnotherRequest() {
        var data = new NeedRequestData(); var r = open(data, "forge", 3);
        var changed = data.openOrUpdate(r.key(), 5, 3, 11);
        assertEquals(r.id(), changed.id()); assertEquals(5, changed.remaining());
        assertEquals(3, changed.originalRequested());
        assertEquals(1, data.requests().size());
        assertEquals(1, NeedRequestData.load(data.save(new CompoundTag(), null), null).requests().size());
    }

    @Test void partialFulfillmentConservesQuantitiesAndSurvivesReload() {
        var data = new NeedRequestData(); var r = open(data, "forge", 5);
        r = ack(data, r, NeedRequestData.Action.RESERVE, 3, 11);
        r = ack(data, r, NeedRequestData.Action.DISPATCH, 2, 12);
        r = ack(data, r, NeedRequestData.Action.DELIVER, 1, 13);
        assertEquals(1, r.reserved()); assertEquals(1, r.inTransit());
        assertEquals(1, r.delivered()); assertEquals(2, r.remaining());
        var restored = NeedRequestData.load(data.save(new CompoundTag(), null), null);
        assertEquals(r, restored.request(r.id()));
        r = ack(restored, r, NeedRequestData.Action.DELIVER, 1, 14);
        r = ack(restored, r, NeedRequestData.Action.DISPATCH, 1, 15);
        r = ack(restored, r, NeedRequestData.Action.DELIVER, 1, 16);
        r = ack(restored, r, NeedRequestData.Action.RESERVE, 2, 17);
        r = ack(restored, r, NeedRequestData.Action.DISPATCH, 2, 18);
        r = ack(restored, r, NeedRequestData.Action.DELIVER, 2, 19);
        assertEquals(NeedRequestStatus.DELIVERED, r.status());
        assertEquals(NeedRequestStatus.CONSUMED,
                restored.transition(r.id(), NeedRequestStatus.CONSUMED, 20, "consumption receipt checked").status());
    }

    @Test void acknowledgedReceiptCannotBeReplayedOrReassignedAfterReload() {
        var data = new NeedRequestData(); var r = open(data, "forge", 3); UUID receipt = UUID.randomUUID();
        data.acknowledge(receipt, r.id(), NeedRequestData.Action.RESERVE, 2, 11);
        var restored = NeedRequestData.load(data.save(new CompoundTag(), null), null);
        assertEquals(2, restored.acknowledge(receipt, r.id(), NeedRequestData.Action.RESERVE, 2, 12).reserved());
        assertThrows(IllegalStateException.class,
                () -> restored.acknowledge(receipt, r.id(), NeedRequestData.Action.RESERVE, 1, 12));
        var other = open(restored, "household", 3);
        assertThrows(IllegalStateException.class,
                () -> restored.acknowledge(receipt, other.id(), NeedRequestData.Action.RESERVE, 2, 12));
    }

    @Test void cancellationRequiresReleaseAndCannotDropCarriedGoods() {
        var data = new NeedRequestData(); var r = open(data, "forge", 3); final UUID id = r.id();
        r = ack(data, r, NeedRequestData.Action.RESERVE, 2, 11);
        assertThrows(IllegalArgumentException.class, () -> data.transition(id, NeedRequestStatus.CANCELLED, 12, "cancel"));
        r = ack(data, r, NeedRequestData.Action.RELEASE_RESERVED, 2, 12);
        assertEquals(NeedRequestStatus.CANCELLED, data.transition(id, NeedRequestStatus.CANCELLED, 13, "released").status());
        var carried = open(data, "cargo", 3);
        ack(data, carried, NeedRequestData.Action.RESERVE, 2, 11);
        ack(data, carried, NeedRequestData.Action.DISPATCH, 2, 12);
        assertThrows(IllegalArgumentException.class,
                () -> data.transition(carried.id(), NeedRequestStatus.CANCELLED, 13, "cancel"));
        assertThrows(IllegalStateException.class,
                () -> data.transition(carried.id(), NeedRequestStatus.OPEN, 13, "restart"));
    }

    @Test void dependencyCycleBlocksRequestWithoutInstallingBadEdge() {
        var data = new NeedRequestData(); var a = open(data, "a", 1);
        var b = open(data, "b", 1); var c = open(data, "c", 1);
        assertTrue(data.dependOn(a.id(), b.id(), 11)); assertTrue(data.dependOn(b.id(), c.id(), 11));
        assertFalse(data.dependOn(c.id(), a.id(), 12));
        assertEquals(NeedRequestStatus.BLOCKED_CYCLE, data.request(c.id()).status());
        assertTrue(data.dependencies(c.id()).isEmpty());
        var restored = NeedRequestData.load(data.save(new CompoundTag(), null), null);
        assertEquals(data.dependencies(a.id()), restored.dependencies(a.id()));
        assertFalse(restored.dependOn(c.id(), c.id(), 13));
    }

    @Test void retryAndDeadlinePreventInfiniteReservingButDoNotDiscardCargo() {
        var data = new NeedRequestData(); var r = open(data, "forge", 3);
        for (int i = 0; i < 3; i++) data.failedAttempt(r.id(), 11 + i, "no stock");
        assertThrows(IllegalStateException.class, () -> data.failedAttempt(r.id(), 14, "retry"));
        assertThrows(IllegalStateException.class, () -> ack(data, r, NeedRequestData.Action.RESERVE, 1, 14));
        var pending = open(data, "late", 3);
        assertThrows(IllegalStateException.class, () -> ack(data, pending, NeedRequestData.Action.RESERVE, 1, 1_000));
        ack(data, pending, NeedRequestData.Action.RESERVE, 1, 11);
        ack(data, pending, NeedRequestData.Action.DISPATCH, 1, 12);
        assertEquals(1, ack(data, pending, NeedRequestData.Action.DELIVER, 1, 1_001).delivered());
    }

    @Test void malformedOrDuplicateLedgerFailsClosed() {
        var data = new NeedRequestData(); var r = open(data, "forge", 3);
        var tag = data.save(new CompoundTag(), null); tag.remove("Receipts");
        assertThrows(IllegalArgumentException.class, () -> NeedRequestData.load(tag, null));
        var duplicate = data.save(new CompoundTag(), null);
        ((ListTag) duplicate.get("Requests")).add(r.save());
        assertThrows(IllegalArgumentException.class, () -> NeedRequestData.load(duplicate, null));
        var broken = r.save(); broken.getCompoundOrEmpty("Item").remove("Components");
        assertThrows(IllegalArgumentException.class, () -> NeedRequest.load(broken));
    }

    @Test void cannotOverReserveDeliverWithoutPickupOrShrinkBelowCommitments() {
        var data = new NeedRequestData(); var r = open(data, "forge", 3);
        ack(data, r, NeedRequestData.Action.RESERVE, 2, 11);
        assertThrows(IllegalStateException.class, () -> ack(data, r, NeedRequestData.Action.RESERVE, 2, 12));
        assertThrows(IllegalStateException.class, () -> ack(data, r, NeedRequestData.Action.DELIVER, 1, 12));
        assertThrows(IllegalArgumentException.class, () -> data.openOrUpdate(r.key(), 1, 3, 12));
        assertThrows(IllegalArgumentException.class, () -> data.openOrUpdate(r.key(), 3, 3, 9));
    }

    private NeedRequest exhaustedNaturalFood(NeedRequestData data) {
        UUID home = UUID.randomUUID();
        var key = new NeedRequest.Key(home, "HOUSEHOLD:household:natural-diet:" + home,
                new ItemFingerprint("minecraft:carrot", 0), UUID.randomUUID(), 0, 1000);
        var need = data.openOrUpdate(key, 4, 32, 0);
        for (int i = 1; i <= 32; i++) data.failedAttempt(need.id(), i, "no legal source");
        return data.transition(need.id(), NeedRequestStatus.WAITING_EXTERNAL, 33, "original exhausted wait");
    }

    @Test void oneRealEmergencyReservePreservesOriginalAgeBudgetAndHistoryAcrossReload() {
        var data = new NeedRequestData(); var original = exhaustedNaturalFood(data); var publications = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> data.acknowledge(UUID.randomUUID(), original.id(), NeedRequestData.Action.RESERVE, 2, 40));
        var reserved = data.acknowledgeEmergencyReserveAndPublish(original.id(), 2, 40, publications::incrementAndGet);
        assertEquals(1, publications.get()); assertEquals(2, reserved.reserved());
        assertEquals(original.id(), reserved.id()); assertEquals(original.key(), reserved.key());
        assertEquals(original.createdAt(), reserved.createdAt()); assertEquals(32, reserved.attempts()); assertEquals(32, reserved.maxAttempts());
        assertTrue(data.emergencyReserved(original.id()));
        var restored = NeedRequestData.load(data.save(new CompoundTag(), null), null);
        assertEquals(reserved, restored.acknowledgeEmergencyReserveAndPublish(original.id(), 2, 41, publications::incrementAndGet));
        assertEquals(1, publications.get());
        restored.acknowledge(UUID.randomUUID(), original.id(), NeedRequestData.Action.DISPATCH, 2, 42);
        var partial = restored.acknowledge(UUID.randomUUID(), original.id(), NeedRequestData.Action.DELIVER, 2, 43);
        assertEquals(2, partial.delivered()); assertEquals(2, partial.remaining());
        assertEquals(partial, restored.acknowledgeEmergencyReserveAndPublish(original.id(), 2, 44, publications::incrementAndGet));
        assertEquals(1, publications.get()); assertEquals(0, restored.request(original.id()).reserved());
        assertThrows(IllegalStateException.class, () -> restored.acknowledgeEmergencyReserveAndPublish(original.id(), 1, 44, publications::incrementAndGet));
        assertThrows(IllegalStateException.class, () -> restored.acknowledge(UUID.randomUUID(), original.id(), NeedRequestData.Action.RESERVE, 2, 44));
    }

    @Test void failedEmergencyPublisherPreservesWaitingStateAndDoesNotSpendOpportunity() {
        var data = new NeedRequestData(); var original = exhaustedNaturalFood(data);
        var before = data.save(new CompoundTag(), null);
        assertThrows(IllegalStateException.class, () -> data.acknowledgeEmergencyReserveAndPublish(original.id(), 2, 40,
                () -> { throw new IllegalStateException("linked publication rejected before mutation"); }));
        assertEquals(before, data.save(new CompoundTag(), null)); assertFalse(data.emergencyReserved(original.id()));
        assertEquals(NeedRequestStatus.WAITING_EXTERNAL, data.request(original.id()).status());
        assertEquals(2, data.acknowledgeEmergencyReserveAndPublish(original.id(), 2, 41, () -> {}).reserved());
    }

    @Test void emergencyCannotBypassDeadlineCycleOtherPurposeOrExistingCustody() {
        var data = new NeedRequestData(); var original = exhaustedNaturalFood(data);
        assertThrows(IllegalStateException.class, () -> data.acknowledgeEmergencyReserveAndPublish(original.id(), 1, 1000, () -> {}));
        assertThrows(IllegalStateException.class, () -> data.acknowledgeEmergencyReserveAndPublish(original.id(), 5, 40, () -> {}));
        assertFalse(data.emergencyReserved(original.id()));
        data.transition(original.id(), NeedRequestStatus.OPEN, 40, "revalidation");
        data.transition(original.id(), NeedRequestStatus.BLOCKED_CYCLE, 41, "real dependency cycle");
        assertThrows(IllegalStateException.class, () -> data.acknowledgeEmergencyReserveAndPublish(original.id(), 1, 42, () -> {}));
        var other = data.openOrUpdate(key("ordinary-industry"), 4, 32, 10);
        for (int i = 0; i < 32; i++) data.failedAttempt(other.id(), 11 + i, "ordinary shortage");
        assertThrows(IllegalStateException.class, () -> data.acknowledgeEmergencyReserveAndPublish(other.id(), 1, 50, () -> {}));
        var heldData = new NeedRequestData(); UUID home = UUID.randomUUID();
        var held = heldData.openOrUpdate(new NeedRequest.Key(home, "HOUSEHOLD:household:natural-diet:" + home,
                new ItemFingerprint("minecraft:carrot", 0), UUID.randomUUID(), 0, 1000), 4, 32, 0);
        heldData.acknowledge(UUID.randomUUID(), held.id(), NeedRequestData.Action.RESERVE, 1, 0);
        for (int i = 1; i <= 32; i++) heldData.failedAttempt(held.id(), i, "prior obligation still held");
        assertThrows(IllegalStateException.class, () -> heldData.acknowledgeEmergencyReserveAndPublish(held.id(), 1, 40, () -> {}));
        assertEquals(1, heldData.request(held.id()).reserved()); assertFalse(heldData.emergencyReserved(held.id()));
    }
}
