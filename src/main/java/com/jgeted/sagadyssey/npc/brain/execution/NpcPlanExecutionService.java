package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanLifecycleService;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStepStatus;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStore;

import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 推进一个 NPC 的当前计划，但不直接依赖实体或世界实现。
 *
 * <p>不可逆世界修改仍由执行器负责；本服务只管理步骤、阻塞、重试和终态。</p>
 */
public final class NpcPlanExecutionService {
    private final NpcPlanStore store;
    private final NpcPlanLifecycleService lifecycle;
    private final NpcTaskExecutorRegistry executors;
    private final Set<RunningStepKey> liveSteps = new HashSet<>();

    public NpcPlanExecutionService(NpcPlanStore store,
                                   NpcPlanLifecycleService lifecycle,
                                   NpcTaskExecutorRegistry executors) {
        this.store = Objects.requireNonNull(store, "store");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    /** Forget process-local execution state only; unloading is not cancellation or settlement. */
    public void suspend(UUID body) {
        store.active(body).ifPresent(plan -> {
            liveSteps.remove(RunningStepKey.from(plan));
            executors.snapshot().values().forEach(executor -> executor.forget(body,plan.id()));
        });
    }

    public NpcPlanExecutionUpdate tick(NpcExecutionContext context) {
        Objects.requireNonNull(context, "context");
        NpcPlan plan = store.active(context.npcId()).orElse(null);
        if (plan == null) return NpcPlanExecutionUpdate.idle("没有活动计划");
        long now = context.gameTime();
        if (now < plan.updatedAt()) {
            throw new IllegalArgumentException("execution time cannot move backwards");
        }

        NpcTaskExecutor executor = executors.find(plan.currentStep().executorId()).orElse(null);
        if (executor == null) {
            return fail(plan, now, "未注册执行器: " + plan.currentStep().executorId());
        }
        if (plan.expiresAt() > 0L && now >= plan.expiresAt()) {
            safeCancel(executor, context, plan, plan.currentStep(),
                    PlanCancellationReason.SCHEDULE_CHANGE);
            liveSteps.remove(RunningStepKey.from(plan));
            NpcPlan cancelled = plan.transition(NpcPlanStatus.CANCELLED, now, "计划已过期");
            lifecycle.save(cancelled);
            return new NpcPlanExecutionUpdate(
                    NpcPlanExecutionUpdate.Outcome.CANCELLED, cancelled, "计划已过期");
        }

        if (plan.status() == NpcPlanStatus.BLOCKED) {
            if (plan.blocker() == null || plan.blocker().retryAt() <= 0L
                    || now < plan.blocker().retryAt()) {
                return new NpcPlanExecutionUpdate(
                        NpcPlanExecutionUpdate.Outcome.WAITING, plan, plan.explanation());
            }
            NpcPlanStep pending = plan.currentStep().transition(
                    NpcPlanStepStatus.PENDING, now, "到达重试时间");
            plan = plan.replaceCurrentStep(pending)
                    .transition(NpcPlanStatus.WAITING, now, plan.blocker(), "等待重新执行");
            lifecycle.save(plan);
        }

        if (plan.status() == NpcPlanStatus.WAITING) {
            plan = plan.transition(plan.currentStep().phase().planStatus(), now, null, "恢复计划步骤");
            lifecycle.save(plan);
        } else if (plan.status() != plan.currentStep().phase().planStatus()) {
            plan = plan.transition(plan.currentStep().phase().planStatus(), now, "进入步骤阶段");
            lifecycle.save(plan);
        }

        NpcPlanStep step = plan.currentStep();
        TaskTickResult result;
        try {
            if (step.status() == NpcPlanStepStatus.PENDING) {
                step = step.transition(NpcPlanStepStatus.RUNNING, now, "开始执行");
                plan = plan.replaceCurrentStep(step);
                lifecycle.save(plan);
                liveSteps.add(RunningStepKey.from(plan));
                result = requireResult(executor.start(context, plan, step));
            } else if (step.status() == NpcPlanStepStatus.RUNNING) {
                RunningStepKey key = RunningStepKey.from(plan);
                result = liveSteps.add(key)
                        ? requireResult(executor.recover(context, plan, step))
                        : requireResult(executor.tick(context, plan, step));
            } else if (step.status() == NpcPlanStepStatus.BLOCKED) {
                return fail(plan, now, "计划与步骤阻塞状态不一致");
            } else {
                return fail(plan, now, "活动计划指向已终结步骤");
            }
        } catch (RuntimeException exception) {
            safeCancel(executor, context, plan, step, PlanCancellationReason.TARGET_INVALID);
            return fail(plan, now, "执行器异常: " + safeMessage(exception));
        }
        return applyResult(plan, result, now);
    }

    public NpcPlanExecutionUpdate cancel(NpcExecutionContext context,
                                         PlanCancellationReason reason,
                                         String detail) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(reason, "reason");
        NpcPlan plan = store.active(context.npcId()).orElse(null);
        if (plan == null) return NpcPlanExecutionUpdate.idle("没有可取消的活动计划");
        NpcTaskExecutor executor = executors.find(plan.currentStep().executorId()).orElse(null);
        if (executor != null && !plan.currentStep().status().terminal()) {
            safeCancel(executor, context, plan, plan.currentStep(), reason);
        }
        liveSteps.remove(RunningStepKey.from(plan));
        NpcPlan cancelled = plan.transition(NpcPlanStatus.CANCELLED,
                context.gameTime(), detail == null ? reason.name() : detail);
        lifecycle.save(cancelled);
        return new NpcPlanExecutionUpdate(
                NpcPlanExecutionUpdate.Outcome.CANCELLED, cancelled, cancelled.explanation());
    }

