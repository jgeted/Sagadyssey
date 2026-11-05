package com.jgeted.sagadyssey.npc.brain.debug;

import com.jgeted.sagadyssey.social.production.ProductionBatch;
import com.jgeted.sagadyssey.social.production.ProductionOrder;
import com.jgeted.sagadyssey.worldsim.rights.AssetReservation;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 只读关联生产令牌；终态批次和已过期记录不能被审计入口隐藏。 */
public final class NpcProductionReservationAudit {
    private NpcProductionReservationAudit() {}

    public static List<AssetReservation> capture(UUID workerId, List<ProductionOrder> orders,
                                                List<ProductionBatch> batches,
                                                Collection<AssetReservation> reservations) {
        Set<String> tokens = new HashSet<>();
        orders.stream().filter(order -> workerId.equals(order.assignedWorkerId()))
                .forEach(order -> tokens.add("production:" + order.batchId()));
        batches.stream().filter(batch -> workerId.equals(batch.workerId()))
                .forEach(batch -> tokens.add(batch.token()));
        return reservations.stream().filter(entry -> tokens.contains(entry.token()))
                .sorted(Comparator.comparing(AssetReservation::token)
                        .thenComparing(entry -> entry.id().toString()))
                .toList();
    }
}
