package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.core.MinecraftApi;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.locks.LockSupport;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferRelocationTest {
    @TempDir Path directory;
    private final class Fixture {
        final UUID bag = UUID.randomUUID(), gear = UUID.randomUUID();
        final SubjectRef npc = SubjectRef.character(UUID.randomUUID()), lord = SubjectRef.character(UUID.randomUUID());
        final RightsStateData rights = new RightsStateData(); final TransferLedgerData ledger = new TransferLedgerData();
        final SimpleContainer a = new SimpleContainer(3), b = new SimpleContainer(1);
        final ItemFingerprint bow = ItemFingerprint.of(new ItemStack(Items.BOW)), sword = ItemFingerprint.of(new ItemStack(Items.IRON_SWORD));
        Fixture() { this(false); }
        Fixture(boolean foreign) {
            rights.ensureContext(bag, "test:bag", npc, npc, AccessPolicy.PRIVATE); rights.ensureContext(gear, "test:gear", foreign ? lord : npc, npc, AccessPolicy.PRIVATE);
            a.setItem(0, new ItemStack(Items.BOW)); a.setItem(1, new ItemStack(Items.COBBLESTONE, 64)); a.setItem(2, new ItemStack(Items.IRON_SWORD)); b.setItem(0, new ItemStack(Items.BOW));
            rights.putClaim(new AssetClaim(UUID.randomUUID(), gear, 0, bow, 1, lord, npc, Set.of("issued", "loan:original"), Set.of(), "original-issue", 0));
        }
        TransferRequest request(TransferIntent intent, int destination) { return new TransferRequest(UUID.randomUUID(), npc, intent,
                List.of(new TransferLeg(gear, bag, bow, 1, 0, 2), new TransferLeg(bag, gear, sword, 1, 2, destination)),
                List.of(new ExpectedRevision(bag, rights.context(bag).revision()), new ExpectedRevision(gear, rights.context(gear).revision())), "swap", 100); }
        TransferReceipt run(TransferRequest r) { return new TransferService(rights, ledger).submit(r, Map.of(bag, new TransferEndpoint(bag, a), gear, new TransferEndpoint(gear, b)), 100); }
    }
    @Test void fullInventorySwapRetainsOriginalTitleEncumbrancesAndProvenance() {
        var f = new Fixture(); var r = f.request(TransferIntent.RELOCATE, 0); assertTrue(f.run(r).successful());
        assertTrue(f.a.getItem(0).is(Items.BOW)); assertEquals(64, f.a.getItem(1).getCount()); assertTrue(f.a.getItem(2).is(Items.BOW)); assertTrue(f.b.getItem(0).is(Items.IRON_SWORD));
        var claim = f.rights.claimAt(f.bag, 2, f.bow); assertEquals(f.lord, claim.beneficialOwner()); assertEquals(f.npc, claim.custodian());
        assertEquals(Set.of("issued", "loan:original"), claim.encumbrances()); assertEquals("original-issue", claim.provenance());
        assertNull(f.rights.claimAt(f.bag, 0, f.bow)); assertNull(f.rights.claimAt(f.gear, 0, f.sword));
        assertEquals(f.run(r), f.run(r)); assertTrue(f.a.getItem(2).is(Items.BOW));
    }
    @Test void anInternalMoveCannotBecomeAGiftAcrossCustodiansOrUseForeignSourceAuthority() {
        var f = new Fixture(true);
        assertFalse(f.run(f.request(TransferIntent.RELOCATE, 0)).successful());
        assertTrue(f.b.getItem(0).is(Items.BOW)); assertTrue(f.a.getItem(2).is(Items.IRON_SWORD));
        var other = new Fixture(); var r = other.request(TransferIntent.RELOCATE, 0);
        assertFalse(other.run(new TransferRequest(r.transferId(), r.initiatedBy(), r.intent(), r.legs(), r.expectedRevisions(), r.authority(), r.requestedAt(), Map.of(other.gear, other.lord))).successful());
    }
    @Test void anInvalidExplicitSlotAndAWrongSourceNeverMutateEitherInventory() {
        var f = new Fixture(); assertEquals(TransferReceipt.Status.INVALID, f.run(f.request(TransferIntent.RELOCATE, 1)).status());
        assertTrue(f.b.getItem(0).is(Items.BOW)); assertTrue(f.a.getItem(2).is(Items.IRON_SWORD));
        var r = f.request(TransferIntent.RELOCATE, 0); var constrained = new TransferRequest(UUID.randomUUID(), f.npc, TransferIntent.RELOCATE,
                List.of(new TransferLeg(f.bag, f.gear, f.sword, 1, 0, 0)), r.expectedRevisions(), "", 100);
        assertFalse(f.run(constrained).successful()); assertTrue(f.a.getItem(2).is(Items.IRON_SWORD));
    }
    @Test void oldUnconstrainedIdentitiesStayByteEquivalentAndNewConstraintsRoundTrip() throws Exception {
        var f = new Fixture(); var legacy = new TransferLeg(f.gear, f.bag, f.bow, 1);
        var old = new TransferRequest(UUID.randomUUID(), f.npc, TransferIntent.RETURN, List.of(legacy), List.of(), "", 100);
        var row = MinecraftApi.listOfType(old.identity(), "Legs", 10).getCompoundOrEmpty(0); assertEquals(Set.of("Source", "Destination", "Item", "Quantity"), row.keySet());
        var codec = new PhysicalItemOperationCodec(); var r = f.request(TransferIntent.RELOCATE, 0);
        for (var request : List.of(old, r)) {
            var value = new PhysicalItemOperation(PhysicalItemOperation.Intent.transfer(request), PhysicalItemOperation.Phase.INTENT, Optional.empty());
            var result = codec.read(codec.write(List.of(value))).iterator().next(); assertEquals(value.phase(), result.phase()); assertEquals(value.intent().identity(), result.intent().identity());
        }
        var changed = new TransferRequest(r.transferId(), r.initiatedBy(), r.intent(), List.of(new TransferLeg(f.gear, f.bag, f.bow, 1, 0, 0)), r.expectedRevisions(), r.authority(), r.requestedAt());
        assertNotEquals(r.identity(), changed.identity());
    }
    @Test void ordinaryIssueStillRequiresTransferRightsRatherThanCustody() {
        var f = new Fixture(); assertFalse(f.run(f.request(TransferIntent.ISSUE, 0)).successful());
        assertTrue(f.b.getItem(0).is(Items.BOW)); assertEquals("original-issue", f.rights.claimAt(f.gear, 0, f.bow).provenance());
    }
    @Test void internalMovementCannotBypassAnotherSystemsQuantityReservation() {
        var f = new Fixture(); var reservation = new AssetReservation(UUID.randomUUID(), f.gear, f.bow, 1, f.lord, "other-order", "reserved issue", 0, 1000);
        assertTrue(f.rights.reserve(reservation, 1, 0));
        assertFalse(f.run(f.request(TransferIntent.RELOCATE, 0)).successful());
        var sameToken = f.request(TransferIntent.RELOCATE, 0);
        assertFalse(f.run(new TransferRequest(sameToken.transferId(), sameToken.initiatedBy(), sameToken.intent(), sameToken.legs(), sameToken.expectedRevisions(), reservation.token(), 100)).successful());
        assertTrue(f.rights.reservations().contains(reservation)); assertTrue(f.a.getItem(2).is(Items.IRON_SWORD)); assertTrue(f.b.getItem(0).is(Items.BOW));
    }
    @Test void aPrivateIdenticalWeaponsReservationDoesNotBlockReturningSomeoneElsesLoan() {
        var f = new Fixture(); assertTrue(f.run(f.request(TransferIntent.RELOCATE, 0)).successful());
        var reserved = new AssetReservation(UUID.randomUUID(), f.bag, f.bow, 1, f.npc, "private-order", "private bow", 100, 1000);
        assertTrue(f.rights.reserve(reserved, 1, 100));
        var reverse = new TransferRequest(UUID.randomUUID(), f.npc, TransferIntent.RELOCATE,
                List.of(new TransferLeg(f.gear, f.bag, f.sword, 1, 0, 2), new TransferLeg(f.bag, f.gear, f.bow, 1, 2, 0)),
                List.of(new ExpectedRevision(f.bag, f.rights.context(f.bag).revision()), new ExpectedRevision(f.gear, f.rights.context(f.gear).revision())), reserved.token(), 100);
        assertTrue(f.run(reverse).successful()); assertTrue(f.a.getItem(0).is(Items.BOW));
        assertEquals(f.lord, f.rights.claimAt(f.gear, 0, f.bow).beneficialOwner()); assertTrue(f.rights.reservations().contains(reserved));
    }
    @Test void indexedTitleReadsStayReadOnlyAndFollowRemovalAndTransactionRollback() {
        var f = new Fixture(); f.rights.setDirty(false);
        var original = f.rights.claimAt(f.gear, 0, f.bow); assertNotNull(original); assertFalse(f.rights.isDirty());
        assertEquals(List.of(original), f.rights.claimsAt(f.gear, 0, f.bow)); assertFalse(f.rights.isDirty());
        var snapshot = f.rights.snapshotFor(Set.of(f.bag, f.gear));
        f.rights.removeClaim(original.claimId()); assertNull(f.rights.claimAt(f.gear, 0, f.bow));
        f.rights.restore(snapshot); assertEquals(original, f.rights.claimAt(f.gear, 0, f.bow));
        f.rights.removeClaimsForSlot(f.gear, 0); assertTrue(f.rights.claimsAt(f.gear, 0, f.bow).isEmpty());
        f.rights.putClaim(original); assertEquals(original, f.rights.claimAt(f.gear, 0, f.bow));
        f.rights.removeClaimsAt(f.gear, 0, f.bow); assertNull(f.rights.claimAt(f.gear, 0, f.bow));
    }
    @Test void anIssuingSwapWithoutOwnerEvidenceNeverReplaysOrStartsASecondMove() throws Exception {
        var f = new Fixture(); var intent = PhysicalItemOperation.Intent.transfer(f.request(TransferIntent.RELOCATE, 0));
        var path = directory.resolve("issuing.sgw");
        Files.write(path, new PhysicalItemOperationCodec().write(List.of(new PhysicalItemOperation(intent, PhysicalItemOperation.Phase.ISSUING, Optional.empty()))));
        var owner = new CompoundTag(); var endpoints = Map.of(f.bag, WitnessedItemEndpoint.forOwner(f.bag, f.a, () -> owner, () -> true, () -> {}),
                f.gear, WitnessedItemEndpoint.forOwner(f.gear, f.b, () -> owner, () -> true, () -> {}));
        try (var items = new PhysicalItemService(path, f.rights, f.ledger)) {
            assertEquals(PhysicalItemService.Status.UNKNOWN, items.submit(intent, endpoints, 100).status());
            assertEquals(PhysicalItemService.Status.PENDING, items.submit(PhysicalItemOperation.Intent.transfer(f.request(TransferIntent.RELOCATE, 0)), endpoints, 101).status());
            assertTrue(f.a.getItem(2).is(Items.IRON_SWORD)); assertTrue(f.b.getItem(0).is(Items.BOW)); assertTrue(items.hasUnresolved(f.bag));
        }
    }
    @Test void aLostCoSavedOwnerWitnessAfterASwapRequiresReviewInsteadOfAnotherSwap() throws Exception {
        var f = new Fixture(); var intent = PhysicalItemOperation.Intent.transfer(f.request(TransferIntent.RELOCATE, 0));
        var path = directory.resolve("applied.sgw"); var owner = new CompoundTag();
        var endpoints = Map.of(f.bag, WitnessedItemEndpoint.forOwner(f.bag, f.a, () -> owner, () -> true, () -> {}),
                f.gear, WitnessedItemEndpoint.forOwner(f.gear, f.b, () -> owner, () -> true, () -> {}));
        try (var items = new PhysicalItemService(path, f.rights, f.ledger)) {
            long limit = System.nanoTime() + 5_000_000_000L; PhysicalItemService.Reply reply;
            do { reply = items.submit(intent, endpoints, 100); if (reply.status() == PhysicalItemService.Status.APPLIED) break; LockSupport.parkNanos(1_000_000); } while (System.nanoTime() < limit);
            assertEquals(PhysicalItemService.Status.APPLIED, reply.status());
        }
        owner.remove("SagadysseyItemOperationWitnessesV1");
        try (var reopened = new PhysicalItemService(path, f.rights, f.ledger)) {
            assertEquals(PhysicalItemService.Status.UNKNOWN, reopened.submit(intent, endpoints, 101).status());
            assertTrue(f.a.getItem(2).is(Items.BOW)); assertTrue(f.b.getItem(0).is(Items.IRON_SWORD));
        }
    }
}
