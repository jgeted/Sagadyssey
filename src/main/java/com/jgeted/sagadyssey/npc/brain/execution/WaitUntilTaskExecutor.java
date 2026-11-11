package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import net.minecraft.resources.Identifier;

/** 只等待到指定游戏刻的幂等执行器；短暂休整与诊断共用，不修改世界或物资。 */
public final class WaitUntilTaskExecutor implements NpcTaskExecutor {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(
            "sagadyssey", "wait_until");
    public static final String UNTIL_GAME_TIME = "until_game_time";

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public TaskTickResult start(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) {
        return evaluate(context, step);
    }

    @Override
    public TaskTickResult tick(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) {
        return evaluate(context, step);
    }

    @Override
    public TaskTickResult recover(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) {
        return evaluate(context, step);
    }

    private TaskTickResult evaluate(NpcExecutionContext context, NpcPlanStep step) {
        String encoded = step.params().get(UNTIL_GAME_TIME);
        if (encoded == null) return TaskTickResult.failed("等待步骤缺少结束时间");
        try {
            long until = Long.parseLong(encoded);
            if (until < 0L) return TaskTickResult.failed("等待步骤结束时间无效");
            return context.gameTime() >= until
                    ? TaskTickResult.stepSucceeded("等待结束")
                    : TaskTickResult.running("等待至游戏刻 " + until);
        } catch (NumberFormatException exception) {
            return TaskTickResult.failed("等待步骤结束时间无法解析");
        }
    }
}
