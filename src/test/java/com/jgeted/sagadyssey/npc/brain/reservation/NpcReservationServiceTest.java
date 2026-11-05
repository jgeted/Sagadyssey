package com.jgeted.sagadyssey.npc.brain.reservation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NpcReservationServiceTest {
    @Test
    void acquisitionIsExclusiveAndSamePlanIsIdempotent() {
        NpcReservationService service = new NpcReservationService();
        NpcReservationKey key = new NpcReservationKey("workstation", "overworld:12,64,9");
        UUID firstPlan = UUID.randomUUID();
        UUID firstNpc = UUID.randomUUID();

        NpcReservationAcquireResult acquired = service.acquire(
                key, firstPlan, firstNpc, 10L, 20L);
        NpcReservationAcquireResult repeated = service.acquire(
                key, firstPlan, firstNpc, 11L, 30L);
        NpcReservationAcquireResult conflict = service.acquire(
                key, UUID.randomUUID(), UUID.randomUUID(), 12L, 30L);

        assertEquals(NpcReservationAcquireResult.Status.ACQUIRED, acquired.status());
        assertEquals(NpcReservationAcquireResult.Status.ALREADY_HELD, repeated.status());
        assertEquals(20L, repeated.reservation().expiresAt());
        assertEquals(NpcReservationAcquireResult.Status.CONFLICT, conflict.status());
        assertFalse(conflict.acquired());
        assertEquals(firstPlan, conflict.reservation().planId());
    }

    @Test
    void releasePlanReturnsEveryReleasedReservationForAudit() {
        NpcReservationService service = new NpcReservationService();
        UUID planId = UUID.randomUUID();
        UUID npcId = UUID.randomUUID();
        service.acquire(new NpcReservationKey("seat", "one"), planId, npcId, 10L, 30L);
        service.acquire(new NpcReservationKey("seat", "two"), planId, npcId, 10L, 30L);

        List<NpcReservation> released = service.releasePlan(planId);

        assertEquals(2, released.size());
        assertTrue(service.reservationsForPlan(planId).isEmpty());
        assertTrue(service.snapshot().isEmpty());
        assertThrows(UnsupportedOperationException.class, released::clear);
    }

    @Test
    void expirationUsesBoundaryTickAndKeepsStillValidReservations() {
        NpcReservationService service = new NpcReservationService();
        NpcReservationKey early = new NpcReservationKey("target", "early");
        NpcReservationKey late = new NpcReservationKey("target", "late");
        service.acquire(early, UUID.randomUUID(), UUID.randomUUID(), 10L, 20L);
        service.acquire(late, UUID.randomUUID(), UUID.randomUUID(), 10L, 21L);

        assertTrue(service.cleanupExpired(19L).isEmpty());
        List<NpcReservation> expired = service.cleanupExpired(20L);

        assertEquals(List.of(early), expired.stream().map(NpcReservation::key).toList());
        assertTrue(service.find(early, 20L).isEmpty());
        assertTrue(service.find(late, 20L).isPresent());
    }

    @Test
    void expiredHolderDoesNotBlockNewAcquisition() {
        NpcReservationService service = new NpcReservationService();
        NpcReservationKey key = new NpcReservationKey("conversation_partner", "npc-a");
        service.acquire(key, UUID.randomUUID(), UUID.randomUUID(), 1L, 5L);

        NpcReservationAcquireResult result = service.acquire(
                key, UUID.randomUUID(), UUID.randomUUID(), 5L, 10L);

        assertEquals(NpcReservationAcquireResult.Status.ACQUIRED, result.status());
    }

    @Test
    void workstationCompetitionCannotRenewOrStealTheCurrentLease() {
        NpcReservationService service = new NpcReservationService();
        NpcReservationKey workstation = new NpcReservationKey(
                "workstation", "minecraft:overworld:forge-1");
        UUID firstPlan = UUID.randomUUID();
        UUID firstNpc = UUID.randomUUID();
        UUID competitorPlan = UUID.randomUUID();
        UUID competitorNpc = UUID.randomUUID();
        service.acquire(workstation, firstPlan, firstNpc, 10L, 30L);

        NpcReservationAcquireResult conflict = service.acquire(
                workstation, competitorPlan, competitorNpc, 11L, 40L);
        assertEquals(NpcReservationAcquireResult.Status.CONFLICT, conflict.status());
        assertTrue(service.holds(workstation, firstPlan, firstNpc, 11L));
        assertFalse(service.holds(workstation, competitorPlan, competitorNpc, 11L));
        assertTrue(service.renew(
                workstation, competitorPlan, competitorNpc, 11L, 40L).isEmpty());

        NpcReservation unchanged = service.find(workstation, 11L).orElseThrow();
        assertEquals(firstPlan, unchanged.planId());
        assertEquals(firstNpc, unchanged.npcId());
        assertEquals(30L, unchanged.expiresAt());

        service.releasePlan(firstPlan);
        assertEquals(NpcReservationAcquireResult.Status.ACQUIRED,
                service.acquire(workstation, competitorPlan, competitorNpc, 12L, 40L).status());
    }

    @Test
    void emptyServiceAfterReloadAllowsThePlanToReacquireItsWorkstation() {
        NpcReservationKey workstation = new NpcReservationKey(
                "workstation", "minecraft:overworld:forge-2");
        UUID planId = UUID.randomUUID();
        UUID npcId = UUID.randomUUID();
        NpcReservationService beforeReload = new NpcReservationService();
        beforeReload.acquire(workstation, planId, npcId, 10L, 30L);

        NpcReservationService afterReload = new NpcReservationService();
        NpcReservationAcquireResult reacquired = afterReload.acquire(
                workstation, planId, npcId, 20L, 50L);

        assertEquals(NpcReservationAcquireResult.Status.ACQUIRED, reacquired.status());
        assertTrue(afterReload.holds(workstation, planId, npcId, 20L));
        assertEquals(20L, reacquired.reservation().acquiredAt());
        assertEquals(50L, reacquired.reservation().expiresAt());
    }

    @Test
    void onlyExactHolderCanRenewAndRenewalNeverShortensTheLease() {
        NpcReservationService service = new NpcReservationService();
        NpcReservationKey key = new NpcReservationKey("output_slot", "warehouse-a:3");
        UUID planId = UUID.randomUUID();
        UUID npcId = UUID.randomUUID();
        service.acquire(key, planId, npcId, 10L, 30L);

        NpcReservation renewed = service.renew(key, planId, npcId, 20L, 45L).orElseThrow();

        assertEquals(10L, renewed.acquiredAt());
        assertEquals(45L, renewed.expiresAt());
        assertTrue(service.holds(key, planId, npcId, 44L));
        assertEquals(renewed, service.renew(key, planId, npcId, 21L, 45L).orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> service.renew(key, planId, npcId, 22L, 40L));
        assertEquals(45L, service.find(key, 22L).orElseThrow().expiresAt());

        assertTrue(service.renew(key, UUID.randomUUID(), npcId, 22L, 50L).isEmpty());
        assertTrue(service.renew(key, planId, UUID.randomUUID(), 22L, 50L).isEmpty());
        assertEquals(45L, service.find(key, 22L).orElseThrow().expiresAt());
    }

    @Test
    void holdsAndRenewClearExpiredLeaseBeforeAnswering() {
        NpcReservationService service = new NpcReservationService();
        NpcReservationKey key = new NpcReservationKey("seat", "expired");
        UUID planId = UUID.randomUUID();
        UUID npcId = UUID.randomUUID();
        service.acquire(key, planId, npcId, 5L, 10L);

        assertFalse(service.holds(key, planId, npcId, 10L));
        assertTrue(service.snapshot().isEmpty());

        service.acquire(key, planId, npcId, 11L, 20L);
        assertTrue(service.renew(key, planId, npcId, 20L, 30L).isEmpty());
        assertTrue(service.snapshot().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> service.renew(key, planId, npcId, 21L, 21L));
    }
}
