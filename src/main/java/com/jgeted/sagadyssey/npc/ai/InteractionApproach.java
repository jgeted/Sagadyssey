package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 为箱子、熔炉等不可站立方块寻找真实可达的交互位置。
 * 目标方块只用于交互；任何移动行为都必须前往其旁边的空气方块。
 */
public final class InteractionApproach {
    private static final int HORIZONTAL_RADIUS = 2;
    private static final double APPROACH_REACHED_SQ = 2.25D;

    private InteractionApproach() { }

    @Nullable
    public static BlockPos find(NpcBase npc, BlockPos target) {
        Level level = npc.level();
        Entity mover = npc.isPassenger() && npc.getVehicle() != null ? npc.getVehicle() : npc;
        BlockPos origin = mover.blockPosition();
        boolean mounted = npc.isPassenger();
        List<BlockPos> candidates = new ArrayList<>();

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -HORIZONTAL_RADIUS; dx <= HORIZONTAL_RADIUS; dx++) {
                for (int dz = -HORIZONTAL_RADIUS; dz <= HORIZONTAL_RADIUS; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos stand = target.offset(dx, dy, dz);
                    if (!level.hasChunkAt(stand) || !isClear(level, mover, stand, mounted)) continue;
                    // 最远候选仍须处在常规交互距离内；竖直差过大也不应被选中。
                    double targetDistance = stand.distSqr(target);
                    if (targetDistance > 8.0D) continue;
                    candidates.add(stand.immutable());
                }
            }
        }

        // 先选真正靠近交互方块的站位。原先优先选择离 NPC 最近的位置时，
        // 经常会选中斜向两格的候选；寻路器再提前一格结束，NPC 就会永久停在
        // “看起来已经靠近、实际仍超过交互距离”的位置。
        candidates.sort(Comparator
                .comparingInt((BlockPos pos) -> Math.abs(pos.getY() - target.getY()))
                .thenComparingDouble(pos -> pos.distSqr(target))
                .thenComparingDouble(origin::distSqr));

        if (mounted) return candidates.isEmpty() ? null : candidates.getFirst();
        for (BlockPos candidate : candidates) {
            Path path = npc.getNavigation().createPath(candidate, 0);
            if (path != null && path.canReach()) return candidate;
        }
        return null;
    }

    public static boolean move(NpcBase npc, BlockPos approach, double speed) {
        if (npc.isPassenger()) {
            npc.setLogisticsMoveTarget(approach);
            npc.getNavigation().stop();
            return true;
        }
        return com.jgeted.sagadyssey.worldsim.lod.NpcNavigationQueue.move(npc, approach, speed);
    }

    public static boolean reached(NpcBase npc, BlockPos approach, double rangeSq) {
        Entity mover = npc.isPassenger() && npc.getVehicle() != null ? npc.getVehicle() : npc;
        return mover.distanceToSqr(approach.getX() + 0.5D, approach.getY(), approach.getZ() + 0.5D)
                <= rangeSq;
    }

    /**
     * 容器本身的距离与寻路站位使用同一套完成条件。
     * 原版寻路允许在目标节点附近停止；只要 NPC 已到达经过可达性验证的站位，
     * 就不应再因为方块中心距离差几十厘米而无限重复寻路。
     */
    public static boolean readyToInteract(NpcBase npc, BlockPos target,
                                          @Nullable BlockPos approach, double directRangeSq) {
        Entity mover = npc.isPassenger() && npc.getVehicle() != null ? npc.getVehicle() : npc;
        if (mover.distanceToSqr(target.getX() + 0.5D, target.getY() + 0.5D,
                target.getZ() + 0.5D) <= directRangeSq) return true;
        return approach != null && reached(npc, approach, APPROACH_REACHED_SQ);
    }

    private static boolean isClear(Level level, Entity mover, BlockPos stand, boolean mounted) {
        int clearance = mounted ? 4 : 2;
        for (int y = 0; y < clearance; y++) {
            BlockPos pos = stand.above(y);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                    || !level.getFluidState(pos).isEmpty()) return false;
        }
        if (!isSafeStandFloor(level, stand.below())) return false;

        // 方块空间虽然可走，仍可能正被玩家或其他实体占用。预先排除该候选，
        // 让 NPC 选择设备的另一侧，而不是持续挤向同一个被挡住的站位。
        double targetX = stand.getX() + 0.5D;
        double targetY = stand.getY();
        double targetZ = stand.getZ() + 0.5D;
        var destinationBounds = mover.getBoundingBox().move(
                targetX - mover.getX(), targetY - mover.getY(), targetZ - mover.getZ()).deflate(0.05D);
        return level.noCollision(mover, destinationBounds);
    }

    static boolean isSafeStandFloor(Level level, BlockPos floorPos) {
        BlockState floor = level.getBlockState(floorPos);
        if (!floor.getFluidState().isEmpty()
                || !floor.isFaceSturdy(level, floorPos, Direction.UP)
                || floor.is(BlockTags.BEDS)
                || level.getBlockEntity(floorPos) != null) return false;
        return !floor.is(Blocks.ANVIL) && !floor.is(Blocks.CHIPPED_ANVIL)
                && !floor.is(Blocks.DAMAGED_ANVIL) && !floor.is(Blocks.SMITHING_TABLE)
                && !floor.is(Blocks.CRAFTING_TABLE) && !floor.is(Blocks.STONECUTTER)
                && !floor.is(Blocks.GRINDSTONE) && !floor.is(Blocks.LOOM)
                && !floor.is(Blocks.CARTOGRAPHY_TABLE) && !floor.is(Blocks.FLETCHING_TABLE)
                && !floor.is(Blocks.COMPOSTER) && !floor.is(Blocks.BREWING_STAND)
                && !floor.is(Blocks.BELL);
    }
}
