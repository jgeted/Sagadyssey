package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.life.NpcAgencyDecision;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** 吟游诗人周期性演奏，为附近盟友恢复士气；WORK 状态视为正式庆典演奏。 */
public class BardSupportGoal extends Goal {
    private final NpcBase npc;

    public BardSupportGoal(NpcBase npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!npc.isAlive() || npc.level().isClientSide
                || com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)
                || npc.getProfession() != NpcProfession.BARD
                || npc.getCommand() == NpcCommand.STAY
                || npc.getTarget() != null || npc.isPassenger() || npc.hasActiveAction()) return false;
        var schedule = com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.current(npc).activity();
        if (schedule == com.jgeted.sagadyssey.social.life.NpcScheduleActivity.REST
                || schedule == com.jgeted.sagadyssey.social.life.NpcScheduleActivity.URGENT_NEEDS
                || npc.getCommand() == NpcCommand.WORK
                && !com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsWork(npc)) return false;
        int interval = npc.getCommand() == NpcCommand.WORK ? 160 : 260;
        if ((npc.tickCount - npc.getId()) % interval != 0) return false;
        if (!NpcAgencyDecision.choose(npc, NpcAgencyDecision.Action.CARE,
                npc.getCommand() == NpcCommand.WORK ? 40.0D : 28.0D, 1.0D, false)) return false;
        return npc.requestMutex(AiMutex.SOCIAL);
    }

    @Override
    public void start() {
        int gain = npc.getCommand() == NpcCommand.WORK ? 5 : 2;
        double radius = 10.0D + npc.getNpcLevel() * 2.0D;
        int helped = 0;
        for (NpcBase ally : npc.level().getEntitiesOfClass(NpcBase.class,
                npc.getBoundingBox().inflate(radius), other -> other.isAlive() && npc.isCombatAlly(other))) {
            int before = ally.getMoral();
            ally.adjustMoral(gain);
            if (ally.getMoral() > before) helped++;
        }
        npc.playAction(NpcAction.PERFORM, 80);
        npc.playSound(SoundEvents.NOTE_BLOCK_HARP.value(), 0.9F,
                0.85F + npc.getRandom().nextFloat() * 0.3F);
        if (npc.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.NOTE, npc.getX(), npc.getY() + 2.0D, npc.getZ(),
                    4, 0.6D, 0.3D, 0.6D, 0.0D);
        }
        if (helped > 0) npc.addExperience(1);
        npc.releaseMutex(AiMutex.SOCIAL);
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }
}
