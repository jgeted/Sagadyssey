package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.social.life.NpcAgencyDecision;
import com.jgeted.sagadyssey.social.combat.InjuryData;
import com.jgeted.sagadyssey.social.combat.TriageAssessment;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 低血量撤退+回血。
 * 撤退和恢复阈值由当前士气档位决定；士气越高，越能坚持战斗。
 * 撤退后向主人靠拢、停止攻击并缓慢回血。
 */
public class LowHpRetreatGoal extends Goal {

    private final NpcBase npc;
    private int regenTimer;
    private int nextDecisionTick;
    private LivingEntity threat;

    private static final double OWNER_TOO_FAR = 256.0D;

    public LowHpRetreatGoal(NpcBase npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if(com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc))return false;
        float hpRatio = npc.getHealth() / npc.getMaxHealth();
        TriageAssessment triage = triage();
        double woundThreshold = Math.min(0.82D, 0.18D + triage.totalTrauma() / 180.0D
                + triage.mobilityPenalty() / 300.0D);
        double retreatThreshold = Math.max(npc.getMoraleState().retreatThreshold(), woundThreshold);
        if (!triage.incapacitated() && hpRatio > retreatThreshold) return false;

        Player owner = npc.getActiveOwner();
        threat = npc.getTarget();
        boolean hasOwnerShelter = owner != null && owner.isAlive()
                && npc.distanceToSqr(owner) < OWNER_TOO_FAR;
        if (!hasOwnerShelter && (threat == null || !threat.isAlive())) return false;
        if (npc.tickCount < nextDecisionTick) return false;

        double threshold = Math.max(0.05D, retreatThreshold);
        double injury = Math.max(0.0D, Math.min(1.0D, (threshold - hpRatio) / threshold));
        nextDecisionTick = npc.tickCount + 20 + npc.getRandom().nextInt(41);
        double situation = 22.0D + injury * 52.0D + triage.bleeding() * 0.25D
                + triage.mobilityPenalty() * 0.15D + (hasOwnerShelter ? 5.0D : 0.0D);
        if (!NpcAgencyDecision.choose(npc, NpcAgencyDecision.Action.RETREAT,
                situation, 0.0D, true)) {
            threat = null;
            return false;
        }

        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if(com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc))return false;
        float hpRatio = npc.getHealth() / npc.getMaxHealth();
        TriageAssessment triage = triage();
        if (!triage.incapacitated() && triage.bleeding() < 20
                && hpRatio >= npc.getMoraleState().recoverThreshold()) return false;

        Player owner = npc.getActiveOwner();
        return owner != null && owner.isAlive() || threat != null && threat.isAlive();
    }

    @Override
    public void start() {
        npc.setTarget(null);
        npc.getNavigation().stop();
        regenTimer = 0;
    }

    @Override
    public void tick() {
        Player owner = npc.getActiveOwner();
        if (owner == null && (threat == null || !threat.isAlive())) return;

        if (owner != null && npc.isPassenger()) {
            // 骑乘撤退：直接设置移动方向，让马跑
            double dx = owner.getX() - npc.getX();
            double dz = owner.getZ() - npc.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > 2.0) {
                float targetYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
                npc.setYRot(targetYaw);
                npc.yHeadRot = targetYaw;
                npc.zza = 1.0F;
                npc.xxa = 0.0F;
            } else {
                npc.zza = 0.0F;
                npc.xxa = 0.0F;
            }
        } else if (owner != null) {
            npc.getNavigation().moveTo(owner, retreatSpeed());
        } else {
            Vec3 away = npc.position().subtract(threat.position());
            if (away.lengthSqr() < 0.01D) away = new Vec3(1.0D, 0.0D, 0.0D);
            Vec3 destination = npc.position().add(away.normalize().scale(12.0D));
            npc.getNavigation().moveTo(destination.x, destination.y, destination.z, retreatSpeed());
        }

        if (++regenTimer >= 20) {
            regenTimer = 0;
            npc.heal(1.0F);
        }
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        threat = null;
    }

    private TriageAssessment triage() {
        if (npc.level() instanceof ServerLevel level) {
            return InjuryData.get(level.getServer()).triage(npc.getCharacterId(), level.getGameTime());
        }
        return new TriageAssessment(0, 0, 0, 0, 0, 0, false, false);
    }

    private double retreatSpeed() {
        int penalty = triage().mobilityPenalty();
        return 1.3D * Math.max(0.45D, 1.0D - penalty / 140.0D);
    }

}
