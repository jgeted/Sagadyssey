package com.jgeted.sagadyssey.mixin;

import com.jgeted.sagadyssey.Sagadyssey;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Mixin 注入到 Structure.findValidGenerationPoint()，记录 biome 验证步骤。
 */
@Mixin(Structure.class)
public class StructureMixin {

    @Unique
    private static final String TAG = "[STRUCTURE-DEBUG]";

    /**
     * 在 findValidGenerationPoint 入口，记录调用参数。
     */
    @Inject(
        method = "findValidGenerationPoint",
        at = @At("HEAD")
    )
    private void onFindValidEntry(
            Structure.GenerationContext context,
            CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir
    ) {
        Sagadyssey.LOGGER.info("{} findValidGenerationPoint 入口: struct={} chunkPos={}",
                TAG, this.getClass().getSimpleName(), context.chunkPos());
    }

    /**
     * 在 findValidGenerationPoint 返回时记录结果。
     */
    @Inject(
        method = "findValidGenerationPoint",
        at = @At("RETURN")
    )
    private void onFindValidReturn(
            Structure.GenerationContext context,
            CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir
    ) {
        Optional<Structure.GenerationStub> result = cir.getReturnValue();
        if (result.isEmpty()) {
            Sagadyssey.LOGGER.warn("{} findValidGenerationPoint 返回 EMPTY! struct={} chunkPos={}",
                    TAG, this.getClass().getSimpleName(), context.chunkPos());
        } else {
            Sagadyssey.LOGGER.info("{} findValidGenerationPoint 成功: struct={} position={}",
                    TAG, this.getClass().getSimpleName(), result.get().position());
        }
    }
}
