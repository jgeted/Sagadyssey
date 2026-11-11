package com.jgeted.sagadyssey.npc.brain.candidate;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.Objects;

/** 候选计划中尚未开始执行的不可变步骤定义。 */
public record NpcPlanStepTemplate(NpcPlanPhase phase, Identifier executorId,
                                  Map<String, String> parameters) {
    public NpcPlanStepTemplate {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(executorId, "executorId");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
    }
}
