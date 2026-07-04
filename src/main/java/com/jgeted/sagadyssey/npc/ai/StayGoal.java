package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 待命行为：当 NPC 命令为 STAY 时，阻止所有水平位移。
 */
public class StayGoal extends Goal {

    private final NpcBase npc;

    public StayGoal(NpcBase npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!npc.isAlive() || npc.getCommand() != NpcCommand.STAY) return false;
        // 近身有敌时放行，让 MeleeAttackGoal 接管反击（6 格内）
        if (npc.getTarget() != null && npc.getTarget().isAlive()
                && npc.distanceToSqr(npc.getTarget()) < 36.0D) {
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        npc.getNavigation().stop();
    }

    @Override
    public void tick() {
        npc.setDeltaMovement(0.0D, npc.getDeltaMovement().y, 0.0D);
        npc.xxa = 0.0F;
        npc.zza = 0.0F;
    }
}
