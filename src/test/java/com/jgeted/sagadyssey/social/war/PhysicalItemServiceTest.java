package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;
import static com.jgeted.sagadyssey.worldsim.transfer.PhysicalItemService.Status.*;

/** 隔离故障夹具；真实NPC/容器另由服务端GameTest验证。 */
class PhysicalItemServiceTest {
    @TempDir Path directory;
    private static final class Owner {
        final UUID id = UUID.randomUUID(); final SubjectRef actor = SubjectRef.character(UUID.randomUUID());
        final SimpleContainer bag = new SimpleContainer(3); final CompoundTag persistent = new CompoundTag();
        final RightsStateData rights = new RightsStateData(); final TransferLedgerData transfers = new TransferLedgerData();
        final WitnessedItemEndpoint endpoint = WitnessedItemEndpoint.forOwner(id, bag, () -> persistent, () -> true, bag::setChanged);
        Owner() { bag.setItem(0, new ItemStack(Items.BREAD, 3)); rights.ensureContext(id, "test:owner", actor, actor, AccessPolicy.PRIVATE); }
        PhysicalItemOperation.Intent meal() { return PhysicalItemOperation.Intent.consume(new PhysicalItemOperation.Consumption(UUID.randomUUID(), id, actor, ItemFingerprint.of(new ItemStack(Items.BREAD)), 1, "", "test:meal", rights.context(id).revision(), 0)); }
        Map<UUID, WitnessedItemEndpoint> endpoints() { return Map.of(id, endpoint); }
    }
    private static PhysicalItemService.Reply finish(PhysicalItemService service, PhysicalItemOperation.Intent request, Owner owner) {
        long until = System.nanoTime() + 5_000_000_000L;
        do { var reply = service.submit(request, owner.endpoints(), 100); if (reply.status() != PENDING) return reply; LockSupport.parkNanos(1_000_000); }
        while (System.nanoTime() < until); throw new AssertionError("item checkpoint stalled");
    }
    @Test void ordinaryConsumptionAndRepeatedReopenNeverConsumeTwice() throws Exception {
        var owner = new Owner(); var request = owner.meal(); var file = directory.resolve("items.sgw");
        try (var service = new PhysicalItemService(file, owner.rights, owner.transfers)) { assertEquals(APPLIED, finish(service, request, owner).status()); }
        for (int i = 0; i < 3; i++) try (var service = new PhysicalItemService(file, owner.rights, owner.transfers)) {
            assertEquals(APPLIED, finish(service, request, owner).status()); assertEquals(2, owner.bag.getItem(0).getCount()); assertFalse(service.hasUnresolved(owner.id));
        }
    }
    @Test void aMissingOwnerWitnessAfterReloadIsUnknownEvenIfTheCountsLookPlausible() throws Exception {
        var owner = new Owner(); var request = owner.meal(); var file = directory.resolve("items.sgw");
        try (var service = new PhysicalItemService(file, owner.rights, owner.transfers)) { assertEquals(APPLIED, finish(service, request, owner).status()); }
        owner.persistent.keySet().stream().toList().forEach(owner.persistent::remove);
        try (var service = new PhysicalItemService(file, owner.rights, owner.transfers)) {
            assertEquals(UNKNOWN, finish(service, request, owner).status()); assertEquals(2, owner.bag.getItem(0).getCount());
            assertEquals(UNKNOWN, finish(service, owner.meal(), owner).status());
        }
    }
    @Test void missingRightsEvidenceDoesNotReplayOrPermitANewOperationOnTheOwner() throws Exception {
        var owner = new Owner(); var request = owner.meal(); var file = directory.resolve("items.sgw");
        try (var service = new PhysicalItemService(file, owner.rights, owner.transfers)) { assertEquals(APPLIED, finish(service, request, owner).status()); }
        var oldRights = new RightsStateData(); oldRights.ensureContext(owner.id, "test:owner", owner.actor, owner.actor, AccessPolicy.PRIVATE);
        try (var service = new PhysicalItemService(file, oldRights, owner.transfers)) {
            assertEquals(UNKNOWN, finish(service, request, owner).status()); assertEquals(UNKNOWN, finish(service, owner.meal(), owner).status()); assertEquals(2, owner.bag.getItem(0).getCount());
        }
    }
    @Test void anIssuingCrashHasNoInMemoryPermissionToRetryPhysicalMutation() throws Exception {
        var owner = new Owner(); var request = owner.meal(); var file = directory.resolve("items.sgw");
        Files.write(file, new PhysicalItemOperationCodec().write(List.of(new PhysicalItemOperation(request, PhysicalItemOperation.Phase.ISSUING, Optional.empty()))));
        // 原消费已发生、物权/容器凭据尚未提交的崩溃边界，不能凭少了一份食物猜测结果。
        var consume = request.consumption().orElseThrow(); assertTrue(new ConsumptionService(owner.rights).consume(owner.id, owner.bag, owner.actor, consume.item(), 1, "", 1).successful());
        try (var service = new PhysicalItemService(file, owner.rights, owner.transfers)) {
            assertEquals(UNKNOWN, finish(service, request, owner).status()); assertEquals(2, owner.bag.getItem(0).getCount());
            assertEquals(PENDING, service.submit(owner.meal(), owner.endpoints(), 100).status()); assertTrue(service.hasUnresolved(owner.id));
        }
    }
    @Test void anIntentCrashBeforeIssuingCanSafelyContinueAndAnOldRevisionIsProvedDenied() throws Exception {
        var owner = new Owner(); var request = owner.meal(); var file = directory.resolve("items.sgw");
        Files.write(file, new PhysicalItemOperationCodec().write(List.of(new PhysicalItemOperation(request, PhysicalItemOperation.Phase.INTENT, Optional.empty()))));
        owner.rights.bumpContext(owner.id);
        try (var service = new PhysicalItemService(file, owner.rights, owner.transfers)) {
            assertEquals(DENIED, finish(service, request, owner).status()); assertEquals(3, owner.bag.getItem(0).getCount());
            assertEquals(DENIED, finish(service, request, owner).status()); assertEquals(APPLIED, finish(service, owner.meal(), owner).status()); assertEquals(2, owner.bag.getItem(0).getCount());
        }
    }
    @Test void changedPayloadsAreConflictsAndFutureOrCorruptFilesArePreserved() throws Exception {
        var owner = new Owner(); var request = owner.meal(); var file = directory.resolve("items.sgw");
        try (var service = new PhysicalItemService(file, owner.rights, owner.transfers)) {
            assertEquals(APPLIED, finish(service, request, owner).status()); var r = request.consumption().orElseThrow();
            var changed = PhysicalItemOperation.Intent.consume(new PhysicalItemOperation.Consumption(r.operationId(), r.container(), r.actor(), r.item(), 2, r.authority(), r.purpose(), r.expectedRevision(), r.requestedAt()));
            assertEquals(CONFLICT, finish(service, changed, owner).status());
        }
        byte[] bytes = Files.readAllBytes(file); ByteBuffer.wrap(bytes).putInt(4, 999); Files.write(file, bytes);
        assertThrows(java.io.IOException.class, () -> new PhysicalItemService(file, owner.rights, owner.transfers)); assertArrayEquals(bytes, Files.readAllBytes(file));
    }
    @Test void unavailableOwnersCreateNoIntentAndNutritionIsNotImplicitlyGranted() throws Exception {
        var owner = new Owner(); var file = directory.resolve("unused/items.sgw");
        try (var service = new PhysicalItemService(file, owner.rights, owner.transfers)) {
            assertEquals(UNAVAILABLE, service.submit(owner.meal(), Map.of(), 0).status()); assertFalse(Files.exists(file.getParent())); assertEquals(3, owner.bag.getItem(0).getCount());
        }
    }
}
