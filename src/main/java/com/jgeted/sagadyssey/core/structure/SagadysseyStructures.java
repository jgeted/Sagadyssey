package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.Sagadyssey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 结构注册中心。
 * 加载所有 JSON 配置，注册 StructureType + StructurePieceType，
 * 并将结构元数据存入内存供生成时查询。
 */
public final class SagadysseyStructures {
    private static final Logger LOGGER = LoggerFactory.getLogger(Sagadyssey.MOD_ID);

    // === StructureType 注册 ===
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPE_REGISTRY =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, Sagadyssey.MOD_ID);

    public static final DeferredRegister<StructurePieceType> PIECE_TYPE_REGISTRY =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, Sagadyssey.MOD_ID);

    // === 自定义类型 ===
    public static final Supplier<StructureType<SagadysseyStructure>> SAGADYSSEY_STRUCTURE_TYPE = STRUCTURE_TYPE_REGISTRY.register(
            "sagadyssey_structure",
            () -> () -> SagadysseyStructure.CODEC);

    public static final Supplier<StructurePieceType> STRUCTURE_PIECE_TYPE = PIECE_TYPE_REGISTRY.register(
            "sagadyssey_piece",
            () -> (StructurePieceType.ContextlessType) SagadysseyStructurePiece::new);

    // === 已加载的结构配置（元数据） ===
    private static final Map<String, StructureConfig> loadedConfigs = new HashMap<>();
    private static boolean initialized = false;

    private SagadysseyStructures() {}

    /**
     * 加载 JSON 配置并缓存元数据。
     * 在 Sagadyssey 构造器中调用。
     */
    public static void init() {
        if (initialized) return;
        initialized = true;

        List<StructureConfig> configs = StructureConfigLoader.loadAll();
        LOGGER.info("加载了 {} 个结构配置，开始缓存元数据...", configs.size());

        for (StructureConfig config : configs) {
            loadedConfigs.put(config.getId(), config);
            LOGGER.info("  已缓存结构元数据: {} ({})", config.getId(), config.getName());
        }

        LOGGER.info("结构元数据缓存完成，共 {} 个", configs.size());
    }

    /**
     * 获取已加载的结构配置。
     */
    public static StructureConfig getConfig(String id) {
        return loadedConfigs.get(id);
    }

    /**
     * 获取所有已加载的结构 ID。
     */
    public static List<String> getAllIds() {
        return new ArrayList<>(loadedConfigs.keySet());
    }
}
