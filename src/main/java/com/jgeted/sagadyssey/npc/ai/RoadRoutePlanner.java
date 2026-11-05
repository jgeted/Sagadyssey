package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 轻量道路偏好器。它不代替原版寻路，只在当前 NPC 附近选一个更接近目标的道路路点。
 */
public final class RoadRoutePlanner {
    // 每次从 2205 个方块降到 867 个；运输者每次重算路径时不会造成明显尖峰。
    private static final int SEARCH_RADIUS = 8;
    private static final int VERTICAL_RADIUS = 3;

    private RoadRoutePlanner() { }

    public static BlockPos nextWaypoint(NpcBase npc, BlockPos destination) {
        Level level = npc.level();
        Entity mover = npc.isPassenger() && npc.getVehicle() != null ? npc.getVehicle() : npc;
        BlockPos from = mover.blockPosition();
        double directDistance = from.distSqr(destination);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int x = from.getX() - SEARCH_RADIUS; x <= from.getX() + SEARCH_RADIUS; x++) {
            for (int z = from.getZ() - SEARCH_RADIUS; z <= from.getZ() + SEARCH_RADIUS; z++) {
                for (int y = from.getY() - VERTICAL_RADIUS; y <= from.getY() + VERTICAL_RADIUS; y++) {
                    BlockPos surface = new BlockPos(x, y, z);
                    if (!level.hasChunkAt(surface) || !isRoad(level.getBlockState(surface))) continue;
                    BlockPos stand = surface.above();
                    if (!hasClearance(level, stand, npc.isPassenger() ? 4 : 2)) continue;

                    double remaining = stand.distSqr(destination);
                    if (remaining >= directDistance - 1.0D) continue;
                    double local = from.distSqr(stand);
                    if (local < 2.0D) continue;

                    // 目标距离为主，小幅惩罚横向绕路，避免为了踩路走极端折线。
                    double score = remaining + local * 0.35D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = stand;
                    }
                }
            }
        }
        return best != null ? best : destination;
    }

    private static boolean isOpen(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getFluidState(pos).isEmpty();
    }

    private static boolean hasClearance(Level level, BlockPos stand, int height) {
        for (int y = 0; y < height; y++) {
            if (!isOpen(level, stand.above(y))) return false;
        }
        return true;
    }

    private static boolean isRoad(BlockState state) {
        return state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.MUD_BRICKS)
                || state.is(Blocks.BRICKS)
                || state.is(BlockTags.PLANKS);
    }
}
