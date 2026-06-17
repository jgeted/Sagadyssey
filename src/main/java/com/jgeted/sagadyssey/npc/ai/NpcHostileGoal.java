package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.faction.NpcFaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;

/**
 * 敌对 NPC 攻击目标选择器。
 * 当 NPC 阵营为 HOSTILE 时，搜索范围内非主人的玩家作为攻击目标。
 */
public class NpcHostileGoal extends Goal {

    private static final double DETECT_RANGE = 16.0D;
    private final NpcBase npc;
    private Player pendingTarget;
    private int scanCooldown;

    public NpcHostileGoal(NpcBase npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (npc.getFaction() != NpcFaction.HOSTILE) {
            return false;
        }

        if (--scanCooldown > 0) {
            return false;
        }
        scanCooldown = 20;

        AABB box = npc.getBoundingBox().inflate(DETECT_RANGE);
        List<Player> players = npc.level().getEntitiesOfClass(Player.class, box,
                p -> p.isAlive() && !p.isSpectator() && !p.isCreative());

        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : players) {
            if (npc.isOwnedBy(p.getUUID())) continue;
            double dist = npc.distanceToSqr(p);
            if (dist < bestDist) {
                bestDist = dist;
                best = p;
            }
        }

        if (best != null) {
            this.pendingTarget = best;
            return true;
        }
        return false;
    }

    @Override
    public void start() {
        npc.setTarget(pendingTarget);
        pendingTarget = null;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = npc.getTarget();
        if (target == null || !target.isAlive()) return false;
        if (target instanceof Player p && (p.isSpectator() || p.isCreative())) return false;
        if (npc.isOwnedBy(target.getUUID())) return false;
        return npc.distanceToSqr(target) <= DETECT_RANGE * DETECT_RANGE;
    }

    @Override
    public void stop() {
        npc.setTarget(null);
    }
}
