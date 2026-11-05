package com.jgeted.sagadyssey.npc.brain.reservation;

import java.util.UUID;

/** 一个计划在有限时间内持有的互斥资源。 */
public record NpcReservation(NpcReservationKey key, UUID planId, UUID npcId,
                             long acquiredAt, long expiresAt) {
    public NpcReservation {
        if (key == null || planId == null || npcId == null) {
            throw new IllegalArgumentException("reservation identity is required");
        }
        if (acquiredAt < 0L || expiresAt <= acquiredAt) {
            throw new IllegalArgumentException("reservation expiry must follow acquisition");
        }
    }

    public boolean expiredAt(long now) {
        return now >= expiresAt;
    }
}
