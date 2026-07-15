package com.jgeted.sagadyssey.mixin;

import com.jgeted.sagadyssey.Sagadyssey;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Mixin 注入到 ChunkGeneratorStructureState：
 * 1. 修复超平坦世界过滤问题——强制 sagadyssey 结构集通过 hasBiomesForStructureSet
 * 2. 记录 getPlacementsForStructure 的调用结果用于调试
 */
@Mixin(net.minecraft.world.level.chunk.ChunkGeneratorStructureState.class)
public class ChunkGeneratorStructureStateMixin {

    @Unique
    private static final String TAG = "[STRUCTURE-DEBUG]";

    /**
     * 修复：hasBiomesForStructureSet 对 sagadyssey 命名空间的结构集永远返回 true。
     * 超平坦世界的 possibleBiomes 只有一个 biome（如 plains），
     * 但 createForFlat 调用时 Holder 可能还未完全解析，导致 biome 匹配失败。
     * 此注入确保我们的结构集无论如何都能进入 possibleStructureSets。
     */
    @Inject(
        method = "hasBiomesForStructureSet",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void onHasBiomesCheck(
            StructureSet structureSet,
            BiomeSource biomeSource,
            CallbackInfoReturnable<Boolean> cir
    ) {
        // 检查 structureSet 是否包含 sagadyssey 的结构
        for (var entry : structureSet.structures()) {
            String namespace = entry.structure().unwrapKey()
                    .map(k -> k.location().getNamespace())
                    .orElse("");
            if (namespace.equals(Sagadyssey.MOD_ID)) {
                cir.setReturnValue(true);
                return;
            }
        }
    }

    /**
     * 在 getPlacementsForStructure 返回时记录结果。
     * 如果返回空列表，说明结构在 generatePositions() 的 possibleBiomes 检查中被过滤掉了。
     */
    @Inject(
        method = "getPlacementsForStructure",
        at = @At("RETURN")
    )
    private void onGetPlacementsReturn(
            Holder<Structure> structureHolder,
            CallbackInfoReturnable<List<StructurePlacement>> cir
    ) {
        List<StructurePlacement> placements = cir.getReturnValue();
        String structName = structureHolder.unwrapKey()
                .map(k -> k.location().toString())
                .orElse("unknown");

        if (placements.isEmpty()) {
            Sagadyssey.LOGGER.warn("{} getPlacementsForStructure 返回空列表! struct={} → 此结构无placement，/locate将找不到",
                    TAG, structName);
        } else {
            Sagadyssey.LOGGER.info("{} getPlacementsForStructure: struct={} placements={}",
                    TAG, structName, placements.size());
        }
    }
}
