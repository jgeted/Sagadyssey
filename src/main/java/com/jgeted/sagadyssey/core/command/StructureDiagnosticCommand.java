package com.jgeted.sagadyssey.core.command;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.SagadysseyStructures;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

import java.util.List;
import java.util.Optional;

/**
 * 结构系统诊断命令。
 * /saga structures dump — 转储所有结构注册表内容并测试 NBT 模板加载。
 */
public class StructureDiagnosticCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("saga")
                .then(Commands.literal("structures")
                    .then(Commands.literal("dump")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> dump(ctx.getSource()))
                    )
                )
        );
    }

    private static int dump(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("§6===== 结构系统诊断 ====="), false);

        dumpStructureRegistry(source);
        dumpStructureSetRegistry(source);
        dumpTemplatePoolRegistry(source);
        testNbtLoading(source);
        testBiomeAtPlayer(source);

        source.sendSuccess(() -> Component.literal("§6===== 诊断完成 ====="), false);
        return 1;
    }

    private static void dumpStructureRegistry(CommandSourceStack source) {
        Registry<Structure> registry = source.getServer().registryAccess()
                .registryOrThrow(Registries.STRUCTURE);

        List<ResourceLocation> allKeys = registry.keySet().stream()
                .filter(k -> k.getNamespace().equals(Sagadyssey.MOD_ID))
                .sorted()
                .toList();

        source.sendSuccess(() -> Component.literal(
                "§e[STRUCTURE] 共 " + registry.size() + " 个（sagadyssey: " + allKeys.size() + " 个）"), false);

        for (ResourceLocation key : allKeys) {
            Structure s = registry.get(key);
            if (s == null) {
                source.sendSuccess(() -> Component.literal(
                        "  §c" + key + " → NULL（注册表返回 null！）"), false);
            } else {
                String typeName = s.type() != null
                        ? s.type().toString()
                        : "null";
                source.sendSuccess(() -> Component.literal(
                        "  §a" + key + " §7→ type=" + typeName
                        + " class=" + s.getClass().getSimpleName()), false);
            }
        }

        if (allKeys.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "  §c没有找到 sagadyssey 命名空间的结构！"), false);

            // 列出所有已注册结构供参考
            List<ResourceLocation> all = registry.keySet().stream().sorted().limit(10).toList();
            source.sendSuccess(() -> Component.literal(
                    "  §7所有结构（前10个）: " + all), false);
        }
    }

    private static void dumpStructureSetRegistry(CommandSourceStack source) {
        Registry<StructureSet> registry = source.getServer().registryAccess()
                .registryOrThrow(Registries.STRUCTURE_SET);

        List<ResourceLocation> allKeys = registry.keySet().stream()
                .filter(k -> k.getNamespace().equals(Sagadyssey.MOD_ID))
                .sorted()
                .toList();

        source.sendSuccess(() -> Component.literal(
                "§e[STRUCTURE_SET] 共 " + registry.size() + " 个（sagadyssey: " + allKeys.size() + " 个）"), false);

        for (ResourceLocation key : allKeys) {
            StructureSet ss = registry.get(key);
            if (ss == null) {
                source.sendSuccess(() -> Component.literal("  §c" + key + " → NULL"), false);
            } else {
                String placementType = ss.placement() != null
                        ? ss.placement().getClass().getSimpleName()
                        : "null";
                int structCount = ss.structures().size();
                source.sendSuccess(() -> Component.literal(
                        "  §a" + key + " §7→ 包含 " + structCount + " 个结构, placement=" + placementType), false);

                for (var entry : ss.structures()) {
                    String structKey = entry.structure().unwrapKey()
                            .map(k -> k.location().toString())
                            .orElse("unknown");
                    source.sendSuccess(() -> Component.literal(
                            "    - " + structKey + " (weight=" + entry.weight() + ")"), false);
                }
            }
        }

        if (allKeys.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "  §c没有找到 sagadyssey 命名空间的结构集！"), false);
        }
    }

    private static void dumpTemplatePoolRegistry(CommandSourceStack source) {
        Registry<StructureTemplatePool> registry = source.getServer().registryAccess()
                .registryOrThrow(Registries.TEMPLATE_POOL);

        List<ResourceLocation> allKeys = registry.keySet().stream()
                .filter(k -> k.getNamespace().equals(Sagadyssey.MOD_ID))
                .sorted()
                .toList();

        source.sendSuccess(() -> Component.literal(
                "§e[TEMPLATE_POOL] 共 " + registry.size() + " 个（sagadyssey: " + allKeys.size() + " 个）"), false);

        for (ResourceLocation key : allKeys) {
            StructureTemplatePool pool = registry.get(key);
            if (pool == null) {
                source.sendSuccess(() -> Component.literal("  §c" + key + " → NULL"), false);
            } else {
                int elemCount = pool.size();
                source.sendSuccess(() -> Component.literal(
                        "  §a" + key + " §7→ " + elemCount + " 个元素, fallback="
                        + pool.getFallback()), false);
            }
        }

        if (allKeys.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "  §c没有找到 sagadyssey 命名空间的模板池！"), false);
        }
    }

    private static void testNbtLoading(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("§e[NBT 加载测试]"), false);

        ServerLevel level = source.getLevel();
        var manager = level.getStructureManager();

        for (String id : SagadysseyStructures.getAllIds()) {
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, id);
            Optional<?> template = manager.get(loc);

            if (template.isPresent()) {
                source.sendSuccess(() -> Component.literal(
                        "  §a" + id + " → 加载成功 ✓"), false);
            } else {
                source.sendSuccess(() -> Component.literal(
                        "  §c" + id + " → 加载失败！模板未找到"), false);
            }
        }
    }

    /**
     * 测试玩家当前位置的生物群系是否匹配各结构的 biome 要求，
     * 并复现 generatePositions() 中的 possibleBiomes().anyMatch() 检查。
     */
    private static void testBiomeAtPlayer(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("§e[生物群系匹配测试]"), false);

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendSuccess(() -> Component.literal("  §c无法获取玩家位置（可能需要玩家执行此命令）"), false);
            return;
        }

        BlockPos pos = player.blockPosition();
        ServerLevel level = source.getLevel();

        // 获取玩家脚下的生物群系
        Holder<Biome> biomeHolder = level.getBiome(pos);
        String biomeKey = biomeHolder.unwrapKey()
                .map(k -> k.location().toString())
                .orElse("unknown");
        source.sendSuccess(() -> Component.literal(
                "  §7玩家位置: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()), false);
        source.sendSuccess(() -> Component.literal(
                "  §7当前生物群系: §b" + biomeKey), false);

        // ===== 复现 generatePositions() 的 possibleBiomes 检查 =====
        source.sendSuccess(() -> Component.literal("§e[generatePositions 检查复现]"), false);
        java.util.Set<Holder<Biome>> possibleBiomes = level.getChunkSource()
                .getGenerator().getBiomeSource().possibleBiomes();
        source.sendSuccess(() -> Component.literal(
                "  §7possibleBiomes 共 " + possibleBiomes.size() + " 个 biome"), false);

        // 列出 possibleBiomes 中的所有 biome（最多 15 个）
        List<String> possibleBiomeNames = possibleBiomes.stream()
                .map(h -> h.unwrapKey().map(k -> k.location().toString()).orElse("?"))
                .sorted()
                .limit(15)
                .toList();
        source.sendSuccess(() -> Component.literal(
                "  §7包含: " + String.join(", ", possibleBiomeNames)), false);

        // 对每个结构检查
        Registry<Structure> registry = source.getServer().registryAccess()
                .registryOrThrow(Registries.STRUCTURE);

        List<ResourceLocation> ourKeys = registry.keySet().stream()
                .filter(k -> k.getNamespace().equals(Sagadyssey.MOD_ID))
                .sorted()
                .toList();

        for (ResourceLocation key : ourKeys) {
            Structure s = registry.get(key);
            if (s == null) continue;

            // 1. 玩家位置 biome 检查（HolderSet.contains）
            boolean biomeOk = s.biomes().contains(biomeHolder);

            // 2. generatePositions 检查：anyMatch(possibleBiomes::contains)
            boolean genPosMatch = s.biomes().stream().anyMatch(possibleBiomes::contains);

            // 逐个 biome 检查
            List<String> detailLines = new java.util.ArrayList<>();
            for (Holder<Biome> h : s.biomes().stream().limit(6).toList()) {
                String name = h.unwrapKey().map(k -> k.location().toString()).orElse("?");
                boolean inPossible = possibleBiomes.contains(h);
                boolean isCurrent = h.equals(biomeHolder);
                String marker = inPossible ? "§a✓" : "§c✗";
                String extra = isCurrent ? " (你在这里)" : "";
                detailLines.add(name + " → " + marker + extra);
            }

            String status;
            if (genPosMatch) {
                status = "§aPASS";
            } else {
                status = "§cFAIL §7(原因: 无biome在possibleBiomes中)";
            }

            final String statusFinal = status;
            source.sendSuccess(() -> Component.literal(
                    "  " + statusFinal + " §6" + key.getPath()), false);
            for (String detail : detailLines) {
                source.sendSuccess(() -> Component.literal("      " + detail), false);
            }
        }

        // ===== StructureSet 引用完整性测试 =====
        testStructureSetIntegrity(source, possibleBiomes);
    }

    /**
     * 测试 StructureSet 中的 structure 引用是否完整：
     * 1. structure().value() 是否返回有效对象
     * 2. 与注册表中的 Structure 是否是同一实例
     * 3. biome 数据是否一致
     * 4. possibleStructureSets() 是否包含我们的 set
     * 5. 直接调用 getPlacementsForStructure 验证
     */
    private static void testStructureSetIntegrity(CommandSourceStack source,
                                                   java.util.Set<Holder<Biome>> possibleBiomes) {
        source.sendSuccess(() -> Component.literal("§e[StructureSet 引用完整性]"), false);

        Registry<Structure> structReg = source.getServer().registryAccess()
                .registryOrThrow(Registries.STRUCTURE);
        Registry<StructureSet> setReg = source.getServer().registryAccess()
                .registryOrThrow(Registries.STRUCTURE_SET);

        ServerLevel level = source.getLevel();
        var genState = level.getChunkSource().getGeneratorState();

        // 检查 possibleStructureSets 中是否有 sagadyssey 的 set
        source.sendSuccess(() -> Component.literal("§e[possibleStructureSets 检查]"), false);
        int totalSetCount = setReg.keySet().size();
        int possibleCount = genState.possibleStructureSets().size();
        source.sendSuccess(() -> Component.literal(
                "  §7注册表共 " + totalSetCount + " 个 StructureSet，possibleStructureSets 仅 " + possibleCount + " 个"), false);
        java.util.Set<String> possibleSetNames = new java.util.HashSet<>();
        java.util.Set<String> allSetNames = new java.util.HashSet<>();
        for (var holder : genState.possibleStructureSets()) {
            holder.unwrapKey().ifPresent(k -> possibleSetNames.add(k.location().toString()));
        }
        for (ResourceLocation k : setReg.keySet()) {
            allSetNames.add(k.toString());
        }
        java.util.Set<String> filteredOut = new java.util.HashSet<>(allSetNames);
        filteredOut.removeAll(possibleSetNames);
        // 只显示 sagadyssey 和部分 minecraft 被过滤的
        int shown = 0;
        for (String name : filteredOut.stream().sorted().toList()) {
            if (name.contains("sagadyssey") || shown < 5) {
                source.sendSuccess(() -> Component.literal(
                        "  §c被过滤掉: " + name), false);
                shown++;
            }
        }
        if (!filteredOut.isEmpty() && filteredOut.size() > shown) {
            final int more = filteredOut.size() - shown;
            source.sendSuccess(() -> Component.literal(
                    "  §7... 另外 " + more + " 个也被过滤"), false);
        }
        if (possibleSetNames.stream().noneMatch(n -> n.contains("sagadyssey"))) {
            source.sendSuccess(() -> Component.literal(
                    "  §c所有 sagadyssey 的 structure_set 都被过滤掉了！"), false);
        }

        // 直接测试 getPlacementsForStructure
        source.sendSuccess(() -> Component.literal("§e[getPlacementsForStructure 直接调用]"), false);

        List<ResourceLocation> ourKeys = structReg.keySet().stream()
                .filter(k -> k.getNamespace().equals(Sagadyssey.MOD_ID))
                .sorted()
                .toList();

        for (ResourceLocation key : ourKeys) {
            Structure structFromReg = structReg.get(key);
            if (structFromReg == null) continue;

            StructureSet structureSet = setReg.get(key);
            if (structureSet == null) continue;

            boolean foundSelf = false;
            for (var entry : structureSet.structures()) {
                var structHolder = entry.structure();
                Structure structFromSet = structHolder.value();
                String setKey = structHolder.unwrapKey()
                        .map(k -> k.location().toString())
                        .orElse("???");

                if (!setKey.equals(key.toString())) continue;
                foundSelf = true;

                boolean sameInstance = (structFromReg == structFromSet);
                int regBiomeCount = structFromReg.biomes().size();
                int setBiomeCount = structFromSet != null ? structFromSet.biomes().size() : -1;
                boolean hasBiomes = structFromSet != null
                        && structFromSet.biomes().stream().anyMatch(possibleBiomes::contains);

                // 直接调用 getPlacementsForStructure 使用两个不同的来源
                // 来源 1：注册表 Holder
                var regHolder = structReg.wrapAsHolder(structFromReg);
                java.util.List<?> fromReg = genState.getPlacementsForStructure(
                        (net.minecraft.core.Holder<Structure>) regHolder);
                boolean fromRegOk = !fromReg.isEmpty();

                // 来源 2：StructureSet 的 structure() Holder
                java.util.List<?> fromSet = genState.getPlacementsForStructure(
                        (net.minecraft.core.Holder<Structure>) structHolder);
                boolean fromSetOk = !fromSet.isEmpty();

                String sameStr = sameInstance ? "§a同一实例" : "§c不同实例!";
                String regCall = fromRegOk ? ("§a有(" + fromReg.size() + "个placement)") : "§c空!";
                String setCall = fromSetOk ? ("§a有(" + fromSet.size() + "个placement)") : "§c空!";

                source.sendSuccess(() -> Component.literal(
                        "  §6" + key.getPath()), false);
                source.sendSuccess(() -> Component.literal(
                        "    " + sameStr + " biome=" + regBiomeCount + " hasBiomes="
                        + (hasBiomes ? "§aPASS" : "§cFAIL")), false);
                source.sendSuccess(() -> Component.literal(
                        "    getPlacements(regHolder)=" + regCall
                        + " getPlacements(setHolder)=" + setCall), false);
            }

            if (!foundSelf) {
                source.sendSuccess(() -> Component.literal(
                        "  §c" + key.getPath() + " §7→ 在自己的StructureSet中未找到自己！"), false);
            }
        }
    }
}
