package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalSupplierRulesTest {
    private static final ItemFingerprint IRON = new ItemFingerprint("minecraft:iron_ingot", 0);
    private NeedRequest make(NeedRequestData data, String purpose, int quantity, long start, long deadline) {
        return data.openOrUpdate(new NeedRequest.Key(UUID.randomUUID(), purpose, IRON, UUID.randomUUID(), start, deadline), quantity, 32, start);
    }
    private NeedRequest supplier(NeedRequestData data) { return make(data, "CHARACTER:local-supplier:original-parent:minecraft:iron_ingot", 3, 10, 100); }

    @Test void expiryWithoutEffectsMayReassessButBoundOrUncertainWorkCannotDisappear() {
        var data = new NeedRequestData(); var child = supplier(data);
        child = data.transition(child.id(), NeedRequestStatus.WAITING_EXTERNAL, 20, "no materials");
        assertFalse(LocalSupplierRules.expireUncommitted(child, false, false, 99));
        assertTrue(LocalSupplierRules.expireUncommitted(child, false, false, 100));
        assertFalse(LocalSupplierRules.expireUncommitted(child, true, false, 100));
        assertFalse(LocalSupplierRules.expireUncommitted(child, false, true, 100));
        assertTrue(LocalSupplierRules.blocksNextBatch(child, false, 0), "elapsed deadline alone is not a cancellation");
        data.transition(child.id(), NeedRequestStatus.CANCELLED, 100, "no committed effects, original record retained");
        assertFalse(LocalSupplierRules.blocksNextBatch(data.request(child.id()), false, 0));
        assertEquals(1, data.requests().size());
    }

    @Test void heldAndCarriedGoodsPreventExpiryEvenWhenDeadlinePasses() {
        var data = new NeedRequestData(); var child = supplier(data);
        child = data.acknowledge(UUID.randomUUID(), child.id(), NeedRequestData.Action.RESERVE, 3, 20);
        assertFalse(LocalSupplierRules.expireUncommitted(child, false, false, 101));
        child = data.acknowledge(UUID.randomUUID(), child.id(), NeedRequestData.Action.DISPATCH, 3, 21);
        assertFalse(LocalSupplierRules.expireUncommitted(child, false, false, 101));
        assertTrue(LocalSupplierRules.blocksNextBatch(child, false, 0));
    }

    @Test void activeSupplierDependencySurvivesNbtAndBlocksAnotherSupplierForSameParent() {
        var data = new NeedRequestData(); var parent = make(data, "HOUSEHOLD:workshop:real-tools", 6, 10, 1000);
        var first = supplier(data); assertTrue(data.dependOn(parent.id(), first.id(), 10));
        data = NeedRequestData.load(data.save(new CompoundTag(), null), null);
        assertEquals(1, data.dependencies(parent.id()).size());
        for (var childId : data.dependencies(parent.id())) {
            assertTrue(LocalSupplierRules.blocksNextBatch(data.request(childId), false, 0));
        }
        assertEquals(2, data.requests().size(), "restoring dependencies does not create a replacement supplier or parent");
    }

    @Test void receiptDeliveredStockMustActuallyLeaveBeforeNextSupplierBatch() {
        var data = new NeedRequestData(); var child = supplier(data);
        data.acknowledge(UUID.randomUUID(), child.id(), NeedRequestData.Action.RESERVE, 3, 20);
        data.acknowledge(UUID.randomUUID(), child.id(), NeedRequestData.Action.DISPATCH, 3, 21);
        var receipt = UUID.randomUUID(); data.acknowledge(receipt, child.id(), NeedRequestData.Action.DELIVER, 3, 22);
        data.acknowledge(receipt, child.id(), NeedRequestData.Action.DELIVER, 3, 23);
        child = data.request(child.id());
        assertEquals(3, child.delivered());
        assertTrue(LocalSupplierRules.blocksNextBatch(child, false, 3));
        assertTrue(LocalSupplierRules.blocksNextBatch(child, true, 0));
        assertFalse(LocalSupplierRules.blocksNextBatch(child, false, 0));
        assertFalse(LocalSupplierRules.expireUncommitted(child, false, false, 101));
    }

    @Test void upstreamSupplierIntentCannotRecursivelyBecomeItsOwnDownstreamDemand() {
        var data = new NeedRequestData(); var child = supplier(data);
        assertFalse(LocalSupplierRules.eligibleParent(child, 20));
        var parent = make(data, "CHARACTER:production:actual-tool-order", 6, 10, 1000);
        assertTrue(LocalSupplierRules.eligibleParent(parent, 20));
        parent = data.transition(parent.id(), NeedRequestStatus.BLOCKED_CYCLE, 21, "real unresolved dependency");
        assertFalse(LocalSupplierRules.eligibleParent(parent, 22));
    }

    @Test void freeStockAndAlreadyCommittedGoodsAreNotDoubleCounted() {
        var data = new NeedRequestData(); var parent = make(data, "HOUSEHOLD:workshop:real-tools", 8, 10, 1000);
        data.acknowledge(UUID.randomUUID(), parent.id(), NeedRequestData.Action.RESERVE, 3, 20);
        data.acknowledge(UUID.randomUUID(), parent.id(), NeedRequestData.Action.DISPATCH, 2, 21);
        parent = data.acknowledge(UUID.randomUUID(), parent.id(), NeedRequestData.Action.DELIVER, 1, 22);
        assertEquals(1, parent.reserved()); assertEquals(1, parent.inTransit()); assertEquals(1, parent.delivered());
        assertEquals(5, parent.remaining()); assertEquals(3, LocalSupplierRules.uncovered(parent, 2));
        assertEquals(0, LocalSupplierRules.uncovered(parent, 8));
        assertFalse(LocalSupplierRules.eligibleParent(parent, 23), "existing dependency API requires uncommitted parent before adding a new edge");
    }
}
