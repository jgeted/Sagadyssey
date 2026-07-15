package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.Sagadyssey;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

import java.util.Optional;

/**
 * NBT 模板结构。
 * 通过 structureId 关联 StructureConfig，在 findGenerationPoint 中确定位置并生成片段。
 */
public class SagadysseyStructure extends Structure {

    public static final MapCodec<SagadysseyStructure> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    settingsCodec(instance),
                    Codec.STRING.fieldOf("structure_id").forGetter(s -> s.structureId)
            ).apply(instance, SagadysseyStructure::new)
    );

    private final String structureId;

    public SagadysseyStructure(StructureSettings settings, String structureId) {
        super(settings);
        this.structureId = structureId;
    }

    public String getStructureId() {
        return structureId;
    }

    private StructureConfig config() {
        return SagadysseyStructures.getConfig(structureId);
    }

    private ResourceLocation nbtLocation() {
        StructureConfig cfg = config();
        if (cfg != null) {
            return ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, cfg.getNbtFileName());
        }
        return ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, structureId);
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        return onTopOfChunkCenter(context, Heightmap.Types.WORLD_SURFACE_WG, builder -> {
            generatePieces(builder, context);
        });
    }

    private void generatePieces(StructurePiecesBuilder builder, GenerationContext context) {
        Rotation rotation = Rotation.getRandom(context.random());
        int y = context.chunkGenerator().getFirstOccupiedHeight(
                context.chunkPos().getMiddleBlockX(),
                context.chunkPos().getMiddleBlockZ(),
                Heightmap.Types.WORLD_SURFACE_WG,
                context.heightAccessor(),
                context.randomState());
        BlockPos pos = new BlockPos(context.chunkPos().getMiddleBlockX(), y, context.chunkPos().getMiddleBlockZ());
        builder.addPiece(new SagadysseyStructurePiece(nbtLocation(), pos, rotation));
    }

    @Override
    public StructureType<?> type() {
        return SagadysseyStructures.SAGADYSSEY_STRUCTURE_TYPE.get();
    }
}
