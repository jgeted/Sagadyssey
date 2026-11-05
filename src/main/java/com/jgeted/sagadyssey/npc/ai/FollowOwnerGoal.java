package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.util.EnumSet;

/**
 * 跟随主人行为：NPC 以固定速度跟随主人，保持目标距离。
 * 距离过远时在主人附近寻找可站立位置归队，避免旧的“超过上限后永远停留”。
 */
public class FollowOwnerGoal extends Goal {

    private final NpcBase npc;
    private final double speed;
    private final float stopDistance;
    private final float teleportDistance;
    private final PathNavigation navigation;

    /** 骑乘单位跑得更快，允许更大的自然追赶距离后再整体归队。 */
    private static final float MOUNTED_TELEPORT_DISTANCE = 72.0F;
    /** 骑乘时的停止距离（留更多空间给马转身） */
    private static final float MOUNTED_STOP_DISTANCE = 4.0F;
    private static final int TELEPORT_ATTEMPTS = 12;

    private LivingEntity owner;
    private int timeToRecalcPath;
    private float oldWaterMalus;

    private float getEffectiveStopDistance() {
        return npc.isPassenger() ? MOUNTED_STOP_DISTANCE : stopDistance;
    }

    private float getEffectiveTeleportDistance() {
        return npc.isPassenger() ? MOUNTED_TELEPORT_DISTANCE : teleportDistance;
    }

    public FollowOwnerGoal(NpcBase npc, double speed, float stopDistance, float teleportDistance) {
        this.npc = npc;
        this.speed = speed;
        this.stopDistance = stopDistance;
        this.teleportDistance = teleportDistance;
        this.navigation = npc.getNavigation();
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        if (npc.getCommand() != NpcCommand.FOLLOW) {
            return false;
        }

        if (!(npc.getActiveOwner() instanceof LivingEntity player)) {
            return false;
        }

        if (!player.level().equals(npc.level())) {
            return false;
        }

        double distSqr = npc.distanceToSqr(player);
        float effectiveStop = getEffectiveStopDistance();

        if (distSqr < (double) (effectiveStop * effectiveStop)) {
            return false;
        }

        this.owner = player;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        if (npc.getCommand() != NpcCommand.FOLLOW) {
            return false;
        }
        if (owner == null || !owner.isAlive() || npc.getActiveOwner() != owner) {
            return false;
        }
        if (!owner.level().equals(npc.level())) {
            return false;
        }

        double distSqr = npc.distanceToSqr(owner);
        float effectiveStop = getEffectiveStopDistance();
        return distSqr >= (double) (effectiveStop * effectiveStop);
    }

    @Override
    public void start() {
        timeToRecalcPath = 0;
        oldWaterMalus = npc.getPathfindingMalus(PathType.WATER);
        npc.setPathfindingMalus(PathType.WATER, 0.0F);
    }

    @Override
    public void stop() {
        owner = null;
        navigation.stop();
        npc.setPathfindingMalus(PathType.WATER, oldWaterMalus);
    }

    @Override
    public void tick() {
        npc.getLookControl().setLookAt(owner, 10.0F, npc.getMaxHeadXRot());

        if (--timeToRecalcPath <= 0) {
            timeToRecalcPath = adjustedTickDelay(10);

            float teleportAt = getEffectiveTeleportDistance();
            if (npc.distanceToSqr(owner) >= (double) (teleportAt * teleportAt)) {
                // FOLLOW 的远距归队优先于已经脱离主人的旧战斗目标。
                npc.setTarget(null);
                npc.clearOwnerCombatTargets();
                if (tryTeleportNearOwner()) {
                    return;
                }
            }

            // 骑乘时由 NpcBase.tick() 驱马；传送失败后仍让既有骑乘驱动继续追赶。
            if (npc.isPassenger()) return;
            navigation.moveTo(owner, speed);
        }
    }

    /**
     * 采用原版驯服生物同类的落点规则，但骑乘时传送整个坐骑单元。
     * 被拴住的 NPC/坐骑代表玩家明确锚定，不会被远距跟随强行拉走。
     */
    private boolean tryTeleportNearOwner() {
        Entity movingEntity = npc.isPassenger() ? npc.getVehicle() : npc;
        if (movingEntity == null) return false;
        if (movingEntity instanceof Leashable leashable && leashable.isLeashed()) return false;

        BlockPos ownerPos = owner.blockPosition();
        for (int i = 0; i < TELEPORT_ATTEMPTS; i++) {
            int xOffset = npc.getRandom().nextIntBetweenInclusive(-4, 4);
            int zOffset = npc.getRandom().nextIntBetweenInclusive(-4, 4);
            if (Math.abs(xOffset) < 2 && Math.abs(zOffset) < 2) continue;

            int yOffset = npc.getRandom().nextIntBetweenInclusive(-1, 1);
            BlockPos destination = ownerPos.offset(xOffset, yOffset, zOffset);
            if (canTeleportTo(movingEntity, destination)) {
                movingEntity.teleportTo(destination.getX() + 0.5D, destination.getY(),
                        destination.getZ() + 0.5D);
                navigation.stop();
                if (movingEntity instanceof PathfinderMob mob) {
                    mob.getNavigation().stop();
                }
                return true;
            }
        }
        return false;
    }

    private boolean canTeleportTo(Entity movingEntity, BlockPos destination) {
        if (!npc.level().getWorldBorder().isWithinBounds(destination)) return false;
        if (WalkNodeEvaluator.getPathTypeStatic(npc, destination) != PathType.WALKABLE) return false;

        BlockState support = npc.level().getBlockState(destination.below());
        if (support.getBlock() instanceof LeavesBlock) return false;

        double dx = destination.getX() + 0.5D - movingEntity.getX();
        double dy = destination.getY() - movingEntity.getY();
        double dz = destination.getZ() + 0.5D - movingEntity.getZ();
        return npc.level().noCollision(movingEntity, movingEntity.getBoundingBox().move(dx, dy, dz));
    }
}
