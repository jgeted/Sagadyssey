package com.jgeted.sagadyssey.npc.brain.blacksmith;

import com.jgeted.sagadyssey.npc.brain.execution.WaitUntilTaskExecutor;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Last-resort rest at the current location; grants no work, property or social authority. */
public final class BlacksmithRestPlanFactory {
    public static final ResourceLocation PROVIDER =
            ResourceLocation.fromNamespaceAndPath("sagadyssey", "blacksmith_rest");
    public static final int DURATION_TICKS = 100;

    private BlacksmithRestPlanFactory() {}

    public static NpcPlan create(UUID body, String dimension, BlockPos location, long now) {
        if (body == null || dimension == null || dimension.isBlank() || location == null || now < 0)
            throw new IllegalArgumentException("rest requires the original body, location and valid time");
        long until = Math.addExact(now, DURATION_TICKS);
        var step = new NpcPlanStep(0, NpcPlanPhase.ACTING, WaitUntilTaskExecutor.ID,
                Map.of(WaitUntilTaskExecutor.UNTIL_GAME_TIME, Long.toString(until),
                        "dimension", dimension, "x", Integer.toString(location.getX()),
                        "y", Integer.toString(location.getY()), "z", Integer.toString(location.getZ())),
                NpcPlanStepStatus.PENDING, now, "原地短暂休整，随后重新核验工作和生活需要");
        return new NpcPlan(UUID.randomUUID(), body, "blacksmith-rest:" + body + ":" + now,
                PROVIDER, NpcPlanKind.AMBIENT, NpcPlanPriority.AMBIENT,
                NpcPlanStatus.SELECTED, List.of(step), 0, now, now, now + 20,
                Math.addExact(until, 200), null,
                "没有当前可执行订单或已知生活安排，有限休整；不生成物资或生产承诺");
    }
}
