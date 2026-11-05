package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import net.minecraft.core.BlockPos;
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
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        if (!npc.isAlive() || npc.getCommand() != NpcCommand.STAY
                || !NpcSchedulePlanner.allowsStay(npc)) return false;
        // 普通职业仅近身反击；重甲兵会在十格驻守圈内主动迎击。
        double combatRadiusSq = npc.getProfession() == NpcProfession.HEAVY ? 100.0D : 36.0D;
        if (npc.getTarget() != null && npc.getTarget().isAlive()
                && npc.distanceToSqr(npc.getTarget()) < combatRadiusSq) {
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        if (!npc.isAlive() || npc.getCommand() != NpcCommand.STAY
                || !NpcSchedulePlanner.allowsStay(npc)) return false;
        // 原生 GoalSelector 根据 Flag.MOVE 与优先级把控制权交给战斗行为。
        if (npc.getTarget() != null && npc.getTarget().isAlive()) {
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        BlockPos anchor = npc.getGuardAnchor();
        if (npc.getProfession() == NpcProfession.HEAVY && anchor != null
                && npc.distanceToSqr(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D) > 2.25D) {
            npc.getNavigation().moveTo(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D, 0.8D);
        } else {
            npc.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        BlockPos anchor = npc.getGuardAnchor();
        if (npc.getProfession() == NpcProfession.HEAVY && anchor != null
                && npc.distanceToSqr(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D) > 2.25D) {
            if (npc.tickCount % 20 == 0) {
                npc.getNavigation().moveTo(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D, 0.8D);
            }
            return;
        }
        npc.setDeltaMovement(0.0D, npc.getDeltaMovement().y, 0.0D);
        npc.xxa = 0.0F;
        npc.zza = 0.0F;
    }

}
