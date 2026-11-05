package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import net.minecraft.resources.ResourceLocation;

/**
 * 将一个计划步骤映射为可持续多 tick 的游戏动作。
 */
public interface NpcTaskExecutor {
    /** Drop transient caches on unload; must not settle, cancel, refund or copy domain assets. */
    default void forget(java.util.UUID body, java.util.UUID plan) {}
    ResourceLocation id();

    TaskTickResult start(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step);

    TaskTickResult tick(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step);

    /**
     * 服务端重载后恢复一个已经持久化为 RUNNING 的步骤。
     *
     * <p>默认安全失败，真实执行器必须显式重新核验目标、预留和幂等回执，不能假设
     * 上一次 JVM 中的导航或临时状态仍然存在。</p>
     */
    default TaskTickResult recover(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) {
        return TaskTickResult.failed("执行器未实现重载恢复");
    }

    /**
     * 取消执行时释放执行器持有的临时状态或资源。
     */
    default void cancel(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step,
                        PlanCancellationReason reason) {
    }
}
