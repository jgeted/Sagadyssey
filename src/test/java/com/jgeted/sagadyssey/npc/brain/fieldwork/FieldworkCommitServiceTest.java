package com.jgeted.sagadyssey.npc.brain.fieldwork;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FieldworkCommitServiceTest {
    @Test
    void commitsExactlyOneBlockInventoryToolWearAndReceipt() {
        FieldworkData data = new FieldworkData();
        FieldworkAction action = action(FieldworkAction.Operation.MINE);
        FakeMutation mutation = new FakeMutation(action, 10L);
        var result = FieldworkCommitService.commit(data, action, 10L, (value, now) -> mutation);
        assertEquals(FieldworkCommitService.Status.COMMITTED, result.status());
        assertEquals("minecraft:air", mutation.block);
        assertEquals(1, mutation.inventory);
        assertEquals(1, mutation.wear);
        assertEquals(1, mutation.rightsRevision);
        assertEquals(1, mutation.blockChanges);
        assertEquals(mutation.preview(), data.receipt(action.key()).orElseThrow());
    }

    @Test
    void saveReloadRetryDoesNotPrepareChangeOrConsumeAnythingAgain() {
        FieldworkData data = new FieldworkData();
        FieldworkAction action = action(FieldworkAction.Operation.HARVEST);
        FakeMutation mutation = new FakeMutation(action, 10L);
        FieldworkCommitService.commit(data, action, 10L, (value, now) -> mutation);
        FieldworkData reloaded = FieldworkData.load(data.save(new CompoundTag(), null), null);
        var result = FieldworkCommitService.commit(reloaded, action, 100L,
                (value, now) -> { throw new AssertionError("already committed work must not be prepared"); });
        assertEquals(FieldworkCommitService.Status.ALREADY_COMMITTED, result.status());
        assertEquals(10L, result.receipt().orElseThrow().committedAt());
        assertEquals(1, mutation.blockChanges);
        assertEquals(1, mutation.inventory);
    }

    @Test
    void changedPayloadUnderSameStepKeyFailsClosed() {
        FieldworkData data = new FieldworkData();
        FieldworkAction action = action(FieldworkAction.Operation.CHOP);
        FieldworkCommitService.commit(data, action, 10L, (value, now) -> new FakeMutation(action, now));
        FieldworkAction conflicting = new FieldworkAction(action.planId(), action.npcId(), action.stepIndex(),
                action.dimension(), action.target().above(), action.operation(), action.expectedState());
        assertThrows(IllegalStateException.class, () -> FieldworkCommitService.commit(data, conflicting, 20L,
                (value, now) -> { throw new AssertionError("collision must be rejected before mutation"); }));
    }

    @Test
    void permissionsMissingSeedWrongToolAndOutputFullBlockBeforeAnyMutation() {
        for (String code : List.of("FORBIDDEN", "MISSING_SEED", "WRONG_TOOL", "OUTPUT_FULL", "TARGET_CHANGED",
                "CHUNK_UNLOADED", "TARGET_RESERVED")) {
            FieldworkData data = new FieldworkData();
            FieldworkAction action = action(FieldworkAction.Operation.PLANT);
            FakeMutation mutation = new FakeMutation(action, 10L);
            mutation.blocker = new FieldworkCommitService.Blocker(code, "测试阻塞");
            var result = FieldworkCommitService.commit(data, action, 10L, (value, now) -> mutation);
            assertEquals(FieldworkCommitService.Status.BLOCKED, result.status());
            assertEquals(code, result.blocker().orElseThrow().code());
            assertUnchanged(mutation);
            assertTrue(data.receipt(action.key()).isEmpty());
        }
    }

    @Test
    void rejectedBlockUpdateRollsBackWithoutRegisteringOutput() {
        FieldworkData data = new FieldworkData();
        FieldworkAction action = action(FieldworkAction.Operation.PLACE_SCAFFOLD);
        FakeMutation mutation = new FakeMutation(action, 10L);
        mutation.blockAccepted = false;
        var result = FieldworkCommitService.commit(data, action, 10L, (value, now) -> mutation);
        assertEquals(FieldworkCommitService.Status.BLOCKED, result.status());
        assertEquals("BLOCK_UPDATE_REJECTED", result.blocker().orElseThrow().code());
        assertEquals(1, mutation.rollbacks);
        assertUnchanged(mutation);
        assertTrue(data.receipt(action.key()).isEmpty());
    }

    @Test
    void partialInventoryFailureRestoresBlockSeedToolRightsAndCanRetrySameStep() {
        FieldworkData data = new FieldworkData();
        FieldworkAction action = action(FieldworkAction.Operation.PLANT);
        FakeMutation mutation = new FakeMutation(action, 10L);
        mutation.failInventory = true;
        assertThrows(IllegalStateException.class, () -> FieldworkCommitService.commit(data, action, 10L,
                (value, now) -> mutation));
        assertUnchanged(mutation);
        assertTrue(data.receipt(action.key()).isEmpty());
        FakeMutation retry = new FakeMutation(action, 20L);
        assertEquals(FieldworkCommitService.Status.COMMITTED,
                FieldworkCommitService.commit(data, action, 20L, (value, now) -> retry).status());
    }

    @Test
    void failedReceiptWriteAlsoRollsBackInventoryWorldAndRights() {
        FieldworkAction action = action(FieldworkAction.Operation.RECOVER_SCAFFOLD);
        FakeMutation mutation = new FakeMutation(action, 10L);
        FieldworkCommitService.Ledger failingLedger = new FieldworkCommitService.Ledger() {
            @Override public Optional<FieldworkActionReceipt> receipt(String key) { return Optional.empty(); }
            @Override public void record(FieldworkActionReceipt receipt) { throw new IllegalStateException("ledger failed"); }
        };
        assertThrows(IllegalStateException.class, () -> FieldworkCommitService.commit(failingLedger, action, 10L,
                (value, now) -> mutation));
        assertUnchanged(mutation);
        assertEquals(1, mutation.rollbacks);
    }

    @Test
    void rollbackFailureIsNotMisreportedAsSafeWait() {
        FieldworkAction action = action(FieldworkAction.Operation.MINE);
        FakeMutation mutation = new FakeMutation(action, 10L);
        mutation.failInventory = true;
        mutation.failRollback = true;
        var exception = assertThrows(IllegalStateException.class, () -> FieldworkCommitService.commit(
                new FieldworkData(), action, 10L, (value, now) -> mutation));
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("rollback failed", exception.getSuppressed()[0].getMessage());
        assertEquals(1, mutation.rollbacks);
    }

    @Test
    void malformedPreviewCannotModifyWorldOrWriteReceipt() {
        FieldworkData data = new FieldworkData();
        FieldworkAction action = action(FieldworkAction.Operation.TILL);
        FakeMutation mutation = new FakeMutation(action, 9L);
        assertThrows(IllegalStateException.class, () -> FieldworkCommitService.commit(data, action, 10L,
                (value, now) -> mutation));
        assertUnchanged(mutation);
        assertEquals(0, mutation.blockChanges);
        assertTrue(data.receiptsFor(action.npcId()).isEmpty());
    }

    static FieldworkAction action(FieldworkAction.Operation operation) {
        return new FieldworkAction(UUID.randomUUID(), UUID.randomUUID(), 2, "minecraft:overworld",
                new BlockPos(80, -60, 0), operation, "minecraft:stone");
    }

    static FieldworkActionReceipt receipt(FieldworkAction action, long now) {
        return new FieldworkActionReceipt(action, now, "minecraft:air",
                List.of(new FieldworkActionReceipt.ItemDelta("minecraft:cobblestone", 1)), 1);
    }

    private static void assertUnchanged(FakeMutation mutation) {
        assertEquals("minecraft:stone", mutation.block);
        assertEquals(0, mutation.inventory);
        assertEquals(0, mutation.wear);
        assertEquals(0, mutation.rightsRevision);
    }

    private static final class FakeMutation implements FieldworkCommitService.Mutation {
        final FieldworkActionReceipt receipt;
        String block = "minecraft:stone";
        int inventory, wear, rightsRevision, blockChanges, rollbacks;
        boolean blockAccepted = true, failInventory, failRollback;
        FieldworkCommitService.Blocker blocker;

        FakeMutation(FieldworkAction action, long now) { receipt = receipt(action, now); }
        @Override public Optional<FieldworkCommitService.Blocker> validate() { return Optional.ofNullable(blocker); }
        @Override public FieldworkActionReceipt preview() { return receipt; }
        @Override public boolean applySingleBlock() { block = "minecraft:air"; blockChanges++; return blockAccepted; }
        @Override public void applyInventoryAndRights() {
            inventory++;
            wear++;
            rightsRevision++;
            if (failInventory) throw new IllegalStateException("inventory failed");
        }
        @Override public void rollback() {
            rollbacks++;
            if (failRollback) throw new IllegalStateException("rollback failed");
            block = "minecraft:stone";
            inventory = wear = rightsRevision = 0;
        }
    }
}
