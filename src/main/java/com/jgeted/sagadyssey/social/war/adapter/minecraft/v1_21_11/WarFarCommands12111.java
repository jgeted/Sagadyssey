package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import com.jgeted.sagadyssey.social.war.adapter.minecraft.*;
import com.jgeted.sagadyssey.social.war.domain.WarFarReadiness;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid="sagadyssey")
public final class WarFarCommands12111 {
    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("far").requires(s -> s.getEntity() instanceof ServerPlayer);
        var prepare = Commands.argument("unit", StringArgumentType.word())
                .then(Commands.argument("ticks", IntegerArgumentType.integer(WarFarReadiness.MIN_DURATION,WarFarReadiness.MAX_DURATION))
                        .executes(c -> run(c,"prepare")));
        root.then(Commands.literal("prepare").then(Commands.argument("army", StringArgumentType.word()).then(prepare)));
        for (String action : new String[]{"status","cancel"}) {
            root.then(Commands.literal(action).then(Commands.argument("army", StringArgumentType.word())
                    .then(Commands.argument("unit", StringArgumentType.word()).executes(c -> run(c,action)))));
        }
        event.getDispatcher().register(Commands.literal("saga").then(Commands.literal("war").then(root)));
    }

    private static int run(CommandContext<CommandSourceStack> c, String action) {
        var source = c.getSource();
        try {
            var player = source.getPlayerOrException();
            var server = player.level().getServer();
            var muster = WarMusterRuntime.existing(server).orElseThrow();
            var army = WarMusterEntry.armyId(muster.actor(player), StringArgumentType.getString(c,"army"));
            var unit = WarMusterEntry.unitId(army, StringArgumentType.getString(c,"unit"));
            if (!muster.book().owns(muster.actor(player),army) || muster.book().unit(unit).isEmpty()) throw new IllegalArgumentException("未找到本人可管理的兵团");
            var field = WarServerEvents.existing(server).flatMap(WarServerRuntime::field).orElseThrow();
            String line = switch (action) {
                case "prepare" -> "离屏整备批准正在保存，等原驻地实际卸载后计时 " + field.openFar().prepare(player,army,unit,IntegerArgumentType.getInteger(c,"ticks"));
                case "cancel" -> field.far().orElseThrow().cancel(player,army,unit);
                default -> field.far().map(f -> f.status(player,army,unit)).orElse("本兵团没有离屏整备记录");
            };
            source.sendSuccess(() -> Component.literal(line),false);
            return 1;
        } catch (Exception failure) {
            String message = failure.getMessage();
            source.sendFailure(Component.literal(message == null ? "离屏整备不可用" : message));
            return 0;
        }
    }
}
