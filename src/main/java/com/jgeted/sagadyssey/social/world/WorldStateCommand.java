package com.jgeted.sagadyssey.social.world;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.history.HistoryEventRecord;
import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import com.jgeted.sagadyssey.social.settlement.SettlementRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.social.settlement.SettlementType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.jgeted.sagadyssey.core.structure.SagadysseyStructures;

/** 世界场景、编年史和聚落登记的首批游戏内入口。 */
public final class WorldStateCommand {
    private WorldStateCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("saga")
                .then(Commands.literal("world")
                        .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("history").executes(ctx -> history(ctx.getSource())))));

        dispatcher.register(Commands.literal("saga")
                .then(Commands.literal("settlement")
                        .then(Commands.literal("list").executes(ctx -> listSettlements(ctx.getSource())))
                        .then(Commands.literal("nearest").executes(ctx -> nearest(ctx.getSource())))
                        .then(Commands.literal("create")
                                .requires(source -> MinecraftApi.hasPermission(source, 2))
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            for (SettlementType type : SettlementType.values()) {
                                                builder.suggest(type.name().toLowerCase(java.util.Locale.ROOT));
                                            }
                                            return builder.buildFuture();
                                        })
                                        .then(Commands.argument("faction", StringArgumentType.word())
                                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                                        .executes(ctx -> createSettlement(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "type"),
                                                                StringArgumentType.getString(ctx, "faction"),
                                                                StringArgumentType.getString(ctx, "name")))))))));

        // 旧地图迁移入口：站在建筑基准位置手动补登记，不重新放置方块。
        var addBuildingCommand = Commands.literal("add_building")
                .requires(source -> MinecraftApi.hasPermission(source, 2))
                .then(Commands.argument("structure_id", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            SagadysseyStructures.getAllIds().forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> addBuilding(ctx.getSource(),
                                StringArgumentType.getString(ctx, "structure_id"))));
        dispatcher.register(Commands.literal("saga")
                .then(Commands.literal("settlement").then(addBuildingCommand)));
    }

    private static int status(CommandSourceStack source) {
        WorldScenarioType scenario = WorldScenarioData.get(source.getServer()).scenario();
        source.sendSuccess(() -> Component.literal("§6世界局势：§f").append(scenario.displayName()), false);
        source.sendSuccess(() -> scenario.description().copy().withStyle(net.minecraft.ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("§7稳定 " + scenario.stability()
                + " ｜冲突 " + scenario.conflict() + " ｜物资压力 " + scenario.scarcity()
                + " ｜在位君主 " + (scenario.sovereignAlive() ? "是" : "否")), false);
        return 1;
    }

    private static int history(CommandSourceStack source) {
        var events = WorldHistoryData.get(source.getServer()).recent(10);
        if (events.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7编年史尚无记录。"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§6最近的世界事件："), false);
        for (HistoryEventRecord event : events) {
            source.sendSuccess(() -> Component.literal("§e[" + event.type() + "] §f" + event.subjectName()
                    + (event.detail().isBlank() ? "" : " §7— " + event.detail())
                    + " §8(t=" + event.gameTime() + ")"), false);
        }
        return events.size();
    }

    private static int listSettlements(CommandSourceStack source) {
        SettlementRegistryData data = SettlementRegistryData.get(source.getServer());
        var records = data.allSettlements();
        if (records.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7当前还没有已登记聚落。"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§6已登记聚落（" + records.size() + "）："), false);
        for (SettlementRecord record : records) sendSettlementLine(source, data, record);
        return records.size();
    }

    private static int nearest(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String dimension = player.level().dimension().identifier().toString();
        SettlementRegistryData data = SettlementRegistryData.get(source.getServer());
        SettlementRecord record = data.nearest(dimension, player.blockPosition(), 2048.0D);
        if (record == null) {
            source.sendFailure(Component.literal("附近没有已登记聚落。"));
            return 0;
        }
        sendSettlementLine(source, data, record);
        return 1;
    }

    private static int createSettlement(CommandSourceStack source, String typeName,
                                        String factionId, String name)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SettlementType type = SettlementType.byName(typeName);
        var founder = CharacterRegistry.ensureCurrent(player);
        SettlementRecord record = SettlementRegistryData.get(source.getServer()).create(
                source.getServer(), name, type,
                player.level().dimension().identifier().toString(), player.blockPosition(), 128,
                factionId, founder.id(), player.level().getGameTime());
        source.sendSuccess(() -> Component.literal("§a已登记聚落 " + record.name()
                + "，中心 " + record.center().toShortString() + "，角色创建者 " + founder.name()), true);
        return 1;
    }

    private static int addBuilding(CommandSourceStack source, String structureId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        var config = SagadysseyStructures.getConfig(structureId);
        if (config == null) {
            source.sendFailure(Component.literal("未知 Sagadyssey 建筑：" + structureId));
            return 0;
        }
        SettlementRegistryData.get(source.getServer()).registerGeneratedBuilding(
                source.getServer(), player.level().dimension().identifier().toString(),
                player.blockPosition(), structureId, config.getCategory(), config.getSize(),
                player.level().getGameTime());
        source.sendSuccess(() -> Component.literal("§a已登记旧地图建筑 " + structureId
                + "，没有放置或修改任何方块。"), true);
        return 1;
    }

    private static void sendSettlementLine(CommandSourceStack source,
                                           SettlementRegistryData data,
                                           SettlementRecord record) {
        source.sendSuccess(() -> Component.literal("§e" + record.name() + " §7[" + record.type()
                + "] " + record.dimension() + " " + record.center().toShortString()
                + "，阵营=" + record.factionId() + "，建筑=" + data.buildingCount(record.id())), false);
    }
}
