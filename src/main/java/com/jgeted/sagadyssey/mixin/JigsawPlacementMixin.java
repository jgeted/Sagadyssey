package com.jgeted.sagadyssey.mixin;

import com.jgeted.sagadyssey.Sagadyssey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Mixin 注入到 JigsawPlacement.addPieces()，记录每一步的调用和返回。
 */
@Mixin(net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement.class)
public class JigsawPlacementMixin {

    @Unique
    private static final String TAG = "[STRUCTURE-DEBUG]";

    /**
     * 在 addPieces 入口记录调用参数。
     */
    @Inject(
        method = "addPieces",
        at = @At("HEAD")
    )
    private static void onAddPiecesEntry(
            Structure.GenerationContext context,
            Holder<StructureTemplatePool> startPool,
            Optional<ResourceLocation> startJigsawName,
            int maxDepth,
            BlockPos pos,
            boolean useExpansionHack,
            Optional<Heightmap.Types> projectStartToHeightmap,
            int maxDistanceFromCenter,
            PoolAliasLookup poolAliasLookup,
            DimensionPadding dimensionPadding,
            LiquidSettings liquidSettings,
            CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir
    ) {
        String poolName = startPool.unwrapKey()
                .map(k -> k.location().toString())
                .orElse("inline");
        String heightmapName = projectStartToHeightmap
                .map(Heightmap.Types::getSerializationKey)
                .orElse("none");
        Sagadyssey.LOGGER.info("{} addPieces 入口: pool={} pos={} depth={} heightmap={} maxDist={} jigsawName={} dimPad={}",
                TAG, poolName, pos, maxDepth, heightmapName, maxDistanceFromCenter,
                startJigsawName.map(ResourceLocation::toString).orElse("none"),
                dimensionPadding);
    }

    /**
     * 在 addPieces 返回时记录结果。
     */
    @Inject(
        method = "addPieces",
        at = @At("RETURN")
    )
    private static void onAddPiecesReturn(
            Structure.GenerationContext context,
            Holder<StructureTemplatePool> startPool,
            Optional<ResourceLocation> startJigsawName,
            int maxDepth,
            BlockPos pos,
            boolean useExpansionHack,
            Optional<Heightmap.Types> projectStartToHeightmap,
            int maxDistanceFromCenter,
            PoolAliasLookup poolAliasLookup,
            DimensionPadding dimensionPadding,
            LiquidSettings liquidSettings,
            CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir
    ) {
        String poolName = startPool.unwrapKey()
                .map(k -> k.location().toString())
                .orElse("inline");
        Optional<Structure.GenerationStub> result = cir.getReturnValue();
        if (result.isEmpty()) {
            Sagadyssey.LOGGER.warn("{} addPieces 返回 EMPTY! pool={} pos={} depth={}",
                    TAG, poolName, pos, maxDepth);
        } else {
            Structure.GenerationStub stub = result.get();
            Sagadyssey.LOGGER.info("{} addPieces 成功: pool={} position={}",
                    TAG, poolName, stub.position());
        }
    }
}
