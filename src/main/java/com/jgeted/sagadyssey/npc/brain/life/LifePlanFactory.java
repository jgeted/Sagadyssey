package com.jgeted.sagadyssey.npc.brain.life;

import com.jgeted.sagadyssey.npc.brain.plan.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

public final class LifePlanFactory {
    public static final ResourceLocation PROVIDER = ResourceLocation.parse("sagadyssey:life");
    public static final ResourceLocation RESERVE = ResourceLocation.parse("sagadyssey:life_reserve"),
            MOVE = ResourceLocation.parse("sagadyssey:life_move"), ACT = ResourceLocation.parse("sagadyssey:life_act");
    private LifePlanFactory() { }
    public static NpcPlan create(UUID npc, String operation, String dimension, BlockPos target,
                                 int duration, long now, Map<String, String> extra) {
        if (duration < 20 || duration > 24000) throw new IllegalArgumentException("invalid life duration");
        var params = new HashMap<String, String>(extra);
        params.put("operation", operation); params.put("dimension", dimension);
        params.put("x", Integer.toString(target.getX())); params.put("y", Integer.toString(target.getY())); params.put("z", Integer.toString(target.getZ()));
        params.put("duration", Integer.toString(duration));
        var steps = List.of(new NpcPlanStep(0, NpcPlanPhase.RESERVING, RESERVE, params, NpcPlanStepStatus.PENDING, now, "预约生活设施"),
                new NpcPlanStep(1, NpcPlanPhase.TRAVELLING, MOVE, params, NpcPlanStepStatus.PENDING, now, "前往已知地点"),
                new NpcPlanStep(2, NpcPlanPhase.ACTING, ACT, params, NpcPlanStepStatus.PENDING, now, "真实生活活动"));
        return new NpcPlan(UUID.randomUUID(), npc, "life:" + operation + ':' + target.asLong(), PROVIDER,
                operation.equals("EAT") || operation.equals("SLEEP") ? NpcPlanKind.NEED : operation.equals("CARE") ? NpcPlanKind.HOUSEHOLD
                : operation.equals("TALK") || operation.equals("VISIT") ? NpcPlanKind.SOCIAL : NpcPlanKind.LEISURE,
                NpcPlanPriority.NORMAL, NpcPlanStatus.SELECTED, steps, 0, now, now, now + 40, now + duration + 2400, null, "在已知真实地点满足需求或履行生活活动");
    }
    public static BlockPos target(Map<String, String> params) {
        return new BlockPos(Integer.parseInt(params.get("x")), Integer.parseInt(params.get("y")), Integer.parseInt(params.get("z")));
    }
}
