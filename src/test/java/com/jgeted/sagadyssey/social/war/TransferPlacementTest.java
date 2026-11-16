package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferPlacementTest {
    @Test void aRestrictedContainerCannotReceiveItemsInAnEarlierForbiddenSlot() {
        var source = new SimpleContainer(1); source.setItem(0, new ItemStack(Items.BREAD, 2));
        var destination = new SimpleContainer(2) { @Override public boolean canPlaceItem(int slot, ItemStack item) { return slot == 1; } };
        var receipt = transfer(source, destination, 2);
        assertTrue(receipt.successful()); assertTrue(destination.getItem(0).isEmpty()); assertEquals(2, destination.getItem(1).getCount()); assertTrue(source.isEmpty());
    }
    @Test void containerStackLimitsAreAppliedDuringSimulationBeforeAnyGoodsAreRemoved() {
        var source = new SimpleContainer(1); source.setItem(0, new ItemStack(Items.BREAD, 4));
        var destination = new SimpleContainer(2) { @Override public int getMaxStackSize() { return 2; } };
        assertTrue(transfer(source, destination, 4).successful());
        assertEquals(2, destination.getItem(0).getCount()); assertEquals(2, destination.getItem(1).getCount()); assertTrue(source.isEmpty());
        var fullSource = new SimpleContainer(1); fullSource.setItem(0, new ItemStack(Items.BREAD, 5));
        var tooSmall = new SimpleContainer(2) { @Override public int getMaxStackSize() { return 2; } };
        assertEquals(TransferReceipt.Status.CAPACITY_EXCEEDED, transfer(fullSource, tooSmall, 5).status());
        assertEquals(5, fullSource.getItem(0).getCount()); assertTrue(tooSmall.isEmpty());
    }
    private static TransferReceipt transfer(SimpleContainer source, SimpleContainer destination, int amount) {
        UUID from = UUID.randomUUID(), to = UUID.randomUUID(); var actor = SubjectRef.character(UUID.randomUUID()); var rights = new RightsStateData();
        rights.ensureContext(from, "test:source", actor, actor, AccessPolicy.PRIVATE); rights.ensureContext(to, "test:destination", actor, actor, AccessPolicy.PRIVATE);
        var request = new TransferRequest(UUID.randomUUID(), actor, TransferIntent.DELIVERY, List.of(new TransferLeg(from, to, ItemFingerprint.of(new ItemStack(Items.BREAD)), amount)),
                List.of(new ExpectedRevision(from, 0), new ExpectedRevision(to, 0)), "placement-test", 0);
        return new TransferService(rights, new TransferLedgerData()).submit(request, Map.of(from, new TransferEndpoint(from, source), to, new TransferEndpoint(to, destination)), 1);
    }
}
