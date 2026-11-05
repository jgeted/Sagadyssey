package com.jgeted.sagadyssey.npc.brain.fieldwork;

import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FieldworkStorageTest {
    private static FieldworkStorage storage() {
        return new FieldworkStorage("minecraft:overworld", new BlockPos(80, 1, 5), UUID.randomUUID(),
                SubjectRef.character(UUID.randomUUID()), AccessPolicy.PRIVATE, TransferIntent.DELIVERY, "test");
    }
    private static FieldworkStorage.Pending pending() {
        var storage = storage();
        return new FieldworkStorage.Pending(UUID.randomUUID(), UUID.randomUUID(), storage.owner(), storage,
                UUID.randomUUID(), new ItemFingerprint("minecraft:cobblestone", 0), 1, 4, 8, 20);
    }
    @Test void storageRoundTripRetainsFarPositionAndAuthority() {
        var value = storage(); assertEquals(value, FieldworkStorage.load(value.save()));
    }
    @Test void pendingRoundTripRetainsOriginalIdAndExactTransferRequest() {
        var value = pending(); var restored = FieldworkStorage.Pending.load(value.save());
        assertEquals(value, restored); assertEquals(value.request(), restored.request());
    }
    @Test void missingPositionCannotSilentlyBecomeWorldOrigin() {
        var tag = storage().save(); tag.remove("Position"); assertThrows(IllegalArgumentException.class, () -> FieldworkStorage.load(tag));
    }
    @Test void wronglyTypedPositionCannotSilentlyBecomeWorldOrigin() {
        var tag = storage().save(); tag.putString("Position", "broken"); assertThrows(IllegalArgumentException.class, () -> FieldworkStorage.load(tag));
    }
    @Test void malformedRevisionCannotSilentlyBecomeZero() {
        var tag = pending().save(); tag.putString("SourceRevision", "broken"); assertThrows(IllegalArgumentException.class, () -> FieldworkStorage.Pending.load(tag));
    }
    @Test void wronglyTypedQuantityCannotChangeOriginalTransferPayload() {
        var tag = pending().save(); tag.putLong("Quantity", 1); assertThrows(IllegalArgumentException.class, () -> FieldworkStorage.Pending.load(tag));
    }
}
