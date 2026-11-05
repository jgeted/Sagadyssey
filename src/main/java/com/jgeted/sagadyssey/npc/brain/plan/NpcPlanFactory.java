package com.jgeted.sagadyssey.npc.brain.plan;

import com.jgeted.sagadyssey.npc.brain.candidate.NpcActivityCandidate;
import com.jgeted.sagadyssey.npc.brain.context.NpcDecisionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 将已选候选转换为具有稳定身份的单步骤初始计划。 */
public final class NpcPlanFactory {
    private NpcPlanFactory() {
    }

    public static NpcPlan create(UUID planId, NpcDecisionContext context,
                                 NpcActivityCandidate candidate, long commitmentUntil,
                                 long expiresAt, String explanation) {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(candidate, "candidate");
        if (context.gameTime() < candidate.earliestStart()
                || context.gameTime() > candidate.latestStart()) {
            throw new IllegalArgumentException("candidate is outside its start window");
        }
        if (!candidate.hardBlockers().isEmpty()) {
            throw new IllegalArgumentException("blocked candidate cannot become a plan");
        }
        if (!context.satisfiedPrerequisites().containsAll(candidate.prerequisites())) {
            throw new IllegalArgumentException("candidate prerequisites are not satisfied");
        }
        List<NpcPlanStep> steps = new ArrayList<>();
        for (int index = 0; index < candidate.steps().size(); index++) {
            var template = candidate.steps().get(index);
            steps.add(new NpcPlanStep(index, template.phase(), template.executorId(),
                    template.parameters(), NpcPlanStepStatus.PENDING,
                    context.gameTime(), "等待执行"));
        }
        return new NpcPlan(planId, context.npcId(), candidate.candidateKey(),
                candidate.providerId(), candidate.kind(), candidate.priority(),
                NpcPlanStatus.SELECTED, List.copyOf(steps), 0, context.gameTime(),
                context.gameTime(), commitmentUntil, expiresAt, null, explanation);
    }
}
