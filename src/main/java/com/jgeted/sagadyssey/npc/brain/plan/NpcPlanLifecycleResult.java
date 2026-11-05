package com.jgeted.sagadyssey.npc.brain.plan;

import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservation;

import java.util.List;

/** 计划终结结果及随之释放的互斥资源，用于审计。 */
public record NpcPlanLifecycleResult(NpcPlan plan,
                                     List<NpcReservation> releasedReservations) {
    public NpcPlanLifecycleResult {
        if (plan == null) throw new IllegalArgumentException("plan is required");
        releasedReservations = List.copyOf(releasedReservations == null
                ? List.of() : releasedReservations);
    }
}
