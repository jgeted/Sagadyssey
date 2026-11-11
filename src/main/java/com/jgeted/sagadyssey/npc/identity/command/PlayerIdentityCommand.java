package com.jgeted.sagadyssey.npc.identity.command;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.identity.NpcOffice;
import com.jgeted.sagadyssey.npc.identity.NpcSocialClass;
import com.jgeted.sagadyssey.npc.identity.PlayerIdentityAttachments;
import com.jgeted.sagadyssey.npc.identity.NpcIdentity;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** 管理和检查玩家自身身份；不与集团指挥军衔共用数据。 */
public final class PlayerIdentityCommand {
    private PlayerIdentityCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("saga")
                .then(Commands.literal("identity")
                        .executes(ctx -> show(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                        .then(Commands.literal("get")
                                .executes(ctx -> show(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .requires(src -> MinecraftApi.hasPermission(src, 2))
                                        .executes(ctx -> show(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                        .then(Commands.literal("history")
                                .executes(ctx -> history(ctx.getSource())))
                        .then(Commands.literal("set")
                                .requires(src -> MinecraftApi.hasPermission(src, 2))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("social_class", StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    for (NpcSocialClass socialClass : NpcSocialClass.values()) {
                                                        builder.suggest(socialClass.name());
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(Commands.argument("office", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> {
                                                            for (NpcOffice office : NpcOffice.values()) {
                                                                builder.suggest(office.name());
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(ctx -> set(
                                                                ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "social_class"),
                                                                StringArgumentType.getString(ctx, "office"),
                                                                "player"))
                                                        .then(Commands.argument("title_faction", StringArgumentType.word())
                                                                .executes(ctx -> set(
                                                                        ctx.getSource(),
                                                                        EntityArgument.getPlayer(ctx, "player"),
                                                                        StringArgumentType.getString(ctx, "social_class"),
                                                                        StringArgumentType.getString(ctx, "office"),
                                                                        StringArgumentType.getString(ctx, "title_faction"))))))))));
    }

    private static int history(CommandSourceStack source) {
        var records = CharacterRegistryData.get(source.getServer()).notableDead(8);
        if (records.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7这个世界还没有被编年史记住的已故人物。"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§6世界编年史中的已故人物："), false);
        for (var record : records) {
            source.sendSuccess(() -> Component.literal("§e" + record.name() + " §7— "
                    + record.deathCause() + "，史记权重 " + record.historicalImportance()
                    + "，角色 ID " + record.id()), false);
        }
        return records.size();
    }

    private static int show(CommandSourceStack source, ServerPlayer player) {
        var record = CharacterRegistry.current(player);
        if (record == null) {
            source.sendFailure(Component.literal("当前没有已绑定人物；请完成正常进入或重生。查询不会创建人物。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§6" + record.name() + "：§f")
                .append(NpcIdentity.title(record.socialClass(), record.office(), record.factionId()))
                .append(Component.literal(" §7(" + record.socialClass().name() + "/"
                        + record.office().name() + ", " + record.factionId()
                        + ", life=" + record.lifeNumber() + ", id=" + record.id() + ")")), false);
        return 1;
    }

    private static int set(CommandSourceStack source, ServerPlayer player,
                           String socialClassName, String officeName, String factionId) {
        NpcSocialClass socialClass;
        NpcOffice office;
        try {
            socialClass = NpcSocialClass.valueOf(socialClassName.toUpperCase(java.util.Locale.ROOT));
            office = NpcOffice.valueOf(officeName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("§c无效的社会阶层或职务"));
            return 0;
        }
        if (!player.isAlive() || CharacterRegistry.current(player) == null) {
            source.sendFailure(Component.literal("当前没有可用的在生人物，不能修改身份。"));
            return 0;
        }
        var identity = PlayerIdentityAttachments.get(player);
        identity.set(socialClass, office, factionId);
        CharacterRegistry.updateIdentity(player, socialClass, office, factionId);
        source.sendSuccess(() -> Component.literal("§a已将 " + player.getGameProfile().name() + " 设为 ")
                .append(identity.title()), true);
        return 1;
    }
}
