package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.Sagadyssey;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePieceAccessor;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 单个 NBT 结构片段。
 * 在 postProcess 阶段从资源加载 NBT 模板并放置到世界中。
 */
public class SagadysseyStructurePiece extends StructurePiece {
    private static final Logger LOGGER = LoggerFactory.getLogger(Sagadyssey.MOD_ID);

    private final ResourceLocation templateLocation;
    private final Rotation rotation;

    public SagadysseyStructurePiece(ResourceLocation templateLocation, BlockPos pos, Rotation rotation) {
        super(SagadysseyStructures.STRUCTURE_PIECE_TYPE.get(), 0, makeBoundingBox(templateLocation, pos, rotation));
        this.templateLocation = templateLocation;
        this.rotation = rotation;
    }

    /** NBT 反序列化构造器 */
    public SagadysseyStructurePiece(CompoundTag tag) {
        super(SagadysseyStructures.STRUCTURE_PIECE_TYPE.get(), tag);
        this.templateLocation = ResourceLocation.parse(tag.getString("Template"));
        this.rotation = Rotation.valueOf(tag.getString("Rot"));
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext ctx, CompoundTag tag) {
        tag.putString("Template", templateLocation.toString());
        tag.putString("Rot", rotation.name());
    }

    @Override
    public void postProcess(
            WorldGenLevel level,
            StructureManager structureManager,
            ChunkGenerator generator,
            RandomSource random,
            BoundingBox box,
            ChunkPos chunkPos,
            BlockPos pivot) {

        StructureTemplateManager templateManager = level.getLevel().getStructureManager();
        Optional<StructureTemplate> optTemplate = templateManager.get(templateLocation);

        if (optTemplate.isEmpty()) {
            LOGGER.warn("找不到结构模板: {}", templateLocation);
            return;
        }

        StructureTemplate template = optTemplate.get();
        BlockPos placePos = new BlockPos(box.minX(), box.minY(), box.minZ());

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .setBoundingBox(box);

        template.placeInWorld(level, placePos, placePos, settings, random, 2);

        LOGGER.debug("已放置结构: {} at {}", templateLocation, placePos);
    }

    private static BoundingBox makeBoundingBox(ResourceLocation templateLocation, BlockPos origin, Rotation rotation) {
        // 先用占位尺寸，实际放置时模板会自动适配
        int size = 32;
        return new BoundingBox(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + size, origin.getY() + size, origin.getZ() + size);
    }
}
