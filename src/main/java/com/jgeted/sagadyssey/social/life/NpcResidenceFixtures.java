package com.jgeted.sagadyssey.social.life;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;

/** 为固定测试世界补齐真正可使用的私人床和私人储物桶。 */
public final class NpcResidenceFixtures {
    private static final int HOUSE_SIZE = 5;

    private NpcResidenceFixtures() { }

    public record Result(String name, @Nullable BlockPos origin,
                         @Nullable BlockPos bed, @Nullable BlockPos storage) { }

    public static Result ensurePrivateRoom(ServerLevel level, BlockPos settlementCenter,
                                           String settlementName, int residentIndex,
                                           NpcLifeData lifeData) {
        int[][] ring = {
                {28, 28}, {36, 28}, {44, 28}, {52, 28},
                {28, 36}, {36, 36}, {44, 36}, {52, 36},
                {-32, 30}, {-40, 30}, {-48, 30}, {-56, 30},
                {30, -32}, {38, -32}, {46, -32}, {54, -32},
                {-32, -32}, {-40, -32}, {-48, -32}, {-56, -32}
        };
        int[] offset = ring[Math.floorMod(residentIndex, ring.length)];
        BlockPos desired = settlementCenter.offset(offset[0], 0, offset[1]);
        BlockPos origin = findClearSite(level, desired);
        if (origin == null) return new Result(settlementName + "住宅待修", null, null, null);

        buildRoom(level, origin);
        // POI 与 LivingEntity.startSleeping 都应使用床头。使用床尾会让整个模型
        // 沿床轴偏移一格，正是录像中身体伸出床外的原因。
        BlockPos bed = origin.offset(1, 0, 2).north();
        BlockPos storage = origin.offset(3, 0, 1);
        String dimension = level.dimension().location().toString();
        if (lifeData.bedAssigned(bed, dimension) || lifeData.privateStorageAssigned(storage, dimension)) {
            return new Result(settlementName + "住宅登记冲突", origin, null, null);
        }
        return new Result(settlementName + "私宅 " + (residentIndex + 1), origin, bed, storage);
    }

    @Nullable
    private static BlockPos findClearSite(ServerLevel level, BlockPos desired) {
        for (int radius = 0; radius <= 24; radius += 4) {
            for (int dx = -radius; dx <= radius; dx += 4) {
                for (int dz = -radius; dz <= radius; dz += 4) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    int x = desired.getX() + dx;
                    int z = desired.getZ() + dz;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos origin = new BlockPos(x, y, z);
                    if (clear(level, origin)) return origin;
                }
            }
        }
        return null;
    }

    private static boolean clear(ServerLevel level, BlockPos origin) {
        int floorY = origin.getY() - 1;
        for (int x = 0; x < HOUSE_SIZE; x++) {
            for (int z = 0; z < HOUSE_SIZE; z++) {
                BlockPos floor = new BlockPos(origin.getX() + x, floorY, origin.getZ() + z);
                if (!level.hasChunkAt(floor)) level.getChunkAt(floor);
                if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) return false;
                for (int y = 0; y <= 4; y++) {
                    BlockPos air = floor.above(y + 1);
                    if (!level.getBlockState(air).isAir()) return false;
                }
            }
        }
        return true;
    }

    private static void buildRoom(ServerLevel level, BlockPos origin) {
        for (int x = 0; x < HOUSE_SIZE; x++) {
            for (int z = 0; z < HOUSE_SIZE; z++) {
                level.setBlock(origin.offset(x, -1, z), Blocks.COBBLESTONE.defaultBlockState(), 2);
                boolean wall = x == 0 || x == HOUSE_SIZE - 1 || z == 0 || z == HOUSE_SIZE - 1;
                boolean doorway = z == HOUSE_SIZE - 1 && x == 2;
                for (int y = 0; y <= 2; y++) {
                    level.setBlock(origin.offset(x, y, z),
                            wall && !(doorway && y <= 1)
                                    ? Blocks.SPRUCE_PLANKS.defaultBlockState()
                                    : Blocks.AIR.defaultBlockState(), 2);
                }
                level.setBlock(origin.offset(x, 3, z), Blocks.SPRUCE_SLAB.defaultBlockState(), 2);
            }
        }

        BlockPos foot = origin.offset(1, 0, 2);
        BlockPos head = foot.north();
        level.setBlock(foot, Blocks.WHITE_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.NORTH)
                .setValue(BedBlock.PART, BedPart.FOOT), 3);
        level.setBlock(head, Blocks.WHITE_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.NORTH)
                .setValue(BedBlock.PART, BedPart.HEAD), 3);
        BlockPos door = origin.offset(2, 0, HOUSE_SIZE - 1);
        placeDoor(level, door);
        level.setBlock(origin.offset(3, 0, 1), Blocks.BARREL.defaultBlockState()
                .setValue(BarrelBlock.FACING, Direction.UP), 3);
        level.setBlock(origin.offset(3, 1, 3), Blocks.TORCH.defaultBlockState(), 3);
    }

    /** 兼容旧场景把床尾写进生活档案的记录。 */
    @Nullable
    public static BlockPos normalizeBedHead(ServerLevel level, @Nullable BlockPos position) {
        if (position == null) return null;
        var state = level.getBlockState(position);
        if (!(state.getBlock() instanceof BedBlock)) return position;
        if (state.getValue(BedBlock.PART) == BedPart.HEAD) return position;
        BlockPos head = position.relative(state.getValue(BedBlock.FACING));
        return level.getBlockState(head).is(net.minecraft.tags.BlockTags.BEDS) ? head : position;
    }

    /**
     * r7 早期生成的私人房间只预留了门洞。repair 时按床头和私箱的固定布局识别旧房，
     * 在不触碰其他建筑的前提下补上真正可由 NPC 打开的门。
     */
    public static boolean ensureExistingRoomAccess(ServerLevel level, BlockPos bedHead,
                                                   @Nullable BlockPos storage) {
        BlockPos origin = bedHead.offset(-1, 0, -1);
        if (storage == null || !storage.equals(origin.offset(3, 0, 1))) return false;
        BlockPos door = origin.offset(2, 0, HOUSE_SIZE - 1);
        var lower = level.getBlockState(door);
        if (lower.getBlock() instanceof DoorBlock
                && lower.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) return true;
        if (!lower.isAir() || !level.getBlockState(door.above()).isAir()) return false;
        placeDoor(level, door);
        return true;
    }

    private static void placeDoor(ServerLevel level, BlockPos door) {
        level.setBlock(door, Blocks.SPRUCE_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.SOUTH)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(door.above(), Blocks.SPRUCE_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.SOUTH)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
    }
}
