package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.social.life.NpcAgencyDecision;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/** 战斗居民沿驻地四周巡逻；战斗目标出现时立即释放移动控制权。 */
public final class PatrolGoal extends Goal {
    private final NpcBase npc;
    private BlockPos destination;
    private int routeIndex;
    private int travelTicks;
    private int waitTicks;

    public PatrolGoal(NpcBase npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        if (!(npc.level() instanceof ServerLevel level) || !npc.isAlive()
                || npc.getCommand() != NpcCommand.PATROL || npc.getTarget() != null
                || !com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsPatrol(npc)
                || npc.getPatrolCenter() == null || npc.getPatrolRadius() <= 0) return false;
        if (waitTicks > 0) {
            waitTicks--;
            return false;
        }
        if ((npc.tickCount - npc.getId()) % 20 != 0
                || !NpcAgencyDecision.choose(npc, NpcAgencyDecision.Action.PATROL,
                44.0D, 5.0D, false)) return false;
        destination = nextDestination(level);
        if (!npc.isPassenger() && npc.getNavigation().createPath(destination, 1) == null) {
            destination = npc.getPatrolCenter();
        }
        return destination != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        return destination != null && travelTicks < 400 && npc.getTarget() == null
                && npc.getCommand() == NpcCommand.PATROL
                && com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsPatrol(npc)
                && npc.distanceToSqr(destination.getX() + 0.5D, destination.getY(),
                destination.getZ() + 0.5D) > 3.0D;
    }

    @Override
    public void start() {
        travelTicks = 0;
        move();
    }

    @Override
    public void tick() {
        travelTicks++;
        if (travelTicks % 30 == 0) move();
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        npc.clearPatrolMoveTarget();
        destination = null;
        routeIndex++;
        waitTicks = 30 + npc.getRandom().nextInt(41);
    }

    private void move() {
        if (destination != null) {
            if (npc.isPassenger()) {
                npc.setPatrolMoveTarget(destination);
            } else {
                npc.clearPatrolMoveTarget();
                npc.getNavigation().moveTo(destination.getX() + 0.5D, destination.getY(),
                        destination.getZ() + 0.5D, 0.9D);
            }
        }
    }

    private BlockPos nextDestination(ServerLevel level) {
        BlockPos center = npc.getPatrolCenter();
        int radius = npc.getPatrolRadius();
        int[][] offsets = {{radius, 0}, {0, radius}, {-radius, 0}, {0, -radius}};
        int[] offset = offsets[Math.floorMod(routeIndex, offsets.length)];
        int x = center.getX() + offset[0];
        int z = center.getZ() + offset[1];
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }
}
