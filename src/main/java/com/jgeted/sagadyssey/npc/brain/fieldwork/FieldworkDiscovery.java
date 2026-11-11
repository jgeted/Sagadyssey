package com.jgeted.sagadyssey.npc.brain.fieldwork;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.entity.FarmMode;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** 职业发现规则。只读目标，不把一次采矿扩成整树/整片叶子的世界修改。 */
public final class FieldworkDiscovery {
    private FieldworkDiscovery() { }

    public static boolean producer(NpcBase npc) {
        return npc.getProfession() == NpcProfession.FARMER || npc.getProfession() == NpcProfession.WORKER;
    }

    public static FieldworkSearchArea area(NpcBase npc) {
        BlockPos min = npc.hasWorkZone() ? npc.getWorkZoneMin() : npc.blockPosition().offset(-64, -4, -64);
        BlockPos max = npc.hasWorkZone() ? npc.getWorkZoneMax() : npc.blockPosition().offset(64, 12, 64);
        // 农田是地面列：标记脚下区域时，包含脚下土壤及其上四格作物。
        if (npc.getProfession() == NpcProfession.FARMER) {
            min = min.below();
            max = max.above(4);
        }
        int bottom = Math.max(npc.level().getMinY(), min.getY());
        int top = Math.min(npc.level().getMaxY(), max.getY());
        return new FieldworkSearchArea(npc.level().dimension().identifier().toString(),
                new BlockPos(min.getX(), bottom, min.getZ()), new BlockPos(max.getX(), top, max.getZ()));
    }

    public static boolean allowed(NpcBase npc, BlockPos pos) {
        if (!com.jgeted.sagadyssey.social.growth.SettlementGrowthData.workAllowed(npc,pos)) return false;
        if (!com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData.cultivationAllowed(npc,pos)) return false;
        if (com.jgeted.sagadyssey.social.growth.SettlementCoordinatorData.resourceAllowed(npc,pos)) return true;
        if (!npc.hasWorkZone()) return true;
        if (npc.getProfession() != NpcProfession.FARMER) return npc.isWorkAllowedAt(pos);
        BlockPos min = npc.getWorkZoneMin(), max = npc.getWorkZoneMax();
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ()
                && pos.getY() >= min.getY() - 1 && pos.getY() <= max.getY() + 4;
    }

