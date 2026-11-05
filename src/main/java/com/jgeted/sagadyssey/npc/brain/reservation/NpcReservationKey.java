package com.jgeted.sagadyssey.npc.brain.reservation;

/** 互斥世界资源的稳定键，例如 workstation/overworld:12,64,9。 */
public record NpcReservationKey(String type, String id) implements Comparable<NpcReservationKey> {
    public NpcReservationKey {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type is required");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
    }

    @Override
    public int compareTo(NpcReservationKey other) {
        int typeOrder = type.compareTo(other.type);
        return typeOrder != 0 ? typeOrder : id.compareTo(other.id);
    }
}
