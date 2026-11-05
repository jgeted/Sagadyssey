package com.jgeted.sagadyssey.npc.brain.plan;

import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservation;
import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationService;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 统一提交计划快照，并保证计划进入终态时释放全部互斥预留。
 */
public final class NpcPlanLifecycleService {
    private final NpcPlanStore store;
    private final NpcReservationService reservations;

    public NpcPlanLifecycleService(NpcPlanStore store,
                                   NpcReservationService reservations) {
        this.store = Objects.requireNonNull(store, "store");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
    }

    public synchronized NpcPlanLifecycleResult save(NpcPlan plan) {
        Objects.requireNonNull(plan, "plan");
        store.save(plan);
        List<NpcReservation> released = plan.terminal()
                ? reservations.releasePlan(plan.id()) : List.of();
        return new NpcPlanLifecycleResult(plan, released);
    }

    public synchronized NpcPlanLifecycleResult cancelActive(UUID npcId, long now,
                                                            String explanation) {
        NpcPlan active = requireActive(npcId);
        return save(active.transition(NpcPlanStatus.CANCELLED, now, explanation));
    }

    public synchronized NpcPlanLifecycleResult failActive(UUID npcId, long now,
                                                          String explanation) {
        NpcPlan active = requireActive(npcId);
        return save(active.transition(NpcPlanStatus.FAILED, now, explanation));
    }

    private NpcPlan requireActive(UUID npcId) {
        Objects.requireNonNull(npcId, "npcId");
        return store.active(npcId)
                .orElseThrow(() -> new IllegalStateException("npc has no active plan"));
    }
}
