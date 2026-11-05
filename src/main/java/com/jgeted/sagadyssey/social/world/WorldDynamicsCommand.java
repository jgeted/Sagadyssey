package com.jgeted.sagadyssey.social.world;

import com.jgeted.sagadyssey.social.settlement.SettlementRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/** 查看活跃世界事件，管理员可强制事件以便一次性测试。 */
public final class WorldDynamicsCommand {
    private WorldDynamicsCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("saga").then(Commands.literal("world")
                .then(Commands.literal("events").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("force_event").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (DynamicWorldEventType type : DynamicWorldEventType.values())
                                        builder.suggest(type.name().toLowerCase(java.util.Locale.ROOT));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> force(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "type"), null))
                                .then(Commands.argument("settlement_id", StringArgumentType.word())
                                        .executes(ctx -> force(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "type"),
                                                StringArgumentType.getString(ctx, "settlement_id"))))))));
    }

    private static int list(CommandSourceStack source) {
        var events = WorldDynamicsData.get(source.getServer()).activeEvents();
        if (events.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7当前没有活跃的世界事件。"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§6当前世界事件："), false);
        for (DynamicWorldEventRecord event : events) {
            SettlementRecord settlement = event.settlementId() == null ? null
                    : SettlementRegistryData.get(source.getServer()).byId(event.settlementId());
            source.sendSuccess(() -> Component.literal("§e" + event.type().displayName() + " §7· "
                    + (settlement == null ? event.factionId() : settlement.name())
                    + " · 强度 " + event.severity() + " · 至第 " + event.endDay() + " 天"), false);
        }
        return events.size();
    }

    private static int force(CommandSourceStack source, String typeName, String settlementId) {
        DynamicWorldEventType type;
        try { type = DynamicWorldEventType.valueOf(typeName.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("无效事件类型。"));
            return 0;
        }
        SettlementRecord settlement = null;
        if (settlementId != null) {
            try { settlement = SettlementRegistryData.get(source.getServer()).byId(UUID.fromString(settlementId)); }
            catch (IllegalArgumentException ignored) { }
            if (settlement == null) {
                source.sendFailure(Component.literal("找不到指定聚落。"));
                return 0;
            }
        }
        long day = source.getServer().overworld().getDayTime() / 24000L;
        var created = WorldDynamicsEvents.startEvent(source.getServer(),
                WorldDynamicsData.get(source.getServer()), type, settlement, day, 2);
        if (created == null) {
            source.sendFailure(Component.literal("同类事件已经生效，或世界事件数量已达上限。"));
            return 0;
        }
        return 1;
    }
}
