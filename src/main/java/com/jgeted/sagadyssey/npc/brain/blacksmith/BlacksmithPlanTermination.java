package com.jgeted.sagadyssey.npc.brain.blacksmith;

import com.jgeted.sagadyssey.npc.brain.execution.NpcPlanExecutionUpdate;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 有限期计划到期是正式终止，不等同于战斗、换班等可续接中断。 */
public final class BlacksmithPlanTermination {
    private BlacksmithPlanTermination() {}

    public static boolean closesCommitment(NpcPlanExecutionUpdate update) {
        if (update.plan() == null || !update.plan().providerId().equals(BlacksmithTaskIds.PROVIDER)) return false;
        return update.outcome() == NpcPlanExecutionUpdate.Outcome.FAILED
                || (update.outcome() == NpcPlanExecutionUpdate.Outcome.CANCELLED
                && expiredCancellation(update.plan()));
    }

    /** 补做遗漏或失败的终态收尾，但绝不覆盖同一订单的后续计划。 */
    public static List<NpcPlan> historyToClose(List<NpcPlan> history, Optional<NpcPlan> active) {
        Set<String> seen = new HashSet<>();
        active.filter(plan -> plan.providerId().equals(BlacksmithTaskIds.PROVIDER))
                .ifPresent(plan -> seen.add(plan.candidateKey()));
        List<NpcPlan> result = new ArrayList<>();
        history.stream().filter(plan -> plan.providerId().equals(BlacksmithTaskIds.PROVIDER))
                .sorted(Comparator.comparingLong(NpcPlan::updatedAt).reversed()
                        .thenComparing(Comparator.comparingLong(NpcPlan::createdAt).reversed()))
                .forEach(plan -> {
                    if (seen.add(plan.candidateKey())
                            && (plan.status() == NpcPlanStatus.FAILED || expiredCancellation(plan))) result.add(plan);
                });
        return List.copyOf(result);
    }

    private static boolean expiredCancellation(NpcPlan plan) {
        return plan != null && plan.status() == NpcPlanStatus.CANCELLED
                && plan.expiresAt() > 0L && plan.updatedAt() >= plan.expiresAt();
    }
}
