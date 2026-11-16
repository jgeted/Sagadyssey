package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.world.entity.ai.goal.Goal;
import java.util.*;

/** 唯一主劳动控制Goal；真实游泳仍可抢占，不以NoAI禁用实体。 */
public final class NpcPrimaryWorkGoal extends Goal {
    private final NpcBase npc;
    private NpcPrimaryWorkControl running;
    public NpcPrimaryWorkGoal(NpcBase npc) { this.npc = npc; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)); }
    public boolean canUse() { return npc.isAlive() && !npc.isInWater() && NpcPrimaryWorkRuntime.reserved(npc); }
    public boolean canContinueToUse() { return canUse(); }
    public boolean requiresUpdateEveryTick() { return true; }
    public void start() { npc.getNavigation().stop(); npc.setTarget(null); }
    public void tick() {
        var binding = NpcPrimaryWorkRuntime.binding(npc); if (binding.isEmpty()) return;
        if (running != null && running != binding.get().control()) running.stop(npc); running = binding.get().control();
        var work = binding.get().commitment();
        if (!work.intent().body().equals(npc.getUUID())) { npc.getNavigation().stop(); npc.setTarget(null); npc.reportWorkActivity("等待主劳动核对", null, "原身体不一致，不接管替代实体"); return; }
        running.tick(npc, work, npc.level().getGameTime());
    }
    public void stop() { if (running != null) running.stop(npc); running = null; npc.getNavigation().stop(); npc.setTarget(null); }
}
