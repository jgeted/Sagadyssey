package com.jgeted.sagadyssey.mixin;

import com.jgeted.sagadyssey.core.structure.NaturalVillageBuilding;
import com.jgeted.sagadyssey.core.structure.NaturalVillageRegistration;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureStart.class)
public class StructureStartPlacementMixin implements NaturalVillageRegistration.Eligibility {
    @Unique private boolean sagadyssey$registrationEligible = true;

    @Override
    public void sagadyssey$setRegistrationEligible(boolean eligible) {
        sagadyssey$registrationEligible = eligible;
    }

    @Inject(method = "loadStaticStart", at = @At("RETURN"))
    private static void sagadyssey$readEligibility(StructurePieceSerializationContext context,
                                                  CompoundTag tag, long seed,
                                                  CallbackInfoReturnable<StructureStart> cir) {
        if (cir.getReturnValue() != null && cir.getReturnValue().isValid()) {
            ((NaturalVillageRegistration.Eligibility) (Object) cir.getReturnValue())
                    .sagadyssey$setRegistrationEligible(tag.getInt(NaturalVillageRegistration.ELIGIBILITY_TAG) == 1);
        }
    }

    @Inject(method = "createTag", at = @At("RETURN"))
    private void sagadyssey$saveEligibility(StructurePieceSerializationContext context, ChunkPos chunkPos,
                                           CallbackInfoReturnable<CompoundTag> cir) {
        var start = (StructureStart) (Object) this;
        var id = context.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(start.getStructure());
        if (start.isValid() && sagadyssey$registrationEligible && NaturalVillageBuilding.SUPPORTED.equals(id)) {
            cir.getReturnValue().putInt(NaturalVillageRegistration.ELIGIBILITY_TAG, 1);
        }
    }

    @WrapOperation(method = "placeInChunk", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/levelgen/structure/StructurePiece;postProcess(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/core/BlockPos;)V"))
    private void sagadyssey$placeActualPiece(StructurePiece piece, WorldGenLevel level,
                                            StructureManager manager, ChunkGenerator generator,
                                            RandomSource random, BoundingBox box, ChunkPos chunk,
                                            BlockPos pivot, Operation<Void> original) {
        Runnable place = () -> original.call(piece, level, manager, generator, random, box, chunk, pivot);
        if (!sagadyssey$registrationEligible) { place.run(); return; }
        NaturalVillageRegistration.duringPiece((StructureStart) (Object) this, piece, level, place);
    }
}
