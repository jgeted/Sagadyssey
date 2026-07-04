package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.faction.NpcFaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;

/**
 * 保护主人 AI：跟随模式下，自动攻击主人周围的威胁。
 */
public class ProtectOwnerGoal extends Goal {

    private final NpcBase npc;
    private int scanCooldown;

    private static final double SCAN_RANGE = 16.0D;

    public ProtectOwnerGoal(NpcBase npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (npc.getCommand() != NpcCommand.FOLLOW) return false;
        if (npc.getOwnerUUID() == null) return false;

        if (scanCooldown > 0) {
            scanCooldown--;
            return false;
        }
        scanCooldown = 3;

        Player owner = npc.level().getPlayerByUUID(npc.getOwnerUUID());
        if (owner == null || !owner.isAlive()) return false;

        // 同时扫描 NPC 自身周围和主人周围
        AABB npcBox = npc.getBoundingBox().inflate(SCAN_RANGE);
        List<LivingEntity> nearby = npc.level().getEntitiesOfClass(LivingEntity.class, npcBox,
                e -> e.isAlive() && e != owner && e != npc && isThreat(e, owner));

        AABB ownerBox = owner.getBoundingBox().inflate(SCAN_RANGE);
        List<LivingEntity> ownerNearby = npc.level().getEntitiesOfClass(LivingEntity.class, ownerBox,
                e -> e.isAlive() && e != owner && e != npc && isThreat(e, owner));
        for (LivingEntity e : ownerNearby) {
            if (!nearby.contains(e)) nearby.add(e);
        }

        if (!nearby.isEmpty()) {
            LivingEntity target = nearby.get(0);
            if (npc.getTarget() != target) {
                npc.setTarget(target);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (npc.getCommand() != NpcCommand.FOLLOW) return false;
        LivingEntity target = npc.getTarget();
        if (target == null || !target.isAlive()) return false;

        Player owner = npc.level().getPlayerByUUID(npc.getOwnerUUID());
        if (owner == null || !owner.isAlive()) return false;

        if (npc.distanceToSqr(owner) > 256.0D) return false;
        return npc.distanceToSqr(target) <= SCAN_RANGE * SCAN_RANGE && isThreat(target, owner);
    }

    @Override
    public void start() {
        // target already set in canUse
    }

    @Override
    public void stop() {
        LivingEntity target = npc.getTarget();
        if (target != null && !(target instanceof NpcBase)) {
            npc.setTarget(null);
        }
    }

    private boolean isThreat(LivingEntity entity, Player owner) {
        // 1. 正在攻击这个 NPC → 自卫
        if (entity instanceof Mob mob && mob.getTarget() == npc) return true;
        // 2. 主人正在攻击且主人在 NPC 附近（10 格内）→ 助战
        if (entity == owner.getLastHurtMob() && npc.distanceToSqr(owner) < 100.0D) return true;
        // 3. 敌对阵营 NPC
        if (entity instanceof NpcBase otherNpc
                && otherNpc.getFaction() == NpcFaction.HOSTILE
                && !otherNpc.isOwnedBy(owner.getUUID())) return true;
        return false;
    }
}
