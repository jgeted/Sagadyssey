package com.jgeted.sagadyssey.npc.brain.debug;

import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanBlocker;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPriority;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStepStatus;
import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservation;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 面向测试者的 NPC 大脑只读快照。
 *
 * <p>快照只保留诊断所需字段，不暴露可变存储对象；命令层取得快照后不会意外修改
 * 计划、历史或资源预留。最近历史按更新时间倒序，并限制数量，避免聊天输出失控。</p>
 */
public record NpcBrainSnapshot(UUID npcId, NpcBehaviorMode behaviorMode,
                               @Nullable PlanSummary activePlan,
                               List<HistorySummary> recentHistory,
                               List<ReservationSummary> reservations) {
    public static final int HISTORY_LIMIT = 8;

    public NpcBrainSnapshot {
        if (npcId == null) throw new IllegalArgumentException("npcId is required");
        if (behaviorMode == null) {
            throw new IllegalArgumentException("behaviorMode is required");
        }
        recentHistory = List.copyOf(recentHistory == null ? List.of() : recentHistory);
        reservations = List.copyOf(reservations == null ? List.of() : reservations);
    }

    /** 从持久层的只读结果建立独立快照。 */
    public static NpcBrainSnapshot capture(UUID npcId, NpcBehaviorMode behaviorMode,
                                           Optional<NpcPlan> activePlan,
                                           List<NpcPlan> history,
                                           List<NpcReservation> reservations) {
        if (activePlan == null) throw new IllegalArgumentException("activePlan is required");
        if (history == null) throw new IllegalArgumentException("history is required");
        if (reservations == null) throw new IllegalArgumentException("reservations is required");

        PlanSummary active = activePlan
                .filter(plan -> plan.npcId().equals(npcId))
                .map(PlanSummary::from)
                .orElse(null);
        List<HistorySummary> recent = history.stream()
                .filter(plan -> plan.npcId().equals(npcId))
                .sorted(Comparator.comparingLong(NpcPlan::updatedAt).reversed()
                        .thenComparing(plan -> plan.id().toString()))
                .limit(HISTORY_LIMIT)
                .map(HistorySummary::from)
                .toList();
        List<ReservationSummary> held = reservations.stream()
                .filter(reservation -> reservation.npcId().equals(npcId))
                .sorted(Comparator.comparing(NpcReservation::key)
                        .thenComparing(reservation -> reservation.planId().toString()))
                .map(ReservationSummary::from)
                .toList();
        return new NpcBrainSnapshot(npcId, behaviorMode, active, recent, held);
    }

    public boolean hasActivePlan() {
        return activePlan != null;
    }

    /** 非活动计划持有的记录也必须可见，否则终态审计会得到假零值。 */
    public boolean isActiveReservation(ReservationSummary reservation) {
        return activePlan != null && activePlan.planId().equals(reservation.planId());
    }

    /** 当前活动计划摘要；步骤摘要只包含当前步骤。 */
    public record PlanSummary(UUID planId, String candidateKey, Identifier providerId,
                              NpcPlanKind kind, NpcPlanPriority priority, NpcPlanStatus status,
                              NpcPlanPhase phase, int currentStepIndex, int stepCount,
                              StepSummary currentStep, @Nullable BlockerSummary blocker,
                              long updatedAt, long expiresAt, String explanation) {
        public PlanSummary {
            if (planId == null || providerId == null || kind == null || priority == null
                    || status == null || phase == null || currentStep == null) {
                throw new IllegalArgumentException("plan summary identity is required");
            }
            if (candidateKey == null || candidateKey.isBlank()) {
                throw new IllegalArgumentException("candidateKey is required");
            }
            if (currentStepIndex < 0 || stepCount <= currentStepIndex) {
                throw new IllegalArgumentException("invalid current step position");
            }
            if (updatedAt < 0L || expiresAt < 0L) {
                throw new IllegalArgumentException("plan summary time cannot be negative");
            }
            explanation = explanation == null ? "" : explanation;
        }

        private static PlanSummary from(NpcPlan plan) {
            NpcPlanStep step = plan.currentStep();
            return new PlanSummary(plan.id(), plan.candidateKey(), plan.providerId(),
                    plan.kind(), plan.priority(), plan.status(), step.phase(),
                    plan.currentStepIndex(), plan.steps().size(), StepSummary.from(step),
                    plan.blocker() == null ? null : BlockerSummary.from(plan.blocker()),
                    plan.updatedAt(), plan.expiresAt(), plan.explanation());
        }
    }

    /** 当前步骤的执行器与状态。参数不进入管理员聊天，避免泄漏或刷屏。 */
    public record StepSummary(int index, NpcPlanPhase phase, Identifier executorId,
                              NpcPlanStepStatus status, long updatedAt, String detail) {
        public StepSummary {
            if (index < 0 || phase == null || executorId == null || status == null) {
                throw new IllegalArgumentException("step summary identity is required");
            }
            if (updatedAt < 0L) throw new IllegalArgumentException("updatedAt cannot be negative");
            detail = detail == null ? "" : detail;
        }

        private static StepSummary from(NpcPlanStep step) {
            return new StepSummary(step.index(), step.phase(), step.executorId(),
                    step.status(), step.updatedAt(), step.detail());
        }
    }

    /** 结构化阻塞原因，保留重试时间与可选依赖。 */
    public record BlockerSummary(String code, String detail, long retryAt,
                                 @Nullable String dependencyKey) {
        public BlockerSummary {
            if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
            if (retryAt < 0L) throw new IllegalArgumentException("retryAt cannot be negative");
            detail = detail == null ? "" : detail;
            if (dependencyKey != null && dependencyKey.isBlank()) {
                throw new IllegalArgumentException("dependencyKey cannot be blank");
            }
        }

        private static BlockerSummary from(NpcPlanBlocker blocker) {
            return new BlockerSummary(blocker.code(), blocker.detail(), blocker.retryAt(),
                    blocker.dependencyKey());
        }
    }

    /** 已结束计划的紧凑审计条目。 */
    public record HistorySummary(UUID planId, String candidateKey, NpcPlanKind kind,
                                 NpcPlanPriority priority, NpcPlanStatus status,
                                 long updatedAt, String explanation) {
        public HistorySummary {
            if (planId == null || kind == null || priority == null || status == null) {
                throw new IllegalArgumentException("history summary identity is required");
            }
            if (candidateKey == null || candidateKey.isBlank()) {
                throw new IllegalArgumentException("candidateKey is required");
            }
            if (updatedAt < 0L) throw new IllegalArgumentException("updatedAt cannot be negative");
            explanation = explanation == null ? "" : explanation;
        }

        private static HistorySummary from(NpcPlan plan) {
            return new HistorySummary(plan.id(), plan.candidateKey(), plan.kind(),
                    plan.priority(), plan.status(), plan.updatedAt(), plan.explanation());
        }
    }

    /** 当前由该 NPC 持有的互斥资源。 */
    public record ReservationSummary(String type, String resourceId, UUID planId,
                                     long acquiredAt, long expiresAt) {
        public ReservationSummary {
            if (type == null || type.isBlank() || resourceId == null || resourceId.isBlank()
                    || planId == null) {
                throw new IllegalArgumentException("reservation summary identity is required");
            }
            if (acquiredAt < 0L || expiresAt <= acquiredAt) {
                throw new IllegalArgumentException("reservation summary expiry is invalid");
            }
        }

        private static ReservationSummary from(NpcReservation reservation) {
            return new ReservationSummary(reservation.key().type(), reservation.key().id(),
                    reservation.planId(), reservation.acquiredAt(), reservation.expiresAt());
        }
    }
}
