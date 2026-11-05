package com.jgeted.sagadyssey.social.economy;

import com.jgeted.sagadyssey.social.settlement.SettlementRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** 查看附近聚落的人口与实体口粮结算。 */
public final class SettlementEconomyCommand {
    private SettlementEconomyCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("saga").then(Commands.literal("economy")
                .executes(ctx -> show(ctx.getSource()))));
    }

    private static int show(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SettlementRecord settlement = SettlementRegistryData.get(source.getServer()).nearest(
                player.serverLevel().dimension().location().toString(), player.blockPosition(), 2048.0D);
        if (settlement == null) {
            source.sendFailure(Component.literal("附近没有已登记聚落。"));
            return 0;
        }
        SettlementEconomyRecord record = SettlementEconomyData.get(source.getServer()).record(settlement.id());
        if (record == null) {
            source.sendSuccess(() -> Component.literal("§e" + settlement.name() + " 尚未进行首次每日供给结算。"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§6" + settlement.name() + " §7人口="
                + record.population() + "，今日口粮=" + record.foodConsumed() + "/" + record.foodRequired()
                + "，连续缺粮=" + record.shortageDays() + " 天"), false);
        return 1;
    }
}
