package com.jgeted.sagadyssey.npc.brain.reservation;

/** acquire 的结构化结果；冲突时保留实际持有人，便于调试与等待。 */
public record NpcReservationAcquireResult(Status status, NpcReservation reservation) {
    public NpcReservationAcquireResult {
        if (status == null || reservation == null) {
            throw new IllegalArgumentException("acquire result is incomplete");
        }
    }

    public boolean acquired() {
        return status != Status.CONFLICT;
    }

    public enum Status {
        ACQUIRED,
        ALREADY_HELD,
        CONFLICT
    }
}
