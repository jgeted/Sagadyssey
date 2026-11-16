package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarJointOrderRuntime;
import com.jgeted.sagadyssey.social.war.api.WarMapView;
import com.jgeted.sagadyssey.social.war.domain.WarJointOrder;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;
import java.util.Locale;

@EventBusSubscriber(modid = "sagadyssey")
public final class WarJointCommands12111 {
    private WarJointCommands12111() {}

    @FunctionalInterface
    private interface Action {
        String run(ServerPlayer player, WarJointOrderRuntime runtime) throws Exception;
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        var joint = Commands.literal("joint").requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("new").then(Commands.argument("partner", UuidArgument.uuid()).then(timing(false))))
                .then(Commands.literal("solo").then(timing(true)))
                .then(Commands.literal("list").executes(context -> run(context.getSource(), (player, runtime) -> {
                    var plans = runtime.plans(player);
                    return plans.isEmpty() ? "当前人物没有联合计划" : String.join("\n", plans.stream()
                            .map(plan -> plan.id() + " · " + plan.phase() + " · 发令刻 " + plan.executeAt()).toList());
                })))
                .then(Commands.literal("select").then(Commands.argument("plan", UuidArgument.uuid())
                        .then(Commands.argument("army", StringArgumentType.word())
                        .then(Commands.argument("unit", StringArgumentType.word())
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("move", "hold", "retreat", "rally", "charge"), builder))
                        .then(Commands.argument("x", IntegerArgumentType.integer(0, 127))
                        .then(Commands.argument("z", IntegerArgumentType.integer(0, 127))
                        .then(Commands.argument("share", BoolArgumentType.bool()).executes(WarJointCommands12111::select)))))))))
                .then(Commands.literal("sign").then(Commands.argument("plan", UuidArgument.uuid())
                        .then(Commands.argument("revision", com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
                                .executes(context -> run(context.getSource(), (player, runtime) -> describe(runtime,
                                        runtime.sign(player, UuidArgument.getUuid(context, "plan"),
                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "revision"))))))))
                .then(Commands.literal("status").then(Commands.argument("plan", UuidArgument.uuid())
                        .executes(context -> run(context.getSource(), (player, runtime) ->
                                describe(runtime, runtime.view(player, UuidArgument.getUuid(context, "plan")))))))
                .then(Commands.literal("reports").then(Commands.argument("plan", UuidArgument.uuid())
                        .executes(context -> run(context.getSource(), (player, runtime) ->
                                String.join("\n", runtime.reports(player, UuidArgument.getUuid(context, "plan")))))))
                .then(Commands.literal("close").then(Commands.argument("plan", UuidArgument.uuid())
                        .executes(context -> run(context.getSource(), (player, runtime) ->
                                describe(runtime, runtime.close(player, UuidArgument.getUuid(context, "plan")))))));
        event.getDispatcher().register(Commands.literal("saga").then(Commands.literal("war").then(joint)));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> timing(boolean solo) {
        return Commands.argument("wait", IntegerArgumentType.integer(80, 24000))
                .then(Commands.argument("reportsFor", IntegerArgumentType.integer(20, 24000))
                        .executes(context -> run(context.getSource(), (player, runtime) -> {
                            var partner = solo ? CharacterRegistry.currentId(player) : UuidArgument.getUuid(context, "partner");
                            var plan = runtime.prepare(player, partner, IntegerArgumentType.getInteger(context, "wait"),
                                    IntegerArgumentType.getInteger(context, "reportsFor"));
                            return "联合计划已受理 · " + plan;
                        })));
    }

    private static int select(CommandContext<CommandSourceStack> context) {
        return run(context.getSource(), (player, runtime) -> {
            var mode = WarMapView.Mode.valueOf(StringArgumentType.getString(context, "mode").toUpperCase(Locale.ROOT));
            var plan = runtime.select(player, UuidArgument.getUuid(context, "plan"), StringArgumentType.getString(context, "army"),
                    StringArgumentType.getString(context, "unit"), mode, IntegerArgumentType.getInteger(context, "x"),
                    IntegerArgumentType.getInteger(context, "z"), BoolArgumentType.getBool(context, "share"));
            return describe(runtime, plan);
        });
    }

    private static String describe(WarJointOrderRuntime runtime, WarJointOrder plan) {
        var text = new StringBuilder("联合计划 ").append(plan.id()).append(" · ").append(plan.phase())
                .append(" · 版本 ").append(plan.revision()).append(runtime.isDurable(plan) ? " 已保存" : " 保存中")
                .append(" · 预定发令刻 ").append(plan.executeAt()).append(" · 军报截止刻 ").append(plan.reportsUntil());
        for (var leg : plan.instructions()) {
            var command = leg.command();
            var at = command.intent().target();
            text.append("\n原指挥者 ").append(command.actorId()).append(" · 兵团 ").append(command.unitId())
                    .append(" · ").append(command.intent().kind()).append(" · ").append(at.dimension()).append(' ')
                    .append(at.x()).append(' ').append(at.y()).append(' ').append(at.z())
                    .append(leg.shareReport() ? " · 同意共享军报" : " · 私有军报");
        }
        text.append("\n已签 ").append(plan.signatures().stream().sorted().toList());
        for (var row : plan.receipts()) {
            text.append("\n原军令 ").append(row.order()).append(" · ").append(row.outcome());
            if (row.dueAt() >= 0) text.append(" · 预计收令刻 ").append(row.dueAt());
            if (!row.reason().isBlank()) text.append(" · ").append(row.reason());
        }
        return text.toString();
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("joint new <盟友人物UUID> <等待刻> <军报期>。"
                + "同领主多兵团用 joint solo。各方手持地图，以 joint select 添加己方兵团、军令、地图像素和军报授权。"
                + "双方查看 joint status 后用 joint sign 签署已保存版本。用 joint reports 读共享军报，joint close 关闭。"), false);
        return 1;
    }

    private static int run(CommandSourceStack source, Action action) {
        try {
            var player = source.getPlayerOrException();
            var text = action.run(player, WarJointOrderRuntime.open(player.level().getServer()));
            for (var line : text.split("\n")) source.sendSuccess(() -> Component.literal(line), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            return 0;
        }
    }
}