    public static FieldworkTarget inspect(NpcBase npc, BlockPos pos) {
        if (!npc.level().hasChunkAt(pos) || !allowed(npc, pos)) return null;
        BlockState state = npc.level().getBlockState(pos);
        if (npc.getProfession() == NpcProfession.WORKER) {
            if(!com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.productionTarget(npc,state))return null;
            if(!com.jgeted.sagadyssey.social.growth.SettlementResourceStock.wants(npc,state))return null;
            if (state.is(BlockTags.LOGS)) {
                // 逐块从顶向下，防止先拆根导致高处作业不可达。
                if (npc.level().getBlockState(pos.above()).is(BlockTags.LOGS) && allowed(npc, pos.above())) return null;
                return change(pos, FieldworkAction.Operation.CHOP, state, Blocks.AIR.defaultBlockState(), null);
            }
            if (mineTarget(state) || state.is(BlockTags.LEAVES)) {
                if (state.is(BlockTags.LEAVES) && !nearLog(npc, pos)) return null;
                return change(pos, state.is(BlockTags.LEAVES) ? FieldworkAction.Operation.CHOP : FieldworkAction.Operation.MINE,
                        state, Blocks.AIR.defaultBlockState(), null);
            }
            if (state.is(com.jgeted.sagadyssey.social.growth.SettlementMaterials.COVER)
                    && npc.level().getBlockState(pos.above()).isAir()) {
                for (int depth = 1; depth <= 5; depth++) {
                    if (allowed(npc, pos.below(depth)) && mineTarget(npc.level().getBlockState(pos.below(depth)))
                            && com.jgeted.sagadyssey.social.growth.SettlementResourceStock.wants(npc,npc.level().getBlockState(pos.below(depth)))) {
                        return change(pos, FieldworkAction.Operation.MINE, state, Blocks.AIR.defaultBlockState(), null);
                    }
                }
            }
            return null;
        }
        if (npc.getProfession() != NpcProfession.FARMER) return null;
        // Natural decorations can be placed after the structure. Clear only the approved new crop cells,
        // through the ordinary harvest receipt/drop path; the observer never edits the soil or plants.
        if(com.jgeted.sagadyssey.social.growth.SettlementGrowthData.extraField(npc,pos)
                &&(state.is(Blocks.SHORT_GRASS)||state.is(Blocks.FERN)||state.is(BlockTags.SMALL_FLOWERS)))
            return change(pos,FieldworkAction.Operation.HARVEST,state,Blocks.AIR.defaultBlockState(),null);
        FarmMode mode = npc.getFarmMode();
        boolean crop = mode == FarmMode.CROP || mode == FarmMode.AUTO;
        boolean melon = mode == FarmMode.MELON || mode == FarmMode.AUTO;
        boolean cane = mode == FarmMode.CANE || mode == FarmMode.AUTO;
        boolean cocoa = mode == FarmMode.COCOA || mode == FarmMode.AUTO;
        // 小水井分成独立单格作业：两格对角水源消耗两桶，另两格只开挖；
        // 填水由原版流体规则产生，不能在一个不可恢复循环里修改四格。
        FieldworkTarget irrigation = irrigation(npc, pos, state, mode);
        if (irrigation != null) return irrigation;
        if(crop&&state.getBlock() instanceof CropBlock mature&&mature.isMaxAge(state)
                &&com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.farmer(npc)
                &&!com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.harvest(npc))return null;
        if (crop && state.getBlock() instanceof CropBlock block && block.isMaxAge(state)
                || melon && (state.is(Blocks.MELON) || state.is(Blocks.PUMPKIN))
                || cane && state.is(Blocks.SUGAR_CANE) && npc.level().getBlockState(pos.below()).is(Blocks.SUGAR_CANE)
                && !npc.level().getBlockState(pos.above()).is(Blocks.SUGAR_CANE)
                || cocoa && state.is(Blocks.COCOA) && state.getValue(CocoaBlock.AGE) == 2) {
            return change(pos, FieldworkAction.Operation.HARVEST, state, Blocks.AIR.defaultBlockState(), null);
        }
        BlockState fertilized = null;
        int growth = 2 + npc.getRandom().nextInt(4);
        if (crop && state.getBlock() instanceof CropBlock block && !block.isMaxAge(state)) {
            int increase = state.is(Blocks.BEETROOTS) ? Math.max(1, growth / 3) : growth;
            fertilized = block.getStateForAge(Math.min(block.getMaxAge(), block.getAge(state) + increase));
        } else if (melon && state.getBlock() instanceof StemBlock && state.getValue(StemBlock.AGE) < 7) {
            fertilized = state.setValue(StemBlock.AGE, Math.min(7, state.getValue(StemBlock.AGE) + growth));
        } else if (cocoa && state.is(Blocks.COCOA) && state.getValue(CocoaBlock.AGE) < 2) {
            fertilized = state.setValue(CocoaBlock.AGE, state.getValue(CocoaBlock.AGE) + 1);
        }
        if (fertilized != null) {
            if (has(npc, Items.BONE_MEAL)) return change(pos, FieldworkAction.Operation.FERTILIZE, state, fertilized, Items.BONE_MEAL);
            for (Item material : List.of(Items.BONE_BLOCK, Items.BONE)) if (has(npc, material)) {
                return new FieldworkTarget(pos, FieldworkAction.Operation.CRAFT_FERTILIZER, state, state, material, Items.BONE_MEAL);
            }
        }
        if ((crop || melon) && (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK))
                && npc.level().getBlockState(pos.above()).isAir() && waterNearby(npc, pos)
                && (!wellCell(npc, pos, mode)||com.jgeted.sagadyssey.social.growth.SettlementGrowthData.extraField(npc,pos)) && !unfinishedWell(npc, pos, mode)) {
            return change(pos, FieldworkAction.Operation.TILL, state, Blocks.FARMLAND.defaultBlockState(), null);
        }
        if (state.isAir() && npc.level().getBlockState(pos.below()).is(Blocks.FARMLAND)) {
            // 瓜茎旁留一个果实空位；不把每个空格都补成作物。
            if (nearStem(npc, pos)) return null;
            List<Item> seeds = mode == FarmMode.MELON ? List.of(Items.MELON_SEEDS, Items.PUMPKIN_SEEDS)
                    : crop ? List.of(Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS,
                    Items.MELON_SEEDS, Items.PUMPKIN_SEEDS) : List.of();
            var natural=com.jgeted.sagadyssey.core.structure.NaturalFarmRules.forResident(npc);
            if(crop&&natural!=null&&com.jgeted.sagadyssey.social.growth.GrowthLayout.supports(natural))seeds=List.of(Items.CARROT);
            for (Item seed : seeds) {
                if (!has(npc, seed)) continue;
                if (!melon && (seed == Items.MELON_SEEDS || seed == Items.PUMPKIN_SEEDS)) continue;
                BlockState planted = seedBlock(seed).defaultBlockState();
                if (planted.canSurvive(npc.level(), pos)) return change(pos, FieldworkAction.Operation.PLANT, state, planted, seed);
            }
        }
        if (cane && state.isAir() && !npc.level().getBlockState(pos.below()).is(Blocks.SUGAR_CANE)
                && Blocks.SUGAR_CANE.defaultBlockState().canSurvive(npc.level(), pos) && has(npc, Items.SUGAR_CANE)) {
            return change(pos, FieldworkAction.Operation.PLANT, state, Blocks.SUGAR_CANE.defaultBlockState(), Items.SUGAR_CANE);
        }
        if (cocoa && state.isAir() && has(npc, Items.COCOA_BEANS)) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockState pod = Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, direction);
                if (pod.canSurvive(npc.level(), pos)) return change(pos, FieldworkAction.Operation.PLANT, state, pod, Items.COCOA_BEANS);
            }
            BlockPos anchor = npc.hasWorkZone() ? npc.getWorkZoneMin() : BlockPos.ZERO;
            if (mode == FarmMode.COCOA && Math.floorMod(pos.getX() - anchor.getX(), 3) == 0
                    && Math.floorMod(pos.getZ() - anchor.getZ(), 3) == 0
                    && (!npc.hasWorkZone() || pos.getY() <= anchor.getY() + 1)
                    && npc.level().getBlockState(pos.below()).isFaceSturdy(npc.level(), pos.below(), Direction.UP)) {
                for (Item log : List.of(Items.JUNGLE_LOG, Items.STRIPPED_JUNGLE_LOG, Items.JUNGLE_WOOD, Items.STRIPPED_JUNGLE_WOOD)) {
                    if (has(npc, log)) return change(pos, FieldworkAction.Operation.PLANT, state,
                            ((BlockItem) log).getBlock().defaultBlockState(), log);
                }
            }
        }
        if ((crop || melon || cane) && state.is(Blocks.WATER) && state.getFluidState().isSource()
                && has(npc, Items.BUCKET) && sourceNeighbours(npc, pos) >= 2) {
            return new FieldworkTarget(pos, FieldworkAction.Operation.REFILL_WATER, state, state, Items.BUCKET, Items.WATER_BUCKET);
        }
        return null;
    }

    private static boolean wellCell(NpcBase npc, BlockPos pos, FarmMode mode) {
        if (mode == FarmMode.COCOA || !npc.hasWorkZone()) return false;
        BlockPos min = npc.getWorkZoneMin();
        int spacing = mode == FarmMode.CANE ? 5 : 9, center = spacing / 2;
        int x = Math.floorMod(pos.getX() - min.getX(), spacing), z = Math.floorMod(pos.getZ() - min.getZ(), spacing);
        return pos.getY() == min.getY() - 1 && (x == center || x == center + 1) && (z == center || z == center + 1);
    }
    private static FieldworkTarget irrigation(NpcBase npc, BlockPos pos, BlockState state, FarmMode mode) {
        if (!wellCell(npc, pos, mode) || !npc.level().getBlockState(pos.above()).isAir()
                || !npc.level().getBlockState(pos.below()).isFaceSturdy(npc.level(), pos.below(), Direction.UP)) return null;
        if (!state.isAir() && !state.is(Blocks.DIRT) && !state.is(Blocks.GRASS_BLOCK) && !state.is(Blocks.FARMLAND)) return null;
        int spacing = mode == FarmMode.CANE ? 5 : 9, center = spacing / 2;
        int x = Math.floorMod(pos.getX() - npc.getWorkZoneMin().getX(), spacing) - center;
        int z = Math.floorMod(pos.getZ() - npc.getWorkZoneMin().getZ(), spacing) - center;
        if (x == z && has(npc, Items.WATER_BUCKET)) return new FieldworkTarget(pos, FieldworkAction.Operation.IRRIGATE,
                state, Blocks.WATER.defaultBlockState(), Items.WATER_BUCKET, Items.BUCKET);
        BlockPos corner = pos.offset(-x, 0, -z);
        if (x != z && !state.isAir() && npc.level().getFluidState(corner).isSource()
                && npc.level().getFluidState(corner.south().east()).isSource()) {
            return change(pos, FieldworkAction.Operation.IRRIGATE, state, Blocks.AIR.defaultBlockState(), null);
        }
        return null;
    }
    private static boolean unfinishedWell(NpcBase npc, BlockPos pos, FarmMode mode) {
        if (!npc.hasWorkZone() || mode == FarmMode.COCOA || (!has(npc, Items.BUCKET) && !has(npc, Items.WATER_BUCKET))) return false;
        BlockPos min = npc.getWorkZoneMin(); int spacing = mode == FarmMode.CANE ? 5 : 9;
        BlockPos corner = new BlockPos(min.getX() + Math.floorDiv(pos.getX() - min.getX(), spacing) * spacing + spacing / 2,
                min.getY() - 1, min.getZ() + Math.floorDiv(pos.getZ() - min.getZ(), spacing) * spacing + spacing / 2);
        if (!allowed(npc, corner) || !allowed(npc, corner.south().east())) return false;
        if (!npc.level().getFluidState(corner).is(net.minecraft.tags.FluidTags.WATER)
                && !npc.level().getFluidState(corner.south().east()).is(net.minecraft.tags.FluidTags.WATER)) return false;
        for (BlockPos cell : List.of(corner, corner.east(), corner.south(), corner.east().south())) {
            if (!npc.level().getFluidState(cell).isSource()) return true;
        }
        return false;
    }

    public static boolean mineTarget(BlockState state) {
        return com.jgeted.sagadyssey.social.growth.SettlementMaterials.mineable(state);
    }

    public static Block seedBlock(Item item) {
        if (item == Items.WHEAT_SEEDS) return Blocks.WHEAT;
        if (item == Items.CARROT) return Blocks.CARROTS;
        if (item == Items.POTATO) return Blocks.POTATOES;
        if (item == Items.BEETROOT_SEEDS) return Blocks.BEETROOTS;
        if (item == Items.MELON_SEEDS) return Blocks.MELON_STEM;
        if (item == Items.PUMPKIN_SEEDS) return Blocks.PUMPKIN_STEM;
        throw new IllegalArgumentException("not a planting input");
    }

    public static boolean has(NpcBase npc, Item item) {
        return npc.getPersonalInventory().contains(stack -> stack.is(item));
    }

    private static FieldworkTarget change(BlockPos pos, FieldworkAction.Operation operation,
                                          BlockState before, BlockState after, Item input) {
        return new FieldworkTarget(pos, operation, before, after, input, null);
    }

    private static boolean waterNearby(NpcBase npc, BlockPos pos) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))) {
            if (npc.level().hasChunkAt(p) && npc.level().getFluidState(p).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    private static int sourceNeighbours(NpcBase npc, BlockPos pos) {
        int sources = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            var fluid = npc.level().getFluidState(pos.relative(d));
            if (fluid.is(FluidTags.WATER) && fluid.isSource()) sources++;
        }
        return sources;
    }

    private static boolean nearStem(NpcBase npc, BlockPos pos) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState state = npc.level().getBlockState(pos.relative(d));
            if (state.getBlock() instanceof StemBlock || state.getBlock() instanceof AttachedStemBlock) return true;
        }
        return false;
    }

    private static boolean nearLog(NpcBase npc, BlockPos pos) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) {
            if (npc.level().hasChunkAt(p) && npc.level().getBlockState(p).is(BlockTags.LOGS)) return true;
        }
        // 树干已按单格砍完后，不能把尚未清理的同树树叶遗忘。只使用本 NPC 的已知作业记录。
        for (var receipt : FieldworkData.get(MinecraftApi.server(npc)).receiptsFor(npc.getUUID())) {
            if (receipt.action().operation() == FieldworkAction.Operation.CHOP
                    && receipt.action().dimension().equals(npc.level().dimension().identifier().toString())
                    && receipt.action().target().distSqr(pos) <= 12D
                    && (receipt.action().expectedState().contains("_log\"") || receipt.action().expectedState().contains("_wood\""))) return true;
        }
        return false;
    }
}
