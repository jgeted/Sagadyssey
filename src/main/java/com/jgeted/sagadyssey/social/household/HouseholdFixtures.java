package com.jgeted.sagadyssey.social.household;

import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;

/** 固定世界迁移使用的小型实体家庭储物点。 */
public final class HouseholdFixtures {
    private HouseholdFixtures() { }

    @Nullable
    public static BlockPos ensureSharedPantry(ServerLevel level, BlockPos preferred,
                                             HouseholdData households, NpcLifeData lives) {
        String dimension = level.dimension().identifier().toString();
        LogisticsData logistics = LogisticsData.get(level.getServer());
        for (int radius = 4; radius <= 18; radius += 2) {
            for (int dx = -radius; dx <= radius; dx += 2) {
                for (int dz = -radius; dz <= radius; dz += 2) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    int x = preferred.getX() + dx;
                    int z = preferred.getZ() + dz;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.hasChunkAt(pos)) level.getChunkAt(pos);
                    var ground = level.getBlockState(pos.below());
                    if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()
                            || !(ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT)
                            || ground.is(Blocks.COARSE_DIRT) || ground.is(Blocks.PODZOL))
                            || households.sharedStorageAssigned(dimension, pos)
                            || lives.privateStorageAssigned(pos, dimension)
                            || logistics.warehouseAt(dimension, pos) != null) continue;
                    level.setBlock(pos.below(), Blocks.COBBLESTONE.defaultBlockState(), 3);
                    level.setBlock(pos, Blocks.BARREL.defaultBlockState()
                            .setValue(BarrelBlock.FACING, Direction.UP), 3);
                    return WarehouseContainers.asContainer(level.getBlockEntity(pos)) == null ? null : pos;
                }
            }
        }
        return null;
    }
}
