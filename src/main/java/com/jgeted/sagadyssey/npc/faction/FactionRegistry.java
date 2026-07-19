package com.jgeted.sagadyssey.npc.faction;

import com.jgeted.sagadyssey.Sagadyssey;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

import java.util.Collection;
import java.util.Optional;

/**
 * 阵营注册中心。
 * <p>
 * 使用 NeoForge 1.21 的 DataPackRegistry 方案——
 * 所有阵营（内置 7 个 + 自定义）统一从数据包 JSON 加载。
 * <p>
 * 阵营始终通过 {@link RegistryAccess} 动态获取：
 * <ul>
 *   <li>服务端命令：{@code level.registryAccess()}</li>
 *   <li>客户端 GUI：{@code Minecraft.getInstance().level.registryAccess()}</li>
 *   <li>内部便利：{@link FactionRegistry#get(String)} 使用全局缓存</li>
 * </ul>
 * <p>
 * 全局缓存在 {@link Level} 存在时填充，避免反复查 registryAccess。
 */
public final class FactionRegistry {

    /** Registry 的 ResourceKey */
    public static final ResourceKey<Registry<Faction>> KEY =
            ResourceKey.createRegistryKey(
                    ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, "faction")
            );

    /** 全局缓存（任何 Level 的 registryAccess 均可填充，首次命中后生效） */
    private static volatile Registry<Faction> cachedRegistry;

    private FactionRegistry() {}

    /** 注册 DataPackRegistry（mod bus） */
    @SubscribeEvent
    public static void registerDataPackRegistry(DataPackRegistryEvent.NewRegistry event) {
        event.dataPackRegistry(KEY, Faction.CODEC, Faction.CODEC);
        Sagadyssey.LOGGER.info("阵营 DataPackRegistry 已注册: sagadyssey:faction");
    }

    /**
     * 从 RegistryAccess 获取阵营 Registry。
     * 同时更新全局缓存（如有数据）。
     */
    public static Registry<Faction> fromRegistryAccess(RegistryAccess access) {
        Registry<Faction> reg = access.registryOrThrow(KEY);
        if (reg.size() > 0) {
            cachedRegistry = reg;
        }
        return reg;
    }

    /** 从 Level 获取阵营 Registry */
    public static Registry<Faction> fromLevel(Level level) {
        return fromRegistryAccess(level.registryAccess());
    }

    /**
     * 确保缓存已填充——命令等入口调用此方法，
     * 传入 {@code source.registryAccess()} 或类似源。
     * 仅在缓存未命中时有开销。
     */
    public static void ensureCache(RegistryAccess access) {
        if (cachedRegistry == null || cachedRegistry.size() == 0) {
            fromRegistryAccess(access);
        }
    }

    /**
     * 从任意有效的 RegistryAccess 源获取。
     * 优先用缓存，否则从 mc.level 拿。
     */
    private static Registry<Faction> getActiveRegistry() {
        if (cachedRegistry != null && cachedRegistry.size() > 0) {
            return cachedRegistry;
        }
        // 客户端：从当前 level 获取
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                Registry<Faction> reg = fromRegistryAccess(mc.level.registryAccess());
                if (reg.size() > 0) return reg;
            }
        } catch (Exception ignored) {}
        return cachedRegistry;
    }

    // === 便捷查询 ===

    public static Optional<Faction> getOptional(ResourceLocation id) {
        Registry<Faction> reg = getActiveRegistry();
        if (reg == null) return Optional.empty();
        return reg.getOptional(id);
    }

    public static Faction get(ResourceLocation id) {
        Registry<Faction> reg = getActiveRegistry();
        if (reg == null) return null;
        return reg.get(id);
    }

    /**
     * 按字符串 ID 获取阵营。
     * 不带命名空间的 ID 自动补全为 {@code sagadyssey:} 命名空间。
     */
    public static Faction get(String id) {
        Registry<Faction> reg = getActiveRegistry();
        if (reg == null) return null;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        // 无命名空间时默认补全为 sagadyssey:
        if (rl.getNamespace().equals("minecraft") && !id.contains(":")) {
            rl = ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, rl.getPath());
        }
        return reg.get(rl);
    }

    public static Collection<Faction> getAllFactions() {
        Registry<Faction> reg = getActiveRegistry();
        if (reg == null) return java.util.List.of();
        return reg.stream().toList();
    }

    public static Registry<Faction> getRegistry() {
        return getActiveRegistry();
    }

    /** 获取 Registry 大小（用于日志） */
    public static int size() {
        Registry<Faction> reg = getActiveRegistry();
        return reg == null ? 0 : reg.size();
    }
}
