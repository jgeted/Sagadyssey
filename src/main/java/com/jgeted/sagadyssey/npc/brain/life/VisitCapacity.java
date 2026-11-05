package com.jgeted.sagadyssey.npc.brain.life;

import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationKey;
import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationService;
import java.util.List;
import java.util.UUID;

/** Reuses the unified plan leases; a capacity slot never confers access permission. */
public final class VisitCapacity {
    private VisitCapacity() { }

    public static NpcReservationKey acquire(NpcReservationService reservations, String place,
                                            int capacity, UUID plan, UUID npc, long now, long until) {
        synchronized (reservations) {
            return acquireLocked(reservations, place, capacity, plan, npc, now, until);
        }
    }

    private static NpcReservationKey acquireLocked(NpcReservationService reservations, String place,
                                                   int capacity, UUID plan, UUID npc, long now, long until) {
        if (place == null || place.isBlank() || capacity < 1 || capacity > 64)
            throw new IllegalArgumentException("invalid place capacity");
        if (now < 0 || until <= now) throw new IllegalArgumentException("invalid lease deadline");
        // A resumed plan retains its exact slot, even if a lower slot has become free.
        List<NpcReservationKey> keys = java.util.stream.IntStream.range(0, capacity)
                .mapToObj(i -> new NpcReservationKey("visit_place", place + ":slot:" + i)).toList();
        for (var key : keys) {
            if (reservations.holds(key, plan, npc, now)) {
                reservations.renew(key, plan, npc, now, Math.max(until,
                        reservations.find(key, now).orElseThrow().expiresAt()));
                return key;
            }
        }
        for (var key : keys) {
            var acquired = reservations.acquire(key, plan, npc, now, until);
            if (acquired.status() != com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationAcquireResult.Status.CONFLICT)
                return key;
        }
        return null;
    }
}
