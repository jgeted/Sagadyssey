package com.jgeted.sagadyssey.npc.brain.fieldwork;

import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FieldworkDeliveryRecoveryTest {
    @Test void committedTransferBeforeCargoAckIsReconciledAfterReloadWithoutSecondTransfer() {
        FieldworkData data = new FieldworkData(); RightsStateData rights = new RightsStateData(); TransferLedgerData ledger = new TransferLedgerData();
        UUID npc = UUID.randomUUID(), sourceId = UUID.randomUUID(), targetId = UUID.randomUUID();
        SubjectRef actor = SubjectRef.character(UUID.randomUUID());
        SimpleContainer source = new SimpleContainer(2), destination = new SimpleContainer(2);
        source.setItem(0, new ItemStack(Items.COBBLESTONE));
        ItemFingerprint fp = ItemFingerprint.of(source.getItem(0));
        String fingerprint = FieldworkWorldMutation.fingerprint(source.getItem(0));
        rights.ensureContext(sourceId, "source", actor, actor, AccessPolicy.PRIVATE);
        rights.ensureContext(targetId, "target", actor, actor, AccessPolicy.PRIVATE);
        var action = new FieldworkAction(UUID.randomUUID(), npc, 2, "minecraft:overworld", BlockPos.ZERO, FieldworkAction.Operation.MINE, "stone");
        data.record(new FieldworkActionReceipt(action, 10, "air", List.of(new FieldworkActionReceipt.ItemDelta(fingerprint, 1)), 1));
        var storage = new FieldworkStorage("minecraft:overworld", new BlockPos(8, 1, 5), targetId, actor, AccessPolicy.PRIVATE, TransferIntent.DELIVERY, "test");
        var pending = new FieldworkStorage.Pending(npc, sourceId, actor, storage, UUID.randomUUID(), fp, 1, 0, 0, 20);
        data.beginDelivery(pending);
        var endpoints = Map.of(sourceId, new TransferEndpoint(sourceId, source), targetId, new TransferEndpoint(targetId, destination));
        var receipt = new TransferService(rights, ledger).submit(pending.request(), endpoints, 21);
        assertTrue(receipt.successful());
        FieldworkData reloaded = FieldworkData.load(data.save(new CompoundTag(), null), null);
        assertEquals(pending, reloaded.pendingDelivery(npc).orElseThrow());
        var replay = new TransferService(rights, ledger).submit(reloaded.pendingDelivery(npc).orElseThrow().request(), endpoints, 40);
        FieldworkDepositTaskExecutor.finish(reloaded, pending, replay);
        assertEquals(1, destination.getItem(0).getCount());
        assertTrue(source.getItem(0).isEmpty());
        assertTrue(reloaded.pendingDelivery(npc).isEmpty());
        assertTrue(reloaded.pendingCargo(npc).isEmpty());
        FieldworkDepositTaskExecutor.finish(reloaded, pending, replay);
        assertTrue(reloaded.pendingCargo(npc).isEmpty());
    }
    @Test void failedDeliveryClearsIntentButRetainsCargoAndRetryDeadlineAcrossReload() {
        FieldworkData data = new FieldworkData(); UUID npc = UUID.randomUUID();
        var action = new FieldworkAction(UUID.randomUUID(), npc, 2, "minecraft:overworld", BlockPos.ZERO, FieldworkAction.Operation.MINE, "stone");
        data.record(new FieldworkActionReceipt(action, 10, "air", List.of(new FieldworkActionReceipt.ItemDelta("minecraft:cobblestone#0", 1)), 1));
        var storage = new FieldworkStorage("minecraft:overworld", new BlockPos(8, 1, 5), UUID.randomUUID(),
                SubjectRef.character(npc), AccessPolicy.PRIVATE, TransferIntent.DELIVERY, "test");
        var pending = new FieldworkStorage.Pending(npc, UUID.randomUUID(), SubjectRef.character(npc), storage, UUID.randomUUID(),
                new ItemFingerprint("minecraft:cobblestone", 0), 1, 0, 0, 20);
        data.beginDelivery(pending); data.bindTestStorage(npc, storage); data.deferDelivery(npc, 220);
        var failure = new TransferReceipt(pending.transferId(), TransferReceipt.Status.CAPACITY_EXCEEDED, true, "full", Map.of(), 21);
        FieldworkDepositTaskExecutor.finish(data, pending, failure);
        FieldworkData loaded = FieldworkData.load(data.save(new CompoundTag(), null), null);
        assertTrue(loaded.pendingDelivery(npc).isEmpty());
        assertEquals(Map.of("minecraft:cobblestone#0", 1), loaded.pendingCargo(npc));
        assertEquals(storage, loaded.testStorage(npc).orElseThrow());
        assertEquals(220, loaded.deliveryRetryAt(npc));
    }
}
