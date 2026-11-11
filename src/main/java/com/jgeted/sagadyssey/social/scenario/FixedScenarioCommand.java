package com.jgeted.sagadyssey.social.scenario;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.work.NpcWorkstationContainers;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcLifeRecord;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.LogisticsOrderStatus;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.social.logistics.WarehouseRecord;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import org.joml.Vector3f;

import java.util.UUID;

/** `/saga scenario` 固定世界部署入口。生成和取消仅允许管理员执行。 */
public final class FixedScenarioCommand {
    private FixedScenarioCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("saga").then(Commands.literal("scenario")
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("preview")
                        .then(Commands.argument("scenario", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestScenarios(builder))
                                .executes(ctx -> preview(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "scenario")))))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("observe").requires(source -> MinecraftApi.hasPermission(source, 2))
                        .executes(ctx -> observe(ctx.getSource())))
                .then(Commands.literal("test")
                        .then(Commands.literal("profiles")
                                .executes(ctx -> testProfiles(ctx.getSource())))
                        .then(Commands.literal("reset").requires(source -> MinecraftApi.hasPermission(source, 2))
                                .executes(ctx -> resetLab(ctx.getSource(), null))
                                .then(Commands.argument("profile", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestProfiles(ctx.getSource(), builder))
                                        .executes(ctx -> resetLab(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "profile"))))))
                .then(Commands.literal("verify").requires(source -> MinecraftApi.hasPermission(source, 2))
                        .executes(ctx -> verify(ctx.getSource())))
                .then(Commands.literal("generate").requires(source -> MinecraftApi.hasPermission(source, 2))
                        .then(Commands.argument("scenario", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestScenarios(builder))
                                .executes(ctx -> generate(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "scenario"), false))
                                .then(Commands.literal("force")
                                        .executes(ctx -> generate(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "scenario"), true)))))
                .then(Commands.literal("repair").requires(source -> MinecraftApi.hasPermission(source, 2))
                        .then(Commands.literal("confirm")
                                .executes(ctx -> repair(ctx.getSource()))))
                .then(Commands.literal("reclaim-drops").requires(source -> MinecraftApi.hasPermission(source, 2))
                        .then(Commands.literal("preview")
                                .executes(ctx -> reclaimDrops(ctx.getSource(), false)))
                        .then(Commands.literal("confirm")
                                .executes(ctx -> reclaimDrops(ctx.getSource(), true))))
                .then(Commands.literal("cancel").requires(source -> MinecraftApi.hasPermission(source, 2))
                        .executes(ctx -> cancel(ctx.getSource())))));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
    suggestScenarios(com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        FixedScenarioDefinition.all().forEach(definition -> builder.suggest(definition.id()));
        return builder.buildFuture();
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
    suggestProfiles(CommandSourceStack source,
                    com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        FixedScenarioDeployment deployment = nearestDeployment(source, true, true);
        if (deployment != null) ScenarioLabController.profiles(deployment.scenarioId())
                .forEach(builder::suggest);
        return builder.buildFuture();
    }

    private static int list(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("§6可用固定世界："), false);
        for (FixedScenarioDefinition definition : FixedScenarioDefinition.all()) {
            source.sendSuccess(() -> Component.literal("§e" + definition.id() + " §f- "
                    + definition.displayName() + " §7r" + definition.revision()
                    + "，半径=" + definition.radius() + "，建筑="
                    + definition.placements().size() + "，农田=" + definition.farms().size()
                    + "，居民=" + definition.npcs().size()), false);
            source.sendSuccess(() -> Component.literal("  §7" + definition.description()), false);
        }
        return FixedScenarioDefinition.all().size();
    }

    private static int preview(CommandSourceStack source, String scenarioId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        FixedScenarioDefinition definition = FixedScenarioDefinition.get(scenarioId);
        if (definition == null) {
            source.sendFailure(Component.literal("未知固定世界：" + scenarioId));
            return 0;
        }
        BlockPos anchor = deploymentAnchor(player.level(), player.blockPosition());
        drawPreview(player, anchor, definition);
        source.sendSuccess(() -> Component.literal("§a已显示 " + definition.displayName()
                + " 的边界和聚落中心；锚点=" + anchor.toShortString()), false);
        source.sendSuccess(() -> Component.literal("§e生成会覆盖道路、农田和建筑占用位置。确认后使用：/saga scenario generate "
                + definition.id()), false);
        return 1;
    }

    private static int generate(CommandSourceStack source, String scenarioId, boolean force)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        FixedScenarioDefinition definition = FixedScenarioDefinition.get(scenarioId);
        if (definition == null) {
            source.sendFailure(Component.literal("未知固定世界：" + scenarioId));
            return 0;
        }
        ServerLevel level = player.level();
        String dimension = level.dimension().identifier().toString();
        BlockPos anchor = deploymentAnchor(level, player.blockPosition());
        if (!(level.getChunkSource().getGenerator() instanceof FlatLevelSource) && !force) {
            source.sendFailure(Component.literal("该场景按超平坦世界设计；普通世界必须明确使用 force。"));
            return 0;
        }
        FixedScenarioData data = FixedScenarioData.get(source.getServer());
        if (data.activeIn(dimension) != null) {
            source.sendFailure(Component.literal("该维度已有固定世界正在生成，请先查看 status 或 cancel。"));
            return 0;
        }
        if (!force && overlapsExisting(data, dimension, anchor, definition.radius())) {
            source.sendFailure(Component.literal("该区域与已有固定世界重叠；如确实要覆盖，请在命令末尾加 force。"));
            return 0;
        }
        if (!force && !placementAreasClear(level, anchor, definition)) {
            source.sendFailure(Component.literal("场景地形、建筑或仓库区域检测到玩家方块。请换位置，或明确使用 force 覆盖。"));
            return 0;
        }
        FixedScenarioDeployment deployment = data.start(definition.id(), dimension, anchor,
                player.getUUID(), level.getGameTime(), force);
        if (deployment == null) {
            source.sendFailure(Component.literal("无法建立部署任务；同一锚点可能已经生成过。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§a已开始分批生成 " + definition.displayName()
                + "。任务=" + deployment.id() + "，锚点=" + anchor.toShortString()), true);
        source.sendSuccess(() -> Component.literal("§7生成可跨服务器重启继续；使用 /saga scenario status 查看进度。"), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        String dimension = source.getLevel().dimension().identifier().toString();
        var deployments = FixedScenarioData.get(source.getServer()).deployments().stream()
                .filter(deployment -> deployment.dimension().equals(dimension)).limit(8).toList();
        if (deployments.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7当前维度没有固定世界部署记录。"), false);
            return 0;
        }
        for (FixedScenarioDeployment deployment : deployments) {
            FixedScenarioDefinition definition = FixedScenarioDefinition.get(deployment.scenarioId());
            int percent = definition == null ? 0 : progressPercent(deployment, definition);
            source.sendSuccess(() -> Component.literal("§e" + deployment.scenarioId() + " §7"
                    + (deployment.repair() ? "REPAIR/" : "") + deployment.phase().name() + " " + percent
                    + "% · r" + deployment.definitionRevision()
                    + (definition != null && deployment.definitionRevision() < definition.revision()
                            ? "§c→r" + definition.revision() + "§7" : "")
                    + " · " + deployment.anchor().toShortString()
                    + (deployment.error().isBlank() ? "" : " · " + deployment.error())), false);
        }
        return deployments.size();
    }

    private static int verify(CommandSourceStack source) {
        FixedScenarioDeployment deployment = nearestDeployment(source, false, true);
        if (deployment == null) {
            source.sendFailure(Component.literal("当前维度没有可验收的固定世界。"));
            return 0;
        }
        FixedScenarioDefinition definition = FixedScenarioDefinition.get(deployment.scenarioId());
        if (definition == null) {
            source.sendFailure(Component.literal("固定世界定义已经不存在：" + deployment.scenarioId()));
            return 0;
        }
        FixedScenarioVerifier.Result result = FixedScenarioVerifier.verify(source.getServer(),
                source.getLevel(), deployment, definition);
        if (result.passed()) {
            source.sendSuccess(() -> Component.literal("§a固定世界验收通过：" + result.checks()
                    + " 项检查无结构性错误。"), false);
        } else {
            source.sendFailure(Component.literal("固定世界发现 " + result.issues().size()
                    + " 个问题（最多显示 30 个）："));
            result.issues().stream().limit(30).forEach(issue ->
                    source.sendFailure(Component.literal(" - " + issue)));
        }
        if (!result.warnings().isEmpty()) {
            source.sendSuccess(() -> Component.literal("§e另有 " + result.warnings().size()
                    + " 项无法在线核对（通常是 NPC 所在区块未加载）："), false);
            result.warnings().stream().limit(10).forEach(warning ->
                    source.sendSuccess(() -> Component.literal(" §7- " + warning), false));
        }
        return result.passed() ? 1 : 0;
    }

    /** 汇总离玩家最近场景的 NPC、私人设施、工作站、仓库与在途订单。 */
    private static int observe(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String dimension = player.level().dimension().identifier().toString();
        FixedScenarioDeployment deployment = nearestDeployment(source, false, true);
        if (deployment == null) {
            source.sendFailure(Component.literal("当前维度附近没有已完成的固定场景。"));
            return 0;
        }
        FixedScenarioDefinition definition = FixedScenarioDefinition.get(deployment.scenarioId());
        if (definition == null) {
            source.sendFailure(Component.literal("固定场景定义已经不存在。"));
            return 0;
        }

        ServerLevel level = player.level();
        LogisticsData logistics = LogisticsData.get(source.getServer());
        String profile = deployment.testProfile().isBlank() ? "-" : deployment.testProfile();
        source.sendSuccess(() -> Component.literal("§6[场景观察] §f" + definition.displayName()
                + " §7锚点=" + deployment.anchor().toShortString() + " · 测试档=" + profile), false);

        java.util.Set<java.util.UUID> warehouseIds = new java.util.HashSet<>();
        int warehouseIndex = 0;
        for (FixedScenarioDefinition.WarehouseSpec spec : definition.warehouses()) {
            BlockPos position = deployment.anchor().offset(spec.x(), 0, spec.z());
            WarehouseRecord warehouse = logistics.warehouseAt(dimension, position);
            if (warehouse != null) warehouseIds.add(warehouse.id());
            describeWarehouse(source, level, spec.name(), position, warehouse);
            Vector3f color = (warehouseIndex++ & 1) == 0
                    ? new Vector3f(1.0F, 0.3F, 0.12F) : new Vector3f(0.2F, 1.0F, 0.35F);
            markPoint(player, position, color, 8);
        }

        NpcLifeData lives = NpcLifeData.get(source.getServer());
        for (int index = 0; index < definition.npcs().size(); index++) {
            FixedScenarioDefinition.NpcSpec spec = definition.npcs().get(index);
            UUID npcId = FixedScenarioData.npcId(deployment, index);
            if (!(level.getEntity(npcId) instanceof NpcBase npc)) {
                source.sendSuccess(() -> Component.literal("§cNPC未加载 §f" + spec.name()
                        + " §7UUID=" + npcId), false);
                continue;
            }
            NpcLifeRecord life = lives.record(npcId);
            String residence = life == null ? "生活档案缺失" : "床="
                    + shortPos(life.bedPosition()) + " 私箱=" + shortPos(life.privateStoragePosition());
            String detail = npc.getWorkActivityDetail().isBlank() ? "" : " · " + npc.getWorkActivityDetail();
            String cargo = npc.getTransportSlotLimit() <= 0 ? "" : " · 运输货舱="
                    + inventorySummary(npc.getTransportInventory(), 6);
            source.sendSuccess(() -> Component.literal("§bNPC §f" + npc.getNpcName() + " §7["
                    + npc.getProfession().getDisplayName() + "/" + npc.getCommand().name() + "] 坐标="
                    + npc.blockPosition().toShortString() + " 生命=" + Math.round(npc.getHealth()) + "/"
                    + Math.round(npc.getMaxHealth()) + " 士气=" + npc.getMoral()
                    + " 睡眠=" + (npc.isSleeping() ? "是" : "否")
                    + " 导航=" + (npc.getNavigation().isInProgress() ? "进行中" : "停止")), false);
            source.sendSuccess(() -> Component.literal("  §7动作=" + npc.getWorkActivity() + detail
                    + " · 背包=" + inventorySummary(npc.getEquipmentInventory(), 6)
                    + cargo + " · " + residence), false);
            markPoint(player, npc.blockPosition(), new Vector3f(0.25F, 0.75F, 1.0F), 4);
        }
        if ("guard_lab".equals(definition.id()) && "combat".equals(deployment.testProfile())) {
            var hostile = level.getEntity(ScenarioLabController.trainingHostileId(deployment));
            source.sendSuccess(() -> Component.literal(hostile == null
                    ? "§e训练敌怪已死亡或未加载"
                    : "§4训练敌怪 §f" + hostile.getName().getString() + " §7坐标="
                    + hostile.blockPosition().toShortString()), false);
            if (hostile != null) markPoint(player, hostile.blockPosition(),
                    new Vector3f(1.0F, 0.1F, 0.1F), 4);
        }

        for (FixedScenarioDefinition.TerrainBlock block : definition.terrain()) {
            if (block.kind() != FixedScenarioDefinition.TerrainKind.FURNACE
                    && block.kind() != FixedScenarioDefinition.TerrainKind.BLAST_FURNACE) continue;
            BlockPos position = deployment.anchor().offset(block.x(), block.y(), block.z());
            Container furnace = NpcWorkstationContainers.asFurnace(level.getBlockEntity(position));
            source.sendSuccess(() -> Component.literal("§d工作站 §f" + block.kind().name()
                    + " §7位置=" + position.toShortString() + " · "
                    + (furnace == null ? "容器未加载" : "输入=" + stackSummary(furnace.getItem(0))
                    + " 燃料=" + stackSummary(furnace.getItem(1))
                    + " 输出=" + stackSummary(furnace.getItem(2)))), false);
            markPoint(player, position, new Vector3f(0.85F, 0.25F, 1.0F), 6);
        }

        var orders = logistics.orders().stream()
                .filter(order -> order.status() == LogisticsOrderStatus.PLANNED
                        || order.status() == LogisticsOrderStatus.IN_PROGRESS)
                .filter(order -> warehouseIds.contains(order.sourceWarehouseId())
                        || warehouseIds.contains(order.destinationWarehouseId()))
                .limit(12).toList();
        if (!orders.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§b未完成物流订单=" + orders.size()), false);
            for (var order : orders) {
                WarehouseRecord from = logistics.warehouse(order.sourceWarehouseId());
                WarehouseRecord to = logistics.warehouse(order.destinationWarehouseId());
                String carrier = "等待领取";
                if (order.assignedNpcId() != null && level.getEntity(order.assignedNpcId()) instanceof NpcBase npc) {
                    carrier = npc.getNpcName();
                } else if (order.assignedNpcId() != null) {
                    carrier = order.assignedNpcId().toString();
                }
                String finalCarrier = carrier;
                source.sendSuccess(() -> Component.literal("  §e" + order.itemId() + " §f"
                        + order.deliveredCount() + "/" + order.requestedCount() + " §7"
                        + (from == null ? "?" : from.name()) + " → " + (to == null ? "?" : to.name())
                        + " · 已取货=" + order.pickedUpCount() + " · 在途=" + order.inTransitCount()
                        + " · 执行者=" + finalCarrier), false);
            }
        }
        return 1;
    }

    private static void describeWarehouse(CommandSourceStack source, ServerLevel level, String label,
                                          BlockPos position, WarehouseRecord warehouse) {
        boolean loaded = level.hasChunkAt(position);
        Container container = loaded ? WarehouseContainers.asContainer(level.getBlockEntity(position)) : null;
        if (warehouse == null || container == null) {
            source.sendSuccess(() -> Component.literal("§c仓库 §f" + label + " §7位置=" + position.toShortString()
                    + " §c[未登记、未加载或容器损坏]"), false);
            return;
        }
        source.sendSuccess(() -> Component.literal("§c仓库 §f" + label + " §7位置="
                + position.toShortString() + " · 库存=" + inventorySummary(container, 10)), false);
    }

    private static void markPoint(ServerPlayer player, BlockPos position, Vector3f color, int maxHeight) {
        DustParticleOptions particle = new DustParticleOptions(MinecraftApi.particleColor(color), 1.0F);
        for (int height = 0; height <= maxHeight; height++) {
            player.level().sendParticles(player, particle, true, false,
                    position.getX() + 0.5D, position.getY() + 0.5D + height,
                    position.getZ() + 0.5D, 2, 0.12D, 0.08D, 0.12D, 0.0D);
        }
    }

    private static String inventorySummary(Container container, int limit) {
        java.util.Map<String, Integer> totals = new java.util.TreeMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            totals.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        if (totals.isEmpty()) return "空";
        String joined = totals.entrySet().stream().limit(limit)
                .map(entry -> entry.getKey() + "×" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        return totals.size() > limit ? joined + " …" : joined;
    }

    private static String stackSummary(ItemStack stack) {
        return stack.isEmpty() ? "空" : BuiltInRegistries.ITEM.getKey(stack.getItem()) + "×" + stack.getCount();
    }

    private static String shortPos(BlockPos position) {
        return position == null ? "未分配" : position.toShortString();
    }

    private static int testProfiles(CommandSourceStack source) {
        FixedScenarioDeployment deployment = nearestDeployment(source, true, true);
        if (deployment == null) {
            source.sendFailure(Component.literal("附近没有已完成的职业实验场。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§6[测试档] §f" + deployment.scenarioId()
                + " §7当前=" + (deployment.testProfile().isBlank() ? "未设置" : deployment.testProfile())), false);
        for (String profile : ScenarioLabController.profiles(deployment.scenarioId())) {
            source.sendSuccess(() -> Component.literal(" §e" + profile + " §7- "
                    + ScenarioLabController.profileDescription(deployment.scenarioId(), profile)), false);
        }
        source.sendSuccess(() -> Component.literal("§7切换并重置：/saga scenario test reset <档位>"), false);
        return 1;
    }

    private static int resetLab(CommandSourceStack source, String requestedProfile) {
        FixedScenarioDeployment deployment = nearestDeployment(source, true, true);
        if (deployment == null) {
            source.sendFailure(Component.literal("附近没有已完成的职业实验场。"));
            return 0;
        }
        FixedScenarioDefinition definition = FixedScenarioDefinition.get(deployment.scenarioId());
        if (definition == null) {
            source.sendFailure(Component.literal("实验场定义已经不存在。"));
            return 0;
        }
        String profile = requestedProfile == null
                ? ScenarioLabController.defaultProfile(definition.id()) : requestedProfile;
        ScenarioLabController.ApplyResult result = ScenarioLabController.apply(
                source.getLevel(), deployment, definition, profile);
        if (!result.applied()) {
            if (ScenarioLabController.profiles(definition.id()).contains(profile)) {
                String reason = result.warnings().isEmpty() ? "实验场当前无法安全重置"
                        : result.warnings().getFirst();
                source.sendFailure(Component.literal(reason));
            } else {
                source.sendFailure(Component.literal("未知测试档：" + profile + "；可用："
                        + String.join(", ", ScenarioLabController.profiles(definition.id()))));
            }
            return 0;
        }
        FixedScenarioData.get(source.getServer()).changed();
        source.sendSuccess(() -> Component.literal("§a已重置 " + definition.displayName() + " 为 §e"
                + result.profile() + "§a 档；重置 NPC=" + result.resetNpcs()), true);
        source.sendSuccess(() -> Component.literal("§7目标："
                + ScenarioLabController.profileDescription(definition.id(), result.profile())), false);
        result.warnings().forEach(warning -> source.sendSuccess(
                () -> Component.literal("§e警告：" + warning), false));
        source.sendSuccess(() -> Component.literal("§7建议白天观察；状态：/saga scenario observe"), false);
        return 1;
    }

    private static int repair(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.level();
        String dimension = level.dimension().identifier().toString();
        FixedScenarioData data = FixedScenarioData.get(source.getServer());
        if (data.activeIn(dimension) != null) {
            source.sendFailure(Component.literal("固定世界仍在生成，不能同时修复。请先等待完成或执行 cancel。"));
            return 0;
        }
        FixedScenarioDeployment previous = nearestDeployment(source, false, false);
        if (previous == null) {
            source.sendFailure(Component.literal("当前维度没有固定世界部署记录。"));
            return 0;
        }
        FixedScenarioDefinition definition = FixedScenarioDefinition.get(previous.scenarioId());
        if (definition == null) {
            source.sendFailure(Component.literal("无法修复：场景定义已经不存在。"));
            return 0;
        }
        FixedScenarioDeployment repair = data.start(definition.id(), dimension, previous.anchor(),
                player.getUUID(), level.getGameTime(), true, true);
        if (repair == null) {
            source.sendFailure(Component.literal("无法建立修复任务。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§e已开始修复 " + definition.displayName()
                + "：会重铺场景地形、重放建筑、清理旧结构标记并恢复 NPC 配置。"), true);
        source.sendSuccess(() -> Component.literal("§7已登记且仍存在的仓库库存不会重置；完成后执行 /saga scenario verify。"), false);
        return 1;
    }

    private static int cancel(CommandSourceStack source) {
        String dimension = source.getLevel().dimension().identifier().toString();
        if (!FixedScenarioData.get(source.getServer()).cancel(dimension)) {
            source.sendFailure(Component.literal("当前维度没有正在执行的固定世界任务。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§e已停止任务。已放置的实体方块不会自动删除。"), true);
        return 1;
    }

    /**
     * 旧版本曾把农作物和矿物遗留在地面。预览只统计；确认后仅把白名单物资放入两座测试市场，
     * 玩家装备、工具及其他物品始终不会被触碰。
     */
    private static int reclaimDrops(CommandSourceStack source, boolean confirm)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        FixedScenarioDeployment deployment = FixedScenarioData.get(source.getServer())
                .latestIn(player.level().dimension().identifier().toString());
        if (deployment == null || !"small_realm".equals(deployment.scenarioId())) {
            source.sendFailure(Component.literal("当前维度没有裂冠河谷场景。"));
            return 0;
        }
        FixedScenarioDefinition definition = FixedScenarioDefinition.get(deployment.scenarioId());
        if (definition == null || deployment.phase() != FixedScenarioDeployment.Phase.COMPLETE) {
            source.sendFailure(Component.literal("固定场景尚未完成，不能回收地面物资。"));
            return 0;
        }

        BlockPos anchor = deployment.anchor();
        int radius = definition.radius();
        ServerLevel level = player.level();
        AABB bounds = new AABB(anchor.getX() - radius, level.getMinY(), anchor.getZ() - radius,
                anchor.getX() + radius + 1, (level.getMaxY() + 1), anchor.getZ() + radius + 1);
        var drops = level.getEntitiesOfClass(ItemEntity.class, bounds,
                entity -> entity.isAlive() && reclaimDestination(entity.getItem()) != ReclaimDestination.NONE);
        java.util.Map<String, Integer> totals = new java.util.TreeMap<>();
        for (ItemEntity entity : drops) {
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entity.getItem().getItem())
                    .toString();
            totals.merge(id, entity.getItem().getCount(), Integer::sum);
        }

        if (!confirm) {
            int total = totals.values().stream().mapToInt(Integer::intValue).sum();
            source.sendSuccess(() -> Component.literal("§e[地面物资预览] 实体堆=" + drops.size()
                    + "，物品=" + total + "；这里只统计当前已加载区块。"), false);
            totals.forEach((id, amount) -> source.sendSuccess(
                    () -> Component.literal(" §7- " + id + " × " + amount), false));
            source.sendSuccess(() -> Component.literal("§7确认回收：/saga scenario reclaim-drops confirm"), false);
            return drops.size();
        }

        BlockPos minePos = anchor.offset(160, 0, 30);
        BlockPos grainPos = anchor.offset(205, 0, 30);
        Container mine = level.hasChunkAt(minePos)
                ? WarehouseContainers.asContainer(level.getBlockEntity(minePos)) : null;
        Container grain = level.hasChunkAt(grainPos)
                ? WarehouseContainers.asContainer(level.getBlockEntity(grainPos)) : null;
        int moved = 0;
        int remaining = 0;
        for (ItemEntity entity : drops) {
            Container target = reclaimDestination(entity.getItem()) == ReclaimDestination.FARM ? grain : mine;
            if (target == null) {
                remaining += entity.getItem().getCount();
                continue;
            }
            int inserted = insertInto(target, entity.getItem());
            moved += inserted;
            remaining += entity.getItem().getCount();
            if (entity.getItem().isEmpty()) entity.discard();
        }
        if (mine != null) mine.setChanged();
        if (grain != null) grain.setChanged();
        int finalMoved = moved;
        int finalRemaining = remaining;
        source.sendSuccess(() -> Component.literal("§a地面物资回收完成：入库=" + finalMoved
                + "，因仓库未加载或已满而保留=" + finalRemaining), true);
        return moved;
    }

    private enum ReclaimDestination { NONE, FARM, MINE }

    private static ReclaimDestination reclaimDestination(ItemStack stack) {
        if (stack.is(Items.WHEAT) || stack.is(Items.WHEAT_SEEDS) || stack.is(Items.CARROT)
                || stack.is(Items.POTATO) || stack.is(Items.BEETROOT)
                || stack.is(Items.BEETROOT_SEEDS) || stack.is(Items.MELON_SLICE)
                || stack.is(Items.PUMPKIN) || stack.is(Items.SUGAR_CANE)
                || stack.is(Items.COCOA_BEANS)) return ReclaimDestination.FARM;
        if (stack.is(ItemTags.LOGS) || stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE)
                || stack.is(Items.STONE) || stack.is(Items.COAL) || stack.is(Items.CHARCOAL)
                || stack.is(Items.RAW_IRON) || stack.is(Items.RAW_COPPER) || stack.is(Items.RAW_GOLD)
                || stack.is(Items.DIAMOND) || stack.is(Items.REDSTONE)
                || stack.is(Items.LAPIS_LAZULI) || stack.is(Items.EMERALD)) return ReclaimDestination.MINE;
        return ReclaimDestination.NONE;
    }

    private static int insertInto(Container container, ItemStack stack) {
        int before = stack.getCount();
        for (int slot = 0; slot < container.getContainerSize() && !stack.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) continue;
            int limit = Math.min(container.getMaxStackSize(), existing.getMaxStackSize());
            int amount = Math.min(stack.getCount(), Math.max(0, limit - existing.getCount()));
            if (amount > 0) {
                existing.grow(amount);
                stack.shrink(amount);
            }
        }
        for (int slot = 0; slot < container.getContainerSize() && !stack.isEmpty(); slot++) {
            if (!container.getItem(slot).isEmpty()) continue;
            int amount = Math.min(stack.getCount(), Math.min(container.getMaxStackSize(), stack.getMaxStackSize()));
            ItemStack placed = stack.copy();
            placed.setCount(amount);
            container.setItem(slot, placed);
            stack.shrink(amount);
        }
        return before - stack.getCount();
    }

    private static int progressPercent(FixedScenarioDeployment deployment,
                                       FixedScenarioDefinition definition) {
        int done = switch (deployment.phase()) {
            case SETTLEMENTS -> deployment.cursor();
            case TERRAIN -> definition.settlements().size() + deployment.cursor();
            case STRUCTURES -> definition.settlements().size() + definition.terrain().size() + deployment.cursor();
            case WAREHOUSES -> definition.settlements().size() + definition.terrain().size()
                    + definition.placements().size() + deployment.cursor();
            case NPCS -> definition.settlements().size() + definition.terrain().size()
                    + definition.placements().size() + definition.warehouses().size() + deployment.cursor();
            case FINALIZE -> definition.totalWorkUnits() - 1;
            case COMPLETE -> definition.totalWorkUnits();
            case CANCELLED, FAILED -> 0;
        };
        return Math.max(0, Math.min(100, (int) Math.round(done * 100.0D / definition.totalWorkUnits())));
    }

    private static FixedScenarioDeployment nearestDeployment(CommandSourceStack source,
                                                             boolean labsOnly,
                                                             boolean completeOnly) {
        String dimension = source.getLevel().dimension().identifier().toString();
        BlockPos origin = BlockPos.containing(source.getPosition());
        return FixedScenarioData.get(source.getServer()).deployments().stream()
                .filter(deployment -> deployment.dimension().equals(dimension))
                .filter(deployment -> !labsOnly || ScenarioLabController.isLab(deployment.scenarioId()))
                .filter(deployment -> !completeOnly
                        || deployment.phase() == FixedScenarioDeployment.Phase.COMPLETE)
                .min(java.util.Comparator.comparingDouble(
                        deployment -> deployment.anchor().distSqr(origin)))
                .orElse(null);
    }

    private static BlockPos deploymentAnchor(ServerLevel level, BlockPos playerPosition) {
        int x = Math.floorDiv(playerPosition.getX(), 16) * 16;
        int z = Math.floorDiv(playerPosition.getZ(), 16) * 16;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    private static boolean overlapsExisting(FixedScenarioData data, String dimension,
                                            BlockPos anchor, int radius) {
        for (FixedScenarioDeployment existing : data.deployments()) {
            if (!existing.dimension().equals(dimension)) continue;
            FixedScenarioDefinition other = FixedScenarioDefinition.get(existing.scenarioId());
            int otherRadius = other == null ? radius : other.radius();
            double limit = radius + otherRadius + 32.0D;
            double dx = anchor.getX() - existing.anchor().getX();
            double dz = anchor.getZ() - existing.anchor().getZ();
            if (dx * dx + dz * dz < limit * limit) return true;
        }
        return false;
    }

    /** 按模板旋转后的真实边界做稀疏三维采样；默认拒绝覆盖明显的玩家建筑。 */
    private static boolean placementAreasClear(ServerLevel level, BlockPos anchor,
                                               FixedScenarioDefinition definition) {
        for (var terrain : definition.terrain()) {
            if (!terrainTargetClear(level, anchor, terrain)) return false;
        }
        for (var placement : definition.placements()) {
            BlockPos origin = anchor.offset(placement.x(), 0, placement.z());
            var config = com.jgeted.sagadyssey.core.structure.SagadysseyStructures
                    .getConfig(placement.templateId());
            String path = config == null ? placement.templateId() : config.getNbtFileName();
            var template = level.getStructureManager().get(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("sagadyssey", path));
            if (template.isEmpty()) return false;
            BoundingBox box = template.get().getBoundingBox(
                    new StructurePlaceSettings().setRotation(placement.rotation()), origin);
            for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
                for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++)
                    level.getChunk(chunkX, chunkZ);
            }
            for (int x = box.minX(); x <= box.maxX(); x += 4) {
                for (int z = box.minZ(); z <= box.maxZ(); z += 4) {
                    for (int y = box.minY(); y <= Math.min(box.maxY(), box.minY() + 24); y += 4) {
                        var state = level.getBlockState(new BlockPos(x, y, z));
                        if (!state.isAir() && !state.canBeReplaced()) return false;
                    }
                }
            }
        }
        for (var warehouse : definition.warehouses()) {
            BlockPos position = anchor.offset(warehouse.x(), 0, warehouse.z());
            if (!level.hasChunkAt(position)) level.getChunkAt(position);
            var state = level.getBlockState(position);
            if (!state.isAir() && !state.canBeReplaced()) return false;
        }
        return true;
    }

    /** 地表层允许替换自然地面；高处方块和农作物层只允许空气、可替换植物或同类场景方块。 */
    private static boolean terrainTargetClear(ServerLevel level, BlockPos anchor,
                                              FixedScenarioDefinition.TerrainBlock terrain) {
        BlockPos ground = anchor.offset(terrain.x(), terrain.y() - 1, terrain.z());
        BlockPos target = switch (terrain.kind()) {
            case FENCE, FARM_GATE, COMPOSTER, FURNACE, BLAST_FURNACE, ANVIL, SMITHING_TABLE, BELL ->
                    ground.above();
            default -> ground;
        };
        if (!level.hasChunkAt(target)) level.getChunkAt(target);
        BlockState state = level.getBlockState(target);
        boolean targetClear = state.isAir() || state.canBeReplaced()
                || matchesTerrainKind(state, terrain.kind())
                || target.equals(ground) && terrain.y() == 0 && naturalSurface(state);
        if (!targetClear) return false;

        if (terrain.kind() == FixedScenarioDefinition.TerrainKind.WATER) {
            BlockState above = level.getBlockState(ground.above());
            return above.isAir() || above.canBeReplaced() || above.is(Blocks.LILY_PAD);
        }
        if (terrain.kind() == FixedScenarioDefinition.TerrainKind.FARMLAND_EMPTY
                || terrain.kind() == FixedScenarioDefinition.TerrainKind.FARMLAND_GROWING
                || terrain.kind() == FixedScenarioDefinition.TerrainKind.FARMLAND_MATURE) {
            BlockState above = level.getBlockState(ground.above());
            return above.isAir() || above.canBeReplaced();
        }
        return true;
    }

    private static boolean matchesTerrainKind(BlockState state,
                                              FixedScenarioDefinition.TerrainKind kind) {
        Block expected = switch (kind) {
            case ROAD -> Blocks.GRAVEL;
            case PLAZA -> Blocks.COBBLESTONE;
            case WATER -> Blocks.WATER;
            case FARMLAND_EMPTY, FARMLAND_GROWING, FARMLAND_MATURE -> Blocks.FARMLAND;
            case FENCE -> Blocks.OAK_FENCE;
            case FARM_GATE -> Blocks.OAK_FENCE_GATE;
            case COMPOSTER -> Blocks.COMPOSTER;
            case STONE -> Blocks.STONE;
            case COAL_ORE -> Blocks.COAL_ORE;
            case IRON_ORE -> Blocks.IRON_ORE;
            case FURNACE -> Blocks.FURNACE;
            case BLAST_FURNACE -> Blocks.BLAST_FURNACE;
            case ANVIL -> Blocks.ANVIL;
            case SMITHING_TABLE -> Blocks.SMITHING_TABLE;
            case BELL -> Blocks.BELL;
        };
        return state.is(expected);
    }

    private static boolean naturalSurface(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM) || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND) || state.is(Blocks.STONE)
                || state.is(Blocks.DEEPSLATE) || state.is(Blocks.SNOW_BLOCK);
    }

    private static void drawPreview(ServerPlayer player, BlockPos anchor,
                                    FixedScenarioDefinition definition) {
        ServerLevel level = player.level();
        DustParticleOptions border = new DustParticleOptions(MinecraftApi.particleColor(new Vector3f(0.2F, 0.85F, 1.0F)), 0.8F);
        int radius = definition.radius();
        for (int offset = -radius; offset <= radius; offset += 8) {
            send(level, player, border, anchor.getX() + offset, anchor.getY() + 1, anchor.getZ() - radius);
            send(level, player, border, anchor.getX() + offset, anchor.getY() + 1, anchor.getZ() + radius);
            send(level, player, border, anchor.getX() - radius, anchor.getY() + 1, anchor.getZ() + offset);
            send(level, player, border, anchor.getX() + radius, anchor.getY() + 1, anchor.getZ() + offset);
        }
        DustParticleOptions center = new DustParticleOptions(MinecraftApi.particleColor(new Vector3f(1.0F, 0.72F, 0.15F)), 1.2F);
        for (var settlement : definition.settlements()) {
            BlockPos pos = anchor.offset(settlement.x(), 1, settlement.z());
            level.sendParticles(player, center, true, false, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                    24, 1.5D, 1.0D, 1.5D, 0.01D);
            player.sendSystemMessage(Component.literal("§e" + settlement.name() + " §7中心 " + pos.toShortString()));
        }
        DustParticleOptions farmland = new DustParticleOptions(MinecraftApi.particleColor(new Vector3f(0.2F, 0.95F, 0.25F)), 0.9F);
        for (var farm : definition.farms()) {
            int minX = anchor.getX() + farm.x() - 1;
            int maxX = anchor.getX() + farm.x() + farm.size();
            int minZ = anchor.getZ() + farm.z() - 1;
            int maxZ = anchor.getZ() + farm.z() + farm.size();
            for (int coordinate = 0; coordinate <= farm.size() + 1; coordinate++) {
                send(level, player, farmland, minX + coordinate, anchor.getY() + 1, minZ);
                send(level, player, farmland, minX + coordinate, anchor.getY() + 1, maxZ);
                send(level, player, farmland, minX, anchor.getY() + 1, minZ + coordinate);
                send(level, player, farmland, maxX, anchor.getY() + 1, minZ + coordinate);
            }
            player.sendSystemMessage(Component.literal("§a农田 " + farm.key() + " §7工作区 "
                    + new BlockPos(minX + 1, anchor.getY() - 1, minZ + 1).toShortString()));
        }
    }

    private static void send(ServerLevel level, ServerPlayer player, DustParticleOptions particle,
                             double x, double y, double z) {
        level.sendParticles(player, particle, true, false, x + 0.5D, y, z + 0.5D,
                1, 0.0D, 0.0D, 0.0D, 0.0D);
    }
}
