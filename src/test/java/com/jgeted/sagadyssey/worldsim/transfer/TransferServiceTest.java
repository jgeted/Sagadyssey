package com.jgeted.sagadyssey.worldsim.transfer;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.AssetClaim;
import com.jgeted.sagadyssey.worldsim.rights.AssetReservation;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransferServiceTest {
    @Test
    void giftIsAtomicConservativeAndIdempotent() {
        Fixture fixture = new Fixture();
        fixture.source.setItem(0, new ItemStack(Items.BREAD, 12));
        UUID transferId = UUID.randomUUID();
        TransferRequest request = fixture.request(transferId, fixture.owner, TransferIntent.GIFT,
                fixture.sourceId, fixture.destinationId, new ItemStack(Items.BREAD), 7, 0L, 0L);

        TransferReceipt first = fixture.service.submit(request, fixture.endpoints(), 20L);
        TransferReceipt replay = fixture.service.submit(request, fixture.endpoints(), 21L);

        assertTrue(first.successful());
        assertEquals(first, replay);
        assertEquals(5, fixture.source.getItem(0).getCount());
        assertEquals(7, fixture.destination.getItem(0).getCount());
        assertEquals(12, count(fixture.source, Items.BREAD) + count(fixture.destination, Items.BREAD));
        assertEquals(1L, fixture.rights.context(fixture.sourceId).revision());
        assertEquals(1L, fixture.rights.context(fixture.destinationId).revision());
        assertEquals(1, fixture.ledger.audits().size());
    }

    @Test
    void capacityFailureHasZeroSideEffects() {
        Fixture fixture = new Fixture(1, 1);
        fixture.source.setItem(0, new ItemStack(Items.BREAD, 4));
        fixture.destination.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        TransferRequest request = fixture.request(UUID.randomUUID(), fixture.owner, TransferIntent.GIFT,
                fixture.sourceId, fixture.destinationId, new ItemStack(Items.BREAD), 4, 0L, 0L);

        TransferReceipt receipt = fixture.service.submit(request, fixture.endpoints(), 30L);

        assertEquals(TransferReceipt.Status.CAPACITY_EXCEEDED, receipt.status());
        assertEquals(4, fixture.source.getItem(0).getCount());
        assertTrue(fixture.destination.getItem(0).is(Items.COBBLESTONE));
        assertEquals(0L, fixture.rights.context(fixture.sourceId).revision());
        assertEquals(0L, fixture.rights.context(fixture.destinationId).revision());
    }

    @Test
    void laterLegFailureRollsBackTheWholeProposal() {
        Fixture fixture = new Fixture(2, 1);
        fixture.source.setItem(0, new ItemStack(Items.BREAD, 4));
        fixture.source.setItem(1, new ItemStack(Items.DIAMOND));
        TransferRequest request = new TransferRequest(UUID.randomUUID(), fixture.owner,
                TransferIntent.GIFT,
                List.of(new TransferLeg(fixture.sourceId, fixture.destinationId,
                                ItemFingerprint.of(new ItemStack(Items.BREAD)), 4),
                        new TransferLeg(fixture.sourceId, fixture.destinationId,
                                ItemFingerprint.of(new ItemStack(Items.DIAMOND)), 1)),
                List.of(new ExpectedRevision(fixture.sourceId, 0L),
                        new ExpectedRevision(fixture.destinationId, 0L)), "two-leg-test", 10L);

        TransferReceipt receipt = fixture.service.submit(request, fixture.endpoints(), 31L);

        assertEquals(TransferReceipt.Status.CAPACITY_EXCEEDED, receipt.status());
        assertEquals(4, count(fixture.source, Items.BREAD));
        assertEquals(1, count(fixture.source, Items.DIAMOND));
        assertTrue(fixture.destination.isEmpty());
        assertEquals(0L, fixture.rights.context(fixture.sourceId).revision());
        assertEquals(0L, fixture.rights.context(fixture.destinationId).revision());
    }

    @Test
    void issuedWeaponRemainsOwnersPropertyAndCustodianCannotSellIt() {
        Fixture fixture = new Fixture();
        fixture.source.setItem(0, new ItemStack(Items.BOW));
        TransferRequest issue = fixture.request(UUID.randomUUID(), fixture.owner, TransferIntent.ISSUE,
                fixture.sourceId, fixture.destinationId, new ItemStack(Items.BOW), 1, 0L, 0L);
        assertTrue(fixture.service.submit(issue, fixture.endpoints(), 40L).successful());

        AssetClaim claim = fixture.rights.claimAt(fixture.destinationId, 0,
                ItemFingerprint.of(fixture.destination.getItem(0)));
        assertNotNull(claim);
        assertEquals(fixture.owner, claim.beneficialOwner());
        assertEquals(fixture.receiver, claim.custodian());
        assertTrue(claim.encumbrances().contains("issued"));

        UUID marketId = UUID.randomUUID();
        SimpleContainer market = new SimpleContainer(9);
        fixture.rights.ensureContext(marketId, "test:market", fixture.market,
                fixture.market, AccessPolicy.PRIVATE);
        TransferRequest sale = fixture.request(UUID.randomUUID(), fixture.receiver, TransferIntent.GIFT,
                fixture.destinationId, marketId, new ItemStack(Items.BOW), 1, 1L, 0L);
        Map<UUID, TransferEndpoint> endpoints = new java.util.HashMap<>(fixture.endpoints());
        endpoints.put(marketId, new TransferEndpoint(marketId, market));

        TransferReceipt denied = fixture.service.submit(sale, endpoints, 41L);
        assertEquals(TransferReceipt.Status.RIGHTS_DENIED, denied.status());
        assertTrue(fixture.destination.getItem(0).is(Items.BOW));
        assertTrue(market.isEmpty());
    }

    @Test
    void loanPreservesTitleAndCarriesAUniqueReturnObligation() {
        Fixture fixture = new Fixture();
        fixture.source.setItem(0, new ItemStack(Items.SPYGLASS));
        UUID transferId = UUID.randomUUID();
        TransferRequest loan = fixture.request(transferId, fixture.owner, TransferIntent.LOAN,
                fixture.sourceId, fixture.destinationId, new ItemStack(Items.SPYGLASS), 1, 0L, 0L);

        assertTrue(fixture.service.submit(loan, fixture.endpoints(), 45L).successful());

        AssetClaim claim = fixture.rights.claimAt(fixture.destinationId, 0,
                ItemFingerprint.of(fixture.destination.getItem(0)));
        assertNotNull(claim);
        assertEquals(fixture.owner, claim.beneficialOwner());
        assertEquals(fixture.receiver, claim.custodian());
        assertTrue(claim.encumbrances().contains("loan:" + transferId));
    }

    @Test
    void physicalPaymentTransfersTitleToReceiver() {
        Fixture fixture = new Fixture();
        fixture.source.setItem(0, new ItemStack(Items.EMERALD, 4));
        TransferRequest payment = fixture.request(UUID.randomUUID(), fixture.owner, TransferIntent.PAYMENT,
                fixture.sourceId, fixture.destinationId, new ItemStack(Items.EMERALD), 3, 0L, 0L);

        assertTrue(fixture.service.submit(payment, fixture.endpoints(), 46L).successful());
        assertEquals(1, count(fixture.source, Items.EMERALD));
        assertEquals(3, count(fixture.destination, Items.EMERALD));
        // Default destination metadata means the receiver owns and possesses the payment.
        assertNull(fixture.rights.claimAt(fixture.destinationId, 0,
                ItemFingerprint.of(fixture.destination.getItem(0))));
    }

    @Test
    void theftChangesCustodyButNotBeneficialOwnership() {
        Fixture fixture = new Fixture();
        fixture.source.setItem(0, new ItemStack(Items.EMERALD, 3));
        TransferRequest theft = fixture.request(UUID.randomUUID(), fixture.receiver, TransferIntent.THEFT,
                fixture.sourceId, fixture.destinationId, new ItemStack(Items.EMERALD), 2, 0L, 0L);

        TransferReceipt receipt = fixture.service.submit(theft, fixture.endpoints(), 50L);

        assertTrue(receipt.successful());
        assertFalse(receipt.lawful());
        AssetClaim claim = fixture.rights.claimAt(fixture.destinationId, 0,
                ItemFingerprint.of(fixture.destination.getItem(0)));
        assertNotNull(claim);
        assertEquals(fixture.owner, claim.beneficialOwner());
        assertEquals(fixture.receiver, claim.custodian());
        assertTrue(claim.encumbrances().contains("unlawful_possession"));
    }

    @Test
    void staleCompetitorCannotDuplicateLastItem() {
        Fixture fixture = new Fixture();
        fixture.source.setItem(0, new ItemStack(Items.DIAMOND));
        UUID otherDestinationId = UUID.randomUUID();
        SimpleContainer otherDestination = new SimpleContainer(9);
        SubjectRef other = SubjectRef.character(UUID.randomUUID());
        fixture.rights.ensureContext(otherDestinationId, "test:other", other, other, AccessPolicy.PRIVATE);
        Map<UUID, TransferEndpoint> endpoints = new java.util.HashMap<>(fixture.endpoints());
        endpoints.put(otherDestinationId, new TransferEndpoint(otherDestinationId, otherDestination));

        TransferRequest first = fixture.request(UUID.randomUUID(), fixture.owner, TransferIntent.GIFT,
                fixture.sourceId, fixture.destinationId, new ItemStack(Items.DIAMOND), 1, 0L, 0L);
        TransferRequest competitor = fixture.request(UUID.randomUUID(), fixture.owner, TransferIntent.GIFT,
                fixture.sourceId, otherDestinationId, new ItemStack(Items.DIAMOND), 1, 0L, 0L);

        assertTrue(fixture.service.submit(first, endpoints, 60L).successful());
        assertEquals(TransferReceipt.Status.STALE_REVISION,
                fixture.service.submit(competitor, endpoints, 61L).status());
        assertEquals(1, count(fixture.destination, Items.DIAMOND));
        assertEquals(0, count(otherDestination, Items.DIAMOND));
    }

    @Test
    void fullInventoriesCanStillExchangeTheSlotsTheyVacate() {
        Fixture fixture = new Fixture(1, 1);
        fixture.source.setItem(0, new ItemStack(Items.BREAD));
        fixture.destination.setItem(0, new ItemStack(Items.EMERALD));
        TransferRequest swap = new TransferRequest(UUID.randomUUID(), fixture.owner,
                TransferIntent.PAYMENT,
                List.of(new TransferLeg(fixture.sourceId, fixture.destinationId,
                                ItemFingerprint.of(new ItemStack(Items.BREAD)), 1),
                        new TransferLeg(fixture.destinationId, fixture.sourceId,
                                ItemFingerprint.of(new ItemStack(Items.EMERALD)), 1)),
                List.of(new ExpectedRevision(fixture.sourceId, 0L),
                        new ExpectedRevision(fixture.destinationId, 0L)), "atomic-swap", 10L,
                Map.of(fixture.sourceId, fixture.owner,
                        fixture.destinationId, fixture.receiver));

        TransferReceipt receipt = fixture.service.submit(swap, fixture.endpoints(), 70L);

        assertTrue(receipt.successful());
        assertTrue(fixture.source.getItem(0).is(Items.EMERALD));
        assertTrue(fixture.destination.getItem(0).is(Items.BREAD));
    }

    @Test
    void authorizedOwnerSkipsAnIdenticalBorrowedBatch() {
        Fixture fixture = new Fixture(2, 2);
        fixture.source.setItem(0, new ItemStack(Items.COAL, 2));
        fixture.source.setItem(1, new ItemStack(Items.COAL, 3));
        SubjectRef lender = SubjectRef.character(UUID.randomUUID());
        fixture.rights.putClaim(new AssetClaim(UUID.randomUUID(), fixture.sourceId, 0,
                ItemFingerprint.of(fixture.source.getItem(0)), 2, lender, fixture.owner,
                java.util.Set.of("loan:test"), java.util.Set.of(), "borrowed", 0L));
        TransferRequest request = fixture.request(UUID.randomUUID(), fixture.owner,
                TransferIntent.DELIVERY, fixture.sourceId, fixture.destinationId,
                new ItemStack(Items.COAL), 3, 0L, 0L);

        TransferReceipt receipt = fixture.service.submit(request, fixture.endpoints(), 80L);

        assertTrue(receipt.successful());
        assertEquals(2, fixture.source.getItem(0).getCount());
        assertEquals(0, fixture.source.getItem(1).getCount());
        assertEquals(3, count(fixture.destination, Items.COAL));
    }

    @Test
    void productionReservationBlocksTradeButItsOwnBatchCanConsumeIt() {
        Fixture fixture = new Fixture();
        fixture.source.setItem(0, new ItemStack(Items.IRON_INGOT, 4));
        ItemFingerprint iron = ItemFingerprint.of(new ItemStack(Items.IRON_INGOT));
        String token = "production:test-batch";
        assertTrue(fixture.rights.reserve(new AssetReservation(UUID.randomUUID(), fixture.sourceId,
                iron, 3, fixture.owner, token, "锻造工具", 0L, 200L), 4, 0L));
        TransferRequest competingTrade = fixture.request(UUID.randomUUID(), fixture.owner,
                TransferIntent.PAYMENT, fixture.sourceId, fixture.destinationId,
                new ItemStack(Items.IRON_INGOT), 2, 0L, 0L);

        assertEquals(TransferReceipt.Status.RIGHTS_DENIED,
                fixture.service.submit(competingTrade, fixture.endpoints(), 10L).status());
        assertEquals(4, count(fixture.source, Items.IRON_INGOT));

        TransferRequest production = new TransferRequest(UUID.randomUUID(), fixture.owner,
                TransferIntent.DELIVERY,
                List.of(new TransferLeg(fixture.sourceId, fixture.destinationId, iron, 3)),
                List.of(new ExpectedRevision(fixture.sourceId, 0L),
                        new ExpectedRevision(fixture.destinationId, 0L)), token, 11L);
        assertTrue(fixture.service.submit(production, fixture.endpoints(), 11L).successful());
        assertEquals(0, fixture.rights.reservedQuantity(fixture.sourceId, iron, null, 12L));
        assertEquals(1, count(fixture.source, Items.IRON_INGOT));
    }

    @Test
    void reservationProtectsTheCorrectOwnersIdenticalBatch() {
        Fixture fixture = new Fixture(2, 2);
        fixture.source.setItem(0, new ItemStack(Items.BREAD, 2));
        fixture.source.setItem(1, new ItemStack(Items.BREAD, 2));
        SubjectRef secondOwner = SubjectRef.character(UUID.randomUUID());
        ItemFingerprint bread = ItemFingerprint.of(fixture.source.getItem(0));
        fixture.rights.putClaim(new AssetClaim(UUID.randomUUID(), fixture.sourceId, 1,
                bread, 2, secondOwner, fixture.owner, java.util.Set.of(),
                java.util.Set.of(), "second-owner", 0L));
        assertTrue(fixture.rights.reserve(new AssetReservation(UUID.randomUUID(), fixture.sourceId,
                bread, 2, fixture.owner, "production:first", "reserved first batch",
                0L, 200L), 4, 0L));
        TransferRequest secondOwnersTransfer = fixture.request(UUID.randomUUID(), secondOwner,
                TransferIntent.DELIVERY, fixture.sourceId, fixture.destinationId,
                new ItemStack(Items.BREAD), 1, 0L, 0L);

        TransferReceipt receipt = fixture.service.submit(secondOwnersTransfer,
                fixture.endpoints(), 90L);

        assertTrue(receipt.successful());
        assertEquals(2, fixture.source.getItem(0).getCount());
        assertEquals(1, fixture.source.getItem(1).getCount());
        assertEquals(1, count(fixture.destination, Items.BREAD));
    }

    @Test
    void reusedIdWithDifferentIntentCannotReuseOrOverwriteSuccess() {
        Fixture fixture = new Fixture();
        fixture.source.setItem(0, new ItemStack(Items.BREAD, 12));
        var request = fixture.request(UUID.randomUUID(), fixture.owner, TransferIntent.GIFT,
                fixture.sourceId, fixture.destinationId, new ItemStack(Items.BREAD), 3, 0, 0);
        var first = fixture.service.submit(request, fixture.endpoints(), 20);
        var changedQuantity = fixture.request(request.transferId(), fixture.owner, TransferIntent.GIFT,
                fixture.sourceId, fixture.destinationId, new ItemStack(Items.BREAD), 4, 0, 0);
        var changedActor = new TransferRequest(request.transferId(), fixture.receiver, request.intent(),
                request.legs(), request.expectedRevisions(), request.authority(), request.requestedAt());
        var changedAuthority = new TransferRequest(request.transferId(), request.initiatedBy(), request.intent(),
                request.legs(), request.expectedRevisions(), "different", request.requestedAt());
        var changedSourceAuthority = new TransferRequest(request.transferId(), request.initiatedBy(),
                request.intent(), request.legs(), request.expectedRevisions(), request.authority(),
                request.requestedAt(), Map.of(fixture.sourceId, fixture.receiver));
        var changedEndpoint = fixture.request(request.transferId(), fixture.owner, TransferIntent.GIFT,
                fixture.sourceId, UUID.randomUUID(), new ItemStack(Items.BREAD), 3, 0, 0);
        var changedRevision = fixture.request(request.transferId(), fixture.owner, TransferIntent.GIFT,
                fixture.sourceId, fixture.destinationId, new ItemStack(Items.BREAD), 3, 1, 1);
        for (var changed : List.of(changedQuantity, changedActor, changedAuthority,
                changedSourceAuthority, changedEndpoint, changedRevision)) {
            assertEquals(TransferReceipt.Status.INVALID,
                    fixture.service.submit(changed, fixture.endpoints(), 21).status());
        }
        assertEquals(first, fixture.ledger.receipt(request.transferId()));
        assertEquals(1, fixture.ledger.audits().size());
        assertEquals(9, count(fixture.source, Items.BREAD));
        assertEquals(3, count(fixture.destination, Items.BREAD));
    }

    @Test
    void replayIdentitySurvivesSaveLoadAndRetryTimestampChanges() {
        Fixture fixture = new Fixture();
        fixture.source.setItem(0, new ItemStack(Items.BREAD, 12));
        var request = fixture.request(UUID.randomUUID(), fixture.owner, TransferIntent.GIFT,
                fixture.sourceId, fixture.destinationId, new ItemStack(Items.BREAD), 3, 0, 0);
        var first = fixture.service.submit(request, fixture.endpoints(), 20);
        var restored = TransferLedgerData.load(
                fixture.ledger.save(new net.minecraft.nbt.CompoundTag(), null), null);
        var service = new TransferService(fixture.rights, restored);
        var retry = new TransferRequest(request.transferId(), request.initiatedBy(), request.intent(),
                request.legs(), request.expectedRevisions().reversed(), request.authority(), 99);
        assertEquals(first, service.submit(retry, fixture.endpoints(), 100));
        var conflict = fixture.request(request.transferId(), fixture.owner, TransferIntent.GIFT,
                fixture.sourceId, fixture.destinationId, new ItemStack(Items.BREAD), 4, 0, 0);
        assertEquals(TransferReceipt.Status.INVALID,
                service.submit(conflict, fixture.endpoints(), 101).status());
        assertEquals(3, count(fixture.destination, Items.BREAD));
    }

    @Test
    void legacyReceiptKeepsOriginalEvidenceWithoutInventingPayload() {
        Fixture fixture = new Fixture();
        fixture.source.setItem(0, new ItemStack(Items.BREAD, 4));
        var request = fixture.request(UUID.randomUUID(), fixture.owner, TransferIntent.GIFT,
                fixture.sourceId, fixture.destinationId, new ItemStack(Items.BREAD), 2, 0, 0);
        var first = fixture.service.submit(request, fixture.endpoints(), 20);
        var tag = fixture.ledger.save(new net.minecraft.nbt.CompoundTag(), null);
        MinecraftApi.listOfType(tag, "Receipts", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompoundOrEmpty(0).remove("RequestIdentity");
        var legacy = TransferLedgerData.load(tag, null);
        assertEquals(first, new TransferService(fixture.rights, legacy)
                .submit(request, fixture.endpoints(), 21));
        assertFalse(MinecraftApi.listOfType(legacy.save(new net.minecraft.nbt.CompoundTag(), null), "Receipts", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .getCompoundOrEmpty(0).contains("RequestIdentity"));
        assertEquals(2, count(fixture.destination, Items.BREAD));
    }

    @Test
    void concurrentServiceInstancesCannotApplyTheSameTransferTwice() throws Exception {
        Fixture fixture = new Fixture();
        fixture.source.setItem(0, new ItemStack(Items.BREAD, 12));
        var request = fixture.request(UUID.randomUUID(), fixture.owner, TransferIntent.GIFT,
                fixture.sourceId, fixture.destinationId, new ItemStack(Items.BREAD), 7, 0, 0);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<TransferReceipt> action = () -> {
                start.await();
                return new TransferService(fixture.rights, fixture.ledger)
                        .submit(request, fixture.endpoints(), 20);
            };
            var first = pool.submit(action);
            var second = pool.submit(action);
            start.countDown();
            assertTrue(first.get(5, java.util.concurrent.TimeUnit.SECONDS).successful());
            assertEquals(first.get(), second.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(5, count(fixture.source, Items.BREAD));
        assertEquals(7, count(fixture.destination, Items.BREAD));
        assertEquals(1, fixture.ledger.audits().size());
    }

    private static int count(SimpleContainer container, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) total += container.getItem(slot).getCount();
        }
        return total;
    }

    private static final class Fixture {
        final RightsStateData rights = new RightsStateData();
        final TransferLedgerData ledger = new TransferLedgerData();
        final TransferService service = new TransferService(rights, ledger);
        final UUID sourceId = UUID.randomUUID();
        final UUID destinationId = UUID.randomUUID();
        final SubjectRef owner = SubjectRef.character(UUID.randomUUID());
        final SubjectRef receiver = SubjectRef.character(UUID.randomUUID());
        final SubjectRef market = SubjectRef.institution(UUID.randomUUID());
        final SimpleContainer source;
        final SimpleContainer destination;

        Fixture() { this(9, 9); }

        Fixture(int sourceSize, int destinationSize) {
            source = new SimpleContainer(sourceSize);
            destination = new SimpleContainer(destinationSize);
            rights.ensureContext(sourceId, "test:source", owner, owner, AccessPolicy.PRIVATE);
            rights.ensureContext(destinationId, "test:destination", receiver, receiver, AccessPolicy.PRIVATE);
        }

        Map<UUID, TransferEndpoint> endpoints() {
            return Map.of(sourceId, new TransferEndpoint(sourceId, source),
                    destinationId, new TransferEndpoint(destinationId, destination));
        }

        TransferRequest request(UUID id, SubjectRef actor, TransferIntent intent,
                                UUID from, UUID to, ItemStack sample, int count,
                                long fromRevision, long toRevision) {
            return new TransferRequest(id, actor, intent,
                    List.of(new TransferLeg(from, to, ItemFingerprint.of(sample), count)),
                    List.of(new ExpectedRevision(from, fromRevision), new ExpectedRevision(to, toRevision)),
                    "test", 10L);
        }
    }
}
