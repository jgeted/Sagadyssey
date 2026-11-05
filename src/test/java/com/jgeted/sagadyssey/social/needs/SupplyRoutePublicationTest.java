package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SupplyRoutePublicationTest {
    @Test
    void fullRouteLedgerLeavesNoOrphanReservationOrReceipt() {
        var fixture = new Fixture();
        for (int index = 0; index < SupplyFlowData.MAX_ENTRIES; index++)
            fixture.flows.route(fixture.route(UUID.randomUUID()));
        var beforeNeeds = fixture.needs.save(new CompoundTag(), null);
        var beforeFlows = fixture.flows.save(new CompoundTag(), null);

        assertThrows(IllegalStateException.class, fixture::publish);

        assertEquals(beforeNeeds, fixture.needs.save(new CompoundTag(), null));
        assertEquals(beforeFlows, fixture.flows.save(new CompoundTag(), null));
        assertTrue(fixture.rights.reservations().isEmpty());
        assertTrue(fixture.logistics.orders().isEmpty());
    }

    @Test
    void conflictingManagedOrderDoesNotPublishRouteOrDemandReceipt() {
        var fixture = new Fixture();
        fixture.logistics.createManagedOrder(fixture.route.id(), fixture.source, fixture.destination,
                fixture.item, 1, fixture.principal.id(), fixture.need.id(), 0);
        var before = fixture.needs.save(new CompoundTag(), null);
        var original = fixture.logistics.order(fixture.route.id());

        assertThrows(IllegalStateException.class, fixture::publish);

        assertEquals(before, fixture.needs.save(new CompoundTag(), null));
        assertEquals(original, fixture.logistics.order(fixture.route.id()));
        assertNull(fixture.flows.routeOrNull(fixture.route.id()));
        assertTrue(fixture.rights.reservations().isEmpty());
    }

    @Test
    void fullReceiptLedgerRejectsBeforePublishingLinkedRecords() {
        var fixture = new Fixture();
        fixture.fillReceipts(NeedRequestData.MAX_RECEIPTS);
        var before = fixture.needs.save(new CompoundTag(), null);
        assertThrows(IllegalStateException.class, fixture::publish);
        assertEquals(before, fixture.needs.save(new CompoundTag(), null));
        assertTrue(fixture.logistics.orders().isEmpty());
        assertTrue(fixture.flows.routes().isEmpty());
        assertTrue(fixture.rights.reservations().isEmpty());
    }

    @Test
    void finalReceiptSlotIsNotConsumedWhenOrderValidationFails() {
        var fixture = new Fixture();
        fixture.fillReceipts(NeedRequestData.MAX_RECEIPTS - 1);
        fixture.logistics.createManagedOrder(fixture.route.id(), fixture.source, fixture.destination,
                fixture.item, 1, fixture.principal.id(), fixture.need.id(), 0);
        var before = fixture.needs.save(new CompoundTag(), null);
        assertThrows(IllegalStateException.class, fixture::publish);
        assertEquals(before, fixture.needs.save(new CompoundTag(), null));
        assertTrue(fixture.flows.routes().isEmpty());
        assertTrue(fixture.rights.reservations().isEmpty());
    }

    @Test
    void successfulPublicationAndReplayCreateExactlyOneObligation() {
        var fixture = new Fixture();
        assertTrue(fixture.publish());
        assertTrue(fixture.publish());
        assertEquals(2, fixture.needs.request(fixture.need.id()).reserved());
        assertEquals(1, fixture.flows.routes().size());
        assertEquals(1, fixture.logistics.orders().size());
        assertEquals(1, fixture.rights.reservations().size());
        assertEquals(1, fixture.needs.save(new CompoundTag(), null)
                .getList("Receipts", CompoundTag.TAG_COMPOUND).size());
    }

    @Test
    void unavailableStockHasNoLinkedSideEffects() {
        var fixture = new Fixture();
        assertFalse(SupplyFlowService.reserveRoute(fixture.rights, fixture.needs, fixture.flows,
                fixture.logistics, fixture.reservation, fixture.route, 1, 1));
        assertEquals(0, fixture.needs.request(fixture.need.id()).reserved());
        assertTrue(fixture.flows.routes().isEmpty());
        assertTrue(fixture.logistics.orders().isEmpty());
    }

    @Test void emergencyPublicationFailureHasNoLeaseRouteOrderReceiptOrFalseReopening() {
        var f = new EmergencyFixture(); var before = f.needs.save(new CompoundTag(), null);
        assertFalse(f.publish(1)); assertEquals(before, f.needs.save(new CompoundTag(), null));
        assertTrue(f.rights.reservations().isEmpty()); assertTrue(f.flows.routes().isEmpty()); assertTrue(f.logistics.orders().isEmpty());
        for (int i = 0; i < SupplyFlowData.MAX_ENTRIES; i++) f.flows.route(f.route(UUID.randomUUID())
                .progress(SupplyFlowData.Stage.CANCELLED, null, 0, 0, "closed records fill the route ledger"));
        assertThrows(IllegalStateException.class, () -> f.publish(4));
        assertEquals(before, f.needs.save(new CompoundTag(), null)); assertTrue(f.rights.reservations().isEmpty());
        assertTrue(f.logistics.orders().isEmpty()); assertNull(f.flows.routeOrNull(f.route.id()));
    }

    @Test void emergencyReplayAfterCancellationAndAssociatedDemandRouteReloadCannotRecreateLease() {
        var f = new EmergencyFixture(); assertTrue(f.publish(4));
        assertFalse(f.publish(4)); assertEquals(1, f.rights.reservations().size());
        f.needs.acknowledge(UUID.randomUUID(), f.need.id(), NeedRequestData.Action.RELEASE_RESERVED, 2, 41);
        f.rights.releaseReservations(f.route.token());
        f.flows.route(f.route.progress(SupplyFlowData.Stage.CANCELLED, null, 41, 3, "normal failed-path release"));
        assertTrue(f.logistics.cancelManagedOrder(f.route.id(), f.need.id(), 41));
        var restoredNeeds = NeedRequestData.load(f.needs.save(new CompoundTag(), null), null);
        var restoredFlows = SupplyFlowData.load(f.flows.save(new CompoundTag(), null), null);
        assertFalse(SupplyFlowService.reserveNaturalEmergencyRoute(f.rights, restoredNeeds, restoredFlows,
                f.logistics, f.reservation, f.route, 4, 42));
        assertTrue(f.rights.reservations().isEmpty()); assertEquals(0, restoredNeeds.request(f.need.id()).reserved());
        assertEquals(32, restoredNeeds.request(f.need.id()).attempts()); assertEquals(f.need.createdAt(), restoredNeeds.request(f.need.id()).createdAt());
        assertEquals(1, restoredFlows.routes().size()); assertEquals(SupplyFlowData.Stage.CANCELLED, restoredFlows.route(f.route.id()).stage());
    }

    @Test void emergencyNeverBypassesEarlierReviewOrHeldRoute() {
        for (var state : new SupplyFlowData.Stage[]{SupplyFlowData.Stage.HELD, SupplyFlowData.Stage.REVIEW}) {
            var f = new EmergencyFixture(); var old = f.route(UUID.randomUUID()).progress(state, null, 0, 0, "original unresolved duty");
            f.flows.route(old); var before = f.needs.save(new CompoundTag(), null);
            assertFalse(f.publish(4)); assertEquals(before, f.needs.save(new CompoundTag(), null));
            assertEquals(old, f.flows.route(old.id())); assertTrue(f.rights.reservations().isEmpty());
            assertTrue(f.logistics.orders().isEmpty()); assertNull(f.flows.routeOrNull(f.route.id()));
        }
    }

    @Test void emergencyLinkedConflictIsRejectedBeforeAnyNewLease() {
        var f = new EmergencyFixture();
        f.logistics.createManagedOrder(f.route.id(), f.source, f.destination, f.item, 1, f.principal.id(), f.need.id(), 0);
        var before = f.needs.save(new CompoundTag(), null); var order = f.logistics.order(f.route.id());
        assertThrows(IllegalStateException.class, () -> f.publish(4));
        assertEquals(before, f.needs.save(new CompoundTag(), null)); assertEquals(order, f.logistics.order(f.route.id()));
        assertTrue(f.rights.reservations().isEmpty()); assertNull(f.flows.routeOrNull(f.route.id()));
        var orphan = new EmergencyFixture(); orphan.needs.acknowledgeEmergencyReserveAndPublish(orphan.need.id(), 2, 40, () -> {});
        assertThrows(IllegalStateException.class, () -> orphan.publish(4));
        assertTrue(orphan.rights.reservations().isEmpty()); assertTrue(orphan.flows.routes().isEmpty());
    }

    @Test void fullEmergencyReceiptLedgerLeavesNoNewLinkedState() {
        var f = new EmergencyFixture();
        var filler = f.needs.openOrUpdate(new NeedRequest.Key(f.principal.id(), "ordinary-receipt-capacity",
                f.item, f.destination, 0, 1000), 1, 4, 0);
        for (int i = 0; i < NeedRequestData.MAX_RECEIPTS; i++) f.needs.acknowledge(UUID.randomUUID(), filler.id(),
                i % 2 == 0 ? NeedRequestData.Action.RESERVE : NeedRequestData.Action.RELEASE_RESERVED, 1, 0);
        var before = f.needs.save(new CompoundTag(), null);
        assertThrows(IllegalStateException.class, () -> f.publish(4));
        assertEquals(before, f.needs.save(new CompoundTag(), null)); assertFalse(f.needs.emergencyReserved(f.need.id()));
        assertTrue(f.rights.reservations().isEmpty()); assertTrue(f.flows.routes().isEmpty()); assertTrue(f.logistics.orders().isEmpty());
    }

    private static final class EmergencyFixture {
        final RightsStateData rights = new RightsStateData(); final NeedRequestData needs = new NeedRequestData();
        final SupplyFlowData flows = new SupplyFlowData(); final LogisticsData logistics = new LogisticsData();
        final UUID source = UUID.randomUUID(), destination = UUID.randomUUID();
        final SubjectRef principal = SubjectRef.household(UUID.randomUUID());
        final ItemFingerprint item = new ItemFingerprint("minecraft:carrot", 0);
        final NeedRequest need; final SupplyFlowData.Route route; final AssetReservation reservation;
        EmergencyFixture() {
            need = needs.openOrUpdate(new NeedRequest.Key(principal.id(), "HOUSEHOLD:household:natural-diet:" + principal.id(),
                    item, destination, 0, 1000), 4, 32, 0);
            for (int i = 1; i <= 32; i++) needs.failedAttempt(need.id(), i, "original finite budget");
            needs.transition(need.id(), NeedRequestStatus.WAITING_EXTERNAL, 33, "no legal supply yet");
            flows.register(new FieldworkStorage("minecraft:overworld", new BlockPos(0, 0, 0), source, principal,
                    AccessPolicy.HOUSEHOLD, TransferIntent.DELIVERY, "controlled source"));
            flows.register(new FieldworkStorage("minecraft:overworld", new BlockPos(1, 0, 0), destination, principal,
                    AccessPolicy.HOUSEHOLD, TransferIntent.DELIVERY, "controlled target"));
            flows.demand(need.id(), principal, destination);
            route = route(NeedRequestData.emergencyRouteId(need.id()));
            reservation = new AssetReservation(SupplyFlowService.receipt(route.id(), "lease"), source, item, 2,
                    principal, route.token(), "test real reserve", 40, 100);
        }
        SupplyFlowData.Route route(UUID id) { return new SupplyFlowData.Route(id, need.id(), source, destination,
                principal, 2, null, null, SupplyFlowData.Stage.HELD, 40, 0, null, "test"); }
        boolean publish(int physical) { return SupplyFlowService.reserveNaturalEmergencyRoute(rights, needs, flows,
                logistics, reservation, route, physical, 40); }
    }

    private static final class Fixture {
        final RightsStateData rights = new RightsStateData();
        final NeedRequestData needs = new NeedRequestData();
        final SupplyFlowData flows = new SupplyFlowData();
        final LogisticsData logistics = new LogisticsData();
        final UUID source = UUID.randomUUID();
        final UUID destination = UUID.randomUUID();
        final SubjectRef principal = SubjectRef.character(UUID.randomUUID());
        final ItemFingerprint item = new ItemFingerprint("minecraft:bread", 0);
        final NeedRequest need = needs.openOrUpdate(new NeedRequest.Key(principal.id(),
                "publication-test", item, destination, 0, 1000), 4, 4, 0);
        final SupplyFlowData.Route route = route(UUID.randomUUID());
        final AssetReservation reservation = new AssetReservation(UUID.randomUUID(), source, item,
                2, principal, route.token(), "test", 0, 100);

        SupplyFlowData.Route route(UUID id) {
            return new SupplyFlowData.Route(id, need.id(), source, destination, principal,
                    2, null, null, SupplyFlowData.Stage.HELD, 0, 0, null, "test");
        }

        boolean publish() {
            return SupplyFlowService.reserveRoute(rights, needs, flows, logistics,
                    reservation, route, 4, 1);
        }

        void fillReceipts(int count) {
            var filler = needs.openOrUpdate(new NeedRequest.Key(principal.id(),
                    "receipt-capacity", item, destination, 0, 1000), 1, 4, 0);
            for (int index = 0; index < count; index++)
                needs.acknowledge(UUID.randomUUID(), filler.id(),
                        index % 2 == 0 ? NeedRequestData.Action.RESERVE
                                : NeedRequestData.Action.RELEASE_RESERVED, 1, 0);
        }
    }
}