    private NpcPlanExecutionUpdate applyResult(NpcPlan plan, TaskTickResult result, long now) {
        Objects.requireNonNull(result, "executor result");
        return switch (result.state()) {
            case RUNNING -> {
                if (!result.detail().equals(plan.currentStep().detail())) {
                    NpcPlanStep running = new NpcPlanStep(
                            plan.currentStep().index(), plan.currentStep().phase(),
                            plan.currentStep().executorId(), plan.currentStep().params(),
                            NpcPlanStepStatus.RUNNING, now, result.detail());
                    plan = plan.replaceCurrentStep(running);
                    lifecycle.save(plan);
                }
                yield new NpcPlanExecutionUpdate(
                        NpcPlanExecutionUpdate.Outcome.RUNNING, plan, result.detail());
            }
            case BLOCKED -> {
                liveSteps.remove(RunningStepKey.from(plan));
                NpcPlanStep blockedStep = plan.currentStep().transition(
                        NpcPlanStepStatus.BLOCKED, now, result.detail());
                NpcPlan blocked = plan.replaceCurrentStep(blockedStep)
                        .transition(NpcPlanStatus.BLOCKED, now, result.blocker(), result.detail());
                lifecycle.save(blocked);
                yield new NpcPlanExecutionUpdate(
                        NpcPlanExecutionUpdate.Outcome.WAITING, blocked, result.detail());
            }
            case FAILED -> fail(plan, now, result.detail());
            case STEP_SUCCEEDED -> succeedStep(plan, now, result.detail());
        };
    }

    private NpcPlanExecutionUpdate succeedStep(NpcPlan plan, long now, String detail) {
        NpcPlanStep succeededStep = plan.currentStep().transition(
                NpcPlanStepStatus.SUCCEEDED, now, detail);
        liveSteps.remove(RunningStepKey.from(plan));
        NpcPlan updated = plan.replaceCurrentStep(succeededStep);
        if (updated.currentStepIndex() + 1 >= updated.steps().size()) {
            NpcPlan succeeded = updated.transition(NpcPlanStatus.SUCCEEDED, now, detail);
            lifecycle.save(succeeded);
            return new NpcPlanExecutionUpdate(
                    NpcPlanExecutionUpdate.Outcome.SUCCEEDED, succeeded, detail);
        }

        updated = updated.advanceStep(now, "进入下一步骤");
        NpcPlanStatus nextStatus = updated.currentStep().phase().planStatus();
        if (updated.status() != nextStatus) {
            updated = updated.transition(nextStatus, now, "进入下一步骤阶段");
        }
        lifecycle.save(updated);
        return new NpcPlanExecutionUpdate(
                NpcPlanExecutionUpdate.Outcome.STEP_ADVANCED, updated, detail);
    }

    private NpcPlanExecutionUpdate fail(NpcPlan plan, long now, String detail) {
        liveSteps.remove(RunningStepKey.from(plan));
        NpcPlanStep current = plan.currentStep();
        if (!current.status().terminal()) {
            current = current.transition(NpcPlanStepStatus.FAILED, now, detail);
            plan = plan.replaceCurrentStep(current);
        }
        NpcPlan failed = plan.transition(NpcPlanStatus.FAILED, now, detail);
        lifecycle.save(failed);
        return new NpcPlanExecutionUpdate(
                NpcPlanExecutionUpdate.Outcome.FAILED, failed, detail);
    }

    private static TaskTickResult requireResult(TaskTickResult result) {
        return Objects.requireNonNull(result, "executor result");
    }

    private static void safeCancel(NpcTaskExecutor executor, NpcExecutionContext context,
                                   NpcPlan plan, NpcPlanStep step,
                                   PlanCancellationReason reason) {
        try {
            executor.cancel(context, plan, step, reason);
        } catch (RuntimeException ignored) {
            // 计划终态和资源释放不能被执行器的临时清理异常阻断。
        }
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private record RunningStepKey(UUID planId, int stepIndex) {
        private static RunningStepKey from(NpcPlan plan) {
            return new RunningStepKey(plan.id(), plan.currentStepIndex());
        }
    }
}
