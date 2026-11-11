package com.jgeted.sagadyssey.npc.brain.plan;

import net.minecraft.resources.Identifier;

import java.util.Map;

/** 一个由注册执行器处理的不可变步骤。 */
public record NpcPlanStep(int index, NpcPlanPhase phase, Identifier executorId,
                          Map<String, String> params,
                          NpcPlanStepStatus status, long updatedAt, String detail) {
    public NpcPlanStep {
        if (index < 0) throw new IllegalArgumentException("step index cannot be negative");
        if (phase == null) throw new IllegalArgumentException("step phase is required");
        if (executorId == null) throw new IllegalArgumentException("executorId is required");
        params = Map.copyOf(params == null ? Map.of() : params);
        if (status == null) throw new IllegalArgumentException("step status is required");
        if (updatedAt < 0L) throw new IllegalArgumentException("updatedAt cannot be negative");
        detail = detail == null ? "" : detail;
    }

    public NpcPlanStep transition(NpcPlanStepStatus next, long now, String nextDetail) {
        status.requireTransitionTo(next);
        if (now < updatedAt) throw new IllegalArgumentException("step time cannot move backwards");
        return new NpcPlanStep(index, phase, executorId, params, next, now, nextDetail);
    }
}
