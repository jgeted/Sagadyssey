package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcLifeRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/** 工作窗口开始后把 WORK NPC 从住宅送回登记岗位；具体职业行为仍由各自目标负责。 */
public final class NpcWorkCommuteGoal extends Goal {
    private static final int CLOSE_ENOUGH_DISTANCE = 4;
    private static final int SAFE_DESTINATION_RADIUS = 8;
    private static final double DESTINATION_REACHED_DISTANCE = 1.5D;
    private static final int MAX_COMMUTE_TICKS = 2400;
    private static final int[] VERTICAL_SEARCH_OFFSETS = {0, 1, -1, 2, -2};

    private final NpcBase npc;
    private BlockPos workplace;
    private BlockPos destination;
    private Path path;
    private int travelTicks;
    private boolean finished;

    public NpcWorkCommuteGoal(NpcBase npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        clear();
        if (!(npc.level() instanceof ServerLevel level) || !npc.isAlive() || npc.isSleeping()
                || com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)
                || npc.isPassenger() || npc.getTarget() != null
                || npc.getCommand() != NpcCommand.WORK
                || !com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsWork(npc)
                || (npc.tickCount - npc.getId()) % 20 != 0) return false;
        // 农田的实体脚位会因耕地不足一格高而向下取整；只要已经处在分配工作区内，
        // 职业目标就能直接规划整块区域，不应再按登记中心的 Y 坐标强制通勤。
        if (npc.hasWorkZone() && npc.isWorkAllowedAt(npc.blockPosition())) return false;
        NpcLifeRecord life = NpcLifeData.get(level.getServer()).record(npc.getCharacterId());
        if (life == null || life.activeWorkplace() == null
                || !life.dimension().equals(level.dimension().identifier().toString())) return false;
        workplace = life.activeWorkplace().immutable();
        if (!level.hasChunkAt(workplace)
                || workplace.distManhattan(npc.blockPosition()) <= CLOSE_ENOUGH_DISTANCE) return false;

        // 岗位可能是熔炉、钟或工作区中心，不能让宽松的终点距离把设备顶部当成落点。
        // 先选附近真实可站立的空气方块，再生成一条到该方块的连续精确路径。
        if (!findSafeDestination(level)) {
            npc.reportWorkActivity("上班通勤", workplace, "登记工作地点附近没有可达的安全地面");
            clear();
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !finished && workplace != null && destination != null && path != null && npc.isAlive()
                && !com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)
                && !npc.isSleeping() && npc.getTarget() == null
                && npc.getCommand() == NpcCommand.WORK
                && com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsWork(npc)
                && travelTicks < MAX_COMMUTE_TICKS
                && !npc.getNavigation().isDone();
    }

    @Override
    public void start() {
        travelTicks = 0;
        finished = false;
        if (!npc.getNavigation().moveTo(path, 0.9D)) {
            finished = true;
            npc.reportWorkActivity("上班通勤", workplace, "无法开始前往登记工作地点");
            return;
        }
        npc.reportWorkActivity("上班通勤", workplace,
                "沿连续路径返回岗位安全落点=" + destination.toShortString());
    }

    @Override
    public void tick() {
        travelTicks++;
        if (workplace == null || destination == null) return;
        npc.getLookControl().setLookAt(workplace.getX() + 0.5D, workplace.getY() + 0.5D,
                workplace.getZ() + 0.5D, 25.0F, npc.getMaxHeadXRot());
        if (destination.closerToCenterThan(npc.position(), DESTINATION_REACHED_DISTANCE)) {
            finished = true;
            npc.reportWorkActivity("上班通勤", workplace,
                    "已抵达岗位旁安全地面=" + destination.toShortString() + "，开始职业行为");
        }
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        clear();
    }

    private boolean findSafeDestination(ServerLevel level) {
        BlockPos origin = npc.blockPosition();
        for (int dy : VERTICAL_SEARCH_OFFSETS) {
            for (int radius = 0; radius <= SAFE_DESTINATION_RADIUS; radius++) {
                List<BlockPos> candidates = new ArrayList<>();
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                        BlockPos candidate = workplace.offset(dx, dy, dz);
                        if (isSafeDestination(level, candidate)) candidates.add(candidate.immutable());
                    }
                }
                candidates.sort(Comparator.comparingDouble(origin::distSqr));
                for (BlockPos candidate : candidates) {
                    Path candidatePath = npc.getNavigation().createPath(candidate, 0);
                    if (candidatePath == null || !candidatePath.canReach()) continue;
                    destination = candidate;
                    path = candidatePath;
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSafeDestination(ServerLevel level, BlockPos candidate) {
        if (!level.hasChunkAt(candidate)) return false;
        BlockState feet = level.getBlockState(candidate);
        BlockState head = level.getBlockState(candidate.above());
        BlockPos floorPos = candidate.below();
        if (!feet.isAir() || !feet.getFluidState().isEmpty()
                || !head.getCollisionShape(level, candidate.above()).isEmpty()
                || !head.getFluidState().isEmpty()) return false;
        return InteractionApproach.isSafeStandFloor(level, floorPos);
    }

    private void clear() {
        workplace = null;
        destination = null;
        path = null;
        travelTicks = 0;
        finished = false;
    }
}
