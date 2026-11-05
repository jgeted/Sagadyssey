package com.jgeted.sagadyssey.mixin;

import com.jgeted.sagadyssey.core.structure.NaturalVillageRegistration;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PoolElementStructurePiece.class)
public class PoolElementPlacementMixin {
    @WrapOperation(method = "place", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/levelgen/structure/pools/StructurePoolElement;place(Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/structure/templatesystem/LiquidSettings;Z)Z"))
    private boolean sagadyssey$confirmPlacement(StructurePoolElement element, StructureTemplateManager templates,
                                                WorldGenLevel level, StructureManager manager,
                                                ChunkGenerator generator, BlockPos origin, BlockPos pivot,
                                                Rotation rotation, BoundingBox box, RandomSource random,
                                                LiquidSettings liquids, boolean keepJigsaws,
                                                Operation<Boolean> original) {
        boolean success = original.call(element, templates, level, manager, generator, origin, pivot,
                rotation, box, random, liquids, keepJigsaws);
        NaturalVillageRegistration.placed((PoolElementStructurePiece) (Object) this, level, box, success);
        return success;
    }
}
