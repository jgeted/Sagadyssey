package com.jgeted.sagadyssey.core.command;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * NPC 生成命令。
 * /saga npc spawn [profession] — 在玩家位置生成一个 NPC。
 * profession 为 NpcProfession 枚举名，可选，默认 NONE。
 */
public class NpcSpawnCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("saga")
                        .then(Commands.literal("npc")
                                .then(Commands.literal("spawn")
                                        .requires(src -> src.hasPermission(2))
                                        .executes(ctx -> spawnNpc(ctx.getSource(), null))
                                        .then(Commands.argument("profession", StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    for (NpcProfession p : NpcProfession.values()) {
                                                        builder.suggest(p.name());
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> {
                                                    String profName = StringArgumentType.getString(ctx, "profession");
                                                    return spawnNpc(ctx.getSource(), profName);
                                                })
                                        )
                                )
                        )
        );
    }

    private static int spawnNpc(CommandSourceStack source, String professionName) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        final NpcProfession profession;
        if (professionName != null) {
            try {
                profession = NpcProfession.valueOf(professionName.toUpperCase());
            } catch (IllegalArgumentException e) {
                source.sendFailure(Component.literal("§c未知职业：" + professionName
                        + "。可用：WARRIOR, ARCHER, WORKER 等"));
                return 0;
            }
        } else {
            profession = NpcProfession.NONE;
        }

        NpcBase npc = new NpcBase(NpcEntityTypes.NPC_BASE.get(), player.level());
        npc.setPos(player.getX(), player.getY(), player.getZ());
        npc.setProfession(profession);
        player.level().addFreshEntity(npc);

        source.sendSuccess(
                () -> Component.literal("§a已在当前位置生成 NPC（职业：" + profession.getDisplayName() + "）"),
                true
        );
        return 1;
    }
}
