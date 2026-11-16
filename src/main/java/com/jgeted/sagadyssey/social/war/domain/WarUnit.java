package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.WarIntent;
import com.jgeted.sagadyssey.social.war.api.WarKey;

import java.util.*;

/** 编组与命令快照；不包含第二份人口、库存、健康或实体。 */
public record WarUnit(UUID id, UUID armyId, WarKey troopType, int definitionVersion,
                      long revision, long lastIssuedSequence,
                      Optional<Order> currentOrder, List<Order> pendingOrders) {
    public WarUnit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(armyId, "armyId");
        Objects.requireNonNull(troopType, "troopType");
        Objects.requireNonNull(currentOrder, "currentOrder");
        pendingOrders = List.copyOf(pendingOrders);
        if (definitionVersion < 1 || revision < 1 || lastIssuedSequence < 0) {
            throw new IllegalArgumentException("invalid unit counters");
        }
        long sequence = currentOrder.map(Order::sequence).orElse(0L);
        Set<UUID> ids = new HashSet<>();
        if (currentOrder.isPresent()) {
            Order order = currentOrder.get();
            if (order.deliveredAtTick() < 0 || order.deliveryRevision() > revision) {
                throw new IllegalArgumentException("invalid current order");
            }
            ids.add(order.id());
        }
        for (Order order : pendingOrders) {
            if (order.deliveredAtTick() != -1 || order.sequence() != sequence + 1 || !ids.add(order.id())) {
                throw new IllegalArgumentException("pending orders must be contiguous and undelivered");
            }
            sequence = order.sequence();
        }
        if (sequence != lastIssuedSequence) throw new IllegalArgumentException("order sequence disagrees");
    }

    public record Order(UUID id, long generation, UUID issuerId, long sequence, WarIntent intent,
                        long issuedAtTick, long deliveredAtTick, long deliveryRevision) {
        public Order(UUID id, UUID issuerId, long sequence, WarIntent intent, long issuedAtTick,
                     long deliveredAtTick, long deliveryRevision) {
            this(id, 0, issuerId, sequence, intent, issuedAtTick, deliveredAtTick, deliveryRevision);
        }
        public Order {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(issuerId, "issuerId");
            Objects.requireNonNull(intent, "intent");
            if (generation < 0 || sequence < 1 || issuedAtTick < 0 || deliveredAtTick < -1
                    || deliveredAtTick >= 0 && deliveredAtTick < issuedAtTick
                    || (deliveredAtTick == -1 ? deliveryRevision != 0 : deliveryRevision < 1)) {
                throw new IllegalArgumentException("invalid order checkpoint");
            }
        }
    }
}
