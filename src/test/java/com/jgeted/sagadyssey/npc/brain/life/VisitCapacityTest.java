package com.jgeted.sagadyssey.npc.brain.life;

import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationService;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class VisitCapacityTest {
    @Test void capacityPreventsOverbooking() {
        var leases = new NpcReservationService();
        assertNotNull(VisitCapacity.acquire(leases, "overworld:table", 2, UUID.randomUUID(), UUID.randomUUID(), 10, 100));
        assertNotNull(VisitCapacity.acquire(leases, "overworld:table", 2, UUID.randomUUID(), UUID.randomUUID(), 10, 100));
        assertNull(VisitCapacity.acquire(leases, "overworld:table", 2, UUID.randomUUID(), UUID.randomUUID(), 10, 100));
    }
    @Test void resumedPlanRetainsSlotWithoutTakingSecondSeat() {
        var leases = new NpcReservationService(); var firstPlan = UUID.randomUUID();
        VisitCapacity.acquire(leases, "table", 2, firstPlan, UUID.randomUUID(), 10, 100);
        var plan = UUID.randomUUID(); var npc = UUID.randomUUID();
        var seat = VisitCapacity.acquire(leases, "table", 2, plan, npc, 10, 100);
        leases.releasePlan(firstPlan);
        assertEquals(seat, VisitCapacity.acquire(leases, "table", 2, plan, npc, 20, 200));
        assertEquals(1, leases.reservationsForPlan(plan).size());
    }
    @Test void expiryReturnsCapacity() {
        var leases = new NpcReservationService(); var plan = UUID.randomUUID();
        var seat = VisitCapacity.acquire(leases, "bed", 1, plan, UUID.randomUUID(), 10, 100);
        assertEquals(seat, VisitCapacity.acquire(leases, "bed", 1, UUID.randomUUID(), UUID.randomUUID(), 100, 200));
    }
    @Test void invalidOrExpiredLeaseRejectedEvenForCurrentOwner() {
        var leases = new NpcReservationService(); var plan = UUID.randomUUID(); var npc = UUID.randomUUID();
        VisitCapacity.acquire(leases, "bed", 1, plan, npc, 10, 100);
        assertThrows(IllegalArgumentException.class, () -> VisitCapacity.acquire(leases, "bed", 1, plan, npc, 20, 20));
        assertThrows(IllegalArgumentException.class, () -> VisitCapacity.acquire(leases, "bed", 0, plan, npc, 20, 100));
    }
}
