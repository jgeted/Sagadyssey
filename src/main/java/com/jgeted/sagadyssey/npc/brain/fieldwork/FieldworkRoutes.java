package com.jgeted.sagadyssey.npc.brain.fieldwork;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** 站位、分段长途寻路和实体阶梯规划；仅规划，不批量搭建。 */
public final class FieldworkRoutes {
    private FieldworkRoutes() { }
    public record Route(BlockPos stand, BlockPos approach, List<BlockPos> scaffold, ItemStack material) {
        public Route { stand = stand.immutable(); approach = approach.immutable(); scaffold = List.copyOf(scaffold); material = material.copy(); }
    }

    public static Route plan(NpcBase npc, BlockPos target, boolean allowScaffold) {
        return plan(npc, target, allowScaffold, false);
    }
    public static Route plan(NpcBase npc, BlockPos target, boolean allowScaffold, boolean avoidTargetSupport) {
        List<BlockPos> stands = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(target.offset(-2, -4, -2), target.offset(2, 1, 2))) {
            if ((!avoidTargetSupport || !p.below().equals(target))
                    && walkable(npc, p) && reachableFrom(npc, p, target)) stands.add(p.immutable());
        }
        stands.sort(Comparator.comparingDouble(npc.blockPosition()::distSqr));
        for (BlockPos stand : stands) {
            if (path(npc, stand)) return new Route(stand, stand, List.of(), ItemStack.EMPTY);
        }
        if (!allowScaffold) return null;
        ItemStack material = scaffoldMaterial(npc);
        if (material.isEmpty()) return null;
        Route best = null;
        double score = Double.MAX_VALUE;
        for (int dy = -4; dy <= -1; dy++) {
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                BlockPos stand = target.offset(dx, dy, dz);
                if (!reachableFrom(npc, stand, target) || !clear(npc, stand)) continue;
                for (int height = 1; height <= 3; height++) {
                    if (material.getCount() < height * (height + 1) / 2) continue;
                    BlockPos base = stand.below(height);
                    for (Direction direction : Direction.Plane.HORIZONTAL) {
                        List<BlockPos> blocks = new ArrayList<>();
                        boolean valid = true;
                        for (int step = 1; step <= height; step++) {
                            BlockPos column = base.relative(direction, height - step);
                            if (!solid(npc, column.below())) { valid = false; break; }
                            for (int layer = 0; layer < step; layer++) {
                                BlockPos block = column.above(layer);
                                if (!FieldworkDiscovery.allowed(npc, block) || !npc.level().hasChunkAt(block)
                                        || !npc.level().getBlockState(block).isAir()) { valid = false; break; }
                                blocks.add(block);
                            }
                            if (!valid) break;
                        }
                        BlockPos approach = base.relative(direction, height);
                        for (BlockPos block : blocks) {
                            BlockPos side = new BlockPos(block.getX(), base.getY(), block.getZ())
                                    .offset(direction.getAxis() == Direction.Axis.X ? 0 : 1, 0,
                                            direction.getAxis() == Direction.Axis.X ? 1 : 0);
                            if (!walkable(npc, side) || !reachableFrom(npc, side, block)) valid = false;
                        }
                        if (!valid || !walkable(npc, approach) || !path(npc, approach)) continue;
                        double candidateScore = blocks.size() * 1_000D + npc.blockPosition().distSqr(approach);
                        if (candidateScore < score) {
                            score = candidateScore;
                            best = new Route(stand, approach, blocks, material);
                        }
                    }
                }
            }
        }
        return best;
    }

    public static boolean closeEnough(NpcBase npc, BlockPos target) {
        return npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) <= 20.25D
                && visible(npc, npc.getEyePosition(), target);
    }

    public static boolean move(NpcBase npc, BlockPos target) {
        if (com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.route(npc,target)) return true;
        if (com.jgeted.sagadyssey.social.growth.SettlementCoordinatorData.needsBoundedNavigation(npc, target)) {
            return com.jgeted.sagadyssey.social.growth.SettlementSurveyNavigation.move(npc, target);
        }
        BlockPos waypoint = target;
        double dx = target.getX() - npc.getX(), dz = target.getZ() - npc.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        // Couriers can have a native path-search radius smaller than 24. Keep every
        // local segment inside that radius; do not leave a 16..24-block dead zone.
        if (distance > 12D) {
            int x = (int) Math.floor(npc.getX() + dx / distance * 8D);
            int z = (int) Math.floor(npc.getZ() + dz / distance * 8D);
            List<BlockPos> candidates = new ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(new BlockPos(x - 2, npc.getBlockY() - 4, z - 2),
                    new BlockPos(x + 2, npc.getBlockY() + 4, z + 2))) if (walkable(npc, p)) candidates.add(p.immutable());
            candidates.sort(Comparator.comparingDouble(target::distSqr));
            waypoint = candidates.stream().filter(p -> path(npc, p)).findFirst().orElse(null);
            if (waypoint == null) return false;
        }
        return com.jgeted.sagadyssey.worldsim.lod.NpcNavigationQueue.move(npc, waypoint, 1D);
    }

    private static boolean path(NpcBase npc, BlockPos stand) {
        if (npc.distanceToSqr(stand.getX() + .5, stand.getY(), stand.getZ() + .5) < .25D) return true;
        // v7's registered courtyard door is a real staged route, shared by work,
        // deposit and daily life. It does not mark the target as reached.
        if (com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.gateWaypoint(npc,stand)!=null) return true;
        // 远处站位只做物理验证，走近后再询问最终寻路；原版跟随距离不能裁掉远区任务。
        if (npc.blockPosition().distSqr(stand) > 12D * 12D) return true;
        var path = npc.getNavigation().createPath(stand, 0);
        return path != null && path.canReach();
    }
    public static boolean walkable(NpcBase npc, BlockPos p) { return clear(npc, p) && solid(npc, p.below()); }
    private static boolean clear(NpcBase npc, BlockPos p) {
        return npc.level().hasChunkAt(p) && npc.level().hasChunkAt(p.above())
                && npc.level().getBlockState(p).getCollisionShape(npc.level(), p).isEmpty()
                && npc.level().getBlockState(p.above()).getCollisionShape(npc.level(), p.above()).isEmpty()
                && npc.level().getFluidState(p).isEmpty() && npc.level().getFluidState(p.above()).isEmpty();
    }
    private static boolean solid(NpcBase npc, BlockPos p) {
        return npc.level().hasChunkAt(p) && npc.level().getFluidState(p).isEmpty()
                && npc.level().getBlockEntity(p) == null
                && (npc.level().getBlockState(p).isFaceSturdy(npc.level(), p, Direction.UP)
                || npc.level().getBlockState(p).is(Blocks.FARMLAND) || npc.level().getBlockState(p).is(Blocks.DIRT_PATH));
    }
    private static boolean reachableFrom(NpcBase npc, BlockPos stand, BlockPos target) {
        Vec3 eye = Vec3.atBottomCenterOf(stand).add(0, npc.getEyeHeight(), 0);
        return eye.distanceToSqr(Vec3.atCenterOf(target)) <= 20.25D && visible(npc, eye, target);
    }
    private static boolean visible(NpcBase npc, Vec3 eye, BlockPos target) {
        Vec3 center = Vec3.atCenterOf(target);
        // 合法作业可以瞄准暴露表面，而非必须穿过相邻土块看见地下方块的中心。
        for (Vec3 aim : List.of(center, center.add(0, .499D, 0))) {
            if (eye.distanceToSqr(aim) > 20.25D) continue;
            var hit = npc.level().clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc));
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target)) return true;
        }
        return false;
    }
    private static ItemStack scaffoldMaterial(NpcBase npc) {
        return npc.getPersonalInventory().first(stack -> stack.getItem() instanceof BlockItem item
                && !(item.getBlock() instanceof FallingBlock) && item.getBlock() != Blocks.TNT
                && !item.getBlock().defaultBlockState().hasBlockEntity()
                && item.getBlock().defaultBlockState().isCollisionShapeFullBlock(npc.level(), npc.blockPosition()));
    }
}
