package com.jgeted.sagadyssey.core.command;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.appearance.NpcFaceVariant;
import com.jgeted.sagadyssey.npc.appearance.NpcGender;
import com.jgeted.sagadyssey.npc.faction.Faction;
import com.jgeted.sagadyssey.npc.faction.FactionRegistry;
import com.jgeted.sagadyssey.npc.identity.NpcSocialStatus;
import com.jgeted.sagadyssey.npc.identity.NpcSocialClass;
import com.jgeted.sagadyssey.npc.identity.NpcOffice;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * NPC 生成命令。
 * /saga npc spawn [profession] [faction] [social_status] — 在玩家位置生成一个 NPC。
 * 阵营参数支持新 Faction ID（如 "kingdom"）和旧 NpcFaction 枚举值的自动映射。
 */
public class NpcSpawnCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("saga")
                        .then(Commands.literal("npc")
                                .then(Commands.literal("spawn")
                                        .requires(src -> src.hasPermission(2))
                                        .executes(ctx -> spawnNpc(ctx.getSource(), null, null, null))
                                        .then(Commands.argument("profession", StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    for (NpcProfession p : NpcProfession.values()) {
                                                        builder.suggest(p.name());
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> {
                                                    String profName = StringArgumentType.getString(ctx, "profession");
                                                    return spawnNpc(ctx.getSource(), profName, null, null);
                                                })
                                                .then(Commands.argument("faction", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> {
                                                            // 建议新 Faction ID
                                                            for (Faction f : FactionRegistry.getAllFactions()) {
                                                                // 简化名称（去掉 "sagadyssey:" 前缀）
                                                                String shortId = f.id();
                                                                if (shortId.startsWith("sagadyssey:")) {
                                                                    shortId = shortId.substring("sagadyssey:".length());
                                                                }
                                                                builder.suggest(shortId);
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(ctx -> {
                                                            String profName = StringArgumentType.getString(ctx, "profession");
                                                            String factionName = StringArgumentType.getString(ctx, "faction");
                                                            return spawnNpc(ctx.getSource(), profName, factionName, null);
                                                        })
                                                        .then(Commands.argument("social_status", StringArgumentType.word())
                                                                .suggests((ctx, builder) -> {
                                                                    for (NpcSocialStatus status : NpcSocialStatus.values()) {
                                                                        builder.suggest(status.name());
                                                                    }
                                                                    return builder.buildFuture();
                                                                })
                                                                .executes(ctx -> spawnNpc(
                                                                        ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "profession"),
                                                                        StringArgumentType.getString(ctx, "faction"),
                                                                        StringArgumentType.getString(ctx, "social_status"))))
                                                )
                                        )
                                )
                                .then(Commands.literal("status")
                                        .requires(src -> src.hasPermission(2))
                                        .then(Commands.argument("npc", EntityArgument.entity())
                                                .then(Commands.argument("social_status", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> {
                                                            for (NpcSocialStatus status : NpcSocialStatus.values()) {
                                                                builder.suggest(status.name());
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                         .executes(ctx -> setSocialStatus(
                                                                 ctx.getSource(),
                                                                 EntityArgument.getEntity(ctx, "npc"),
                                                                 StringArgumentType.getString(ctx, "social_status"))))))
                                .then(Commands.literal("identity")
                                        .requires(src -> src.hasPermission(2))
                                        .then(Commands.argument("npc", EntityArgument.entity())
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
                                                                .executes(ctx -> setIdentity(
                                                                        ctx.getSource(),
                                                                        EntityArgument.getEntity(ctx, "npc"),
                                                                        StringArgumentType.getString(ctx, "social_class"),
                                                                        StringArgumentType.getString(ctx, "office")))))))
                                .then(Commands.literal("showcase")
                                        .requires(src -> src.hasPermission(2))
                                        .executes(ctx -> spawnIdentityShowcase(ctx.getSource(), null, null))
                                        .then(Commands.argument("profession", StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    for (NpcProfession profession : NpcProfession.values()) {
                                                        builder.suggest(profession.name());
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> spawnIdentityShowcase(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "profession"),
                                                        null))
                                                .then(Commands.argument("faction", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> {
                                                            for (Faction faction : FactionRegistry.getAllFactions()) {
                                                                String id = faction.id();
                                                                builder.suggest(id.startsWith("sagadyssey:")
                                                                        ? id.substring("sagadyssey:".length()) : id);
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(ctx -> spawnIdentityShowcase(
                                                                ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "profession"),
                                                                StringArgumentType.getString(ctx, "faction"))))))
                                .then(Commands.literal("appearance")
                                        .requires(src -> src.hasPermission(2))
                                        .then(Commands.argument("npc", EntityArgument.entity())
                                                .then(Commands.argument("gender", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> {
                                                            for (NpcGender gender : NpcGender.values()) {
                                                                builder.suggest(gender.name());
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                        .then(Commands.argument("face", StringArgumentType.word())
                                                                .suggests((ctx, builder) -> {
                                                                    for (NpcFaceVariant face : NpcFaceVariant.values()) {
                                                                        builder.suggest(face.name());
                                                                    }
                                                                    return builder.buildFuture();
                                                                })
                                                                .executes(ctx -> setAppearance(
                                                                        ctx.getSource(),
                                                                        EntityArgument.getEntity(ctx, "npc"),
                                                                        StringArgumentType.getString(ctx, "gender"),
                                                                        StringArgumentType.getString(ctx, "face")))))))
                        )
        );
    }

    private static int setSocialStatus(CommandSourceStack source, Entity entity, String statusName) {
        if (!(entity instanceof NpcBase npc)) {
            source.sendFailure(Component.literal("§c目标不是 Sagadyssey NPC"));
            return 0;
        }
        NpcSocialStatus status;
        try {
            status = NpcSocialStatus.valueOf(statusName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("§c未知身份：" + statusName));
            return 0;
        }
        npc.setSocialStatus(status);
        npc.playSocialGreeting();
        source.sendSuccess(() -> Component.literal("§a已将 " + npc.getNpcName()
                + " 的社会身份设为 " + status.displayName().getString()), true);
        return 1;
    }

    private static int setIdentity(CommandSourceStack source, Entity entity,
                                   String socialClassName, String officeName) {
        if (!(entity instanceof NpcBase npc)) {
            source.sendFailure(Component.literal("§c目标不是 Sagadyssey NPC"));
            return 0;
        }
        NpcSocialClass socialClass;
        NpcOffice office;
        try {
            socialClass = NpcSocialClass.valueOf(socialClassName.toUpperCase(java.util.Locale.ROOT));
            office = NpcOffice.valueOf(officeName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("§c无效的社会阶层或职务"));
            return 0;
        }
        npc.setIdentity(socialClass, office);
        npc.playSocialGreeting();
        source.sendSuccess(() -> Component.literal("§a已将 " + npc.getNpcName() + " 设为 "
                + npc.getIdentityTitle().getString() + "（" + socialClass.name() + "/" + office.name() + "）"), true);
        return 1;
    }

    private static int setAppearance(CommandSourceStack source, Entity entity,
                                     String genderName, String faceName) {
        if (!(entity instanceof NpcBase npc)) {
            source.sendFailure(Component.literal("§c目标不是 Sagadyssey NPC"));
            return 0;
        }
        NpcGender gender;
        NpcFaceVariant face;
        try {
            gender = NpcGender.valueOf(genderName.toUpperCase(java.util.Locale.ROOT));
            face = NpcFaceVariant.valueOf(faceName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("§c无效的性别或脸型"));
            return 0;
        }
        npc.setAppearance(gender, face);
        source.sendSuccess(() -> Component.literal("§a已将 " + npc.getNpcName() + " 的外观设为 ")
                .append(gender.displayName()).append(Component.literal(" / ")).append(face.displayName()), true);
        return 1;
    }

    private static int spawnNpc(CommandSourceStack source, String professionName,
                                String factionName, String socialStatusName) throws CommandSyntaxException {
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

        final Faction faction;
        if (factionName != null) {
            // 先尝试新 Faction ID（如 "kingdom" → "sagadyssey:kingdom"）
            String fullId = factionName.contains(":") ? factionName
                    : "sagadyssey:" + factionName;
            faction = FactionRegistry.get(fullId);
            if (faction == null) {
                source.sendFailure(Component.literal("§c未知阵营：" + factionName
                        + "。可用 Faction ID 见 /sagadyssey faction list"));
                return 0;
            }
        } else {
            faction = FactionRegistry.get("sagadyssey:wilderness");
        }

        final NpcSocialStatus socialStatus;
        if (socialStatusName != null) {
            try {
                socialStatus = NpcSocialStatus.valueOf(socialStatusName.toUpperCase());
            } catch (IllegalArgumentException e) {
                source.sendFailure(Component.literal("§c未知身份：" + socialStatusName
                        + "。可用：COMMONER, FREEMAN, BURGHER, KNIGHT, LORD, DUKE, KING"));
                return 0;
            }
        } else {
            socialStatus = NpcSocialStatus.COMMONER;
        }

        NpcBase npc = new NpcBase(NpcEntityTypes.NPC_BASE.get(), player.level());
        npc.setPos(player.getX(), player.getY(), player.getZ());
        npc.setProfession(profession);
        npc.setFaction(faction);
        npc.setSocialStatus(socialStatus);
        player.level().addFreshEntity(npc);

        String factionDisplay = faction != null ? faction.displayName() : "wilderness";
        source.sendSuccess(
                () -> Component.literal("§a已在当前位置生成 NPC（职业：" + profession.getDisplayName()
                        + "，阵营：" + factionDisplay
                        + "，身份：" + socialStatus.displayName().getString() + "）"),
                true
        );
        return 1;
    }

    /** 在玩家面前生成男女两排共十四个身份表现，便于直接比较服装、脸型和礼仪动作。 */
    private static int spawnIdentityShowcase(CommandSourceStack source, String professionName,
                                             String factionName) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        final NpcProfession profession;
        try {
            profession = professionName == null ? NpcProfession.NONE
                    : NpcProfession.valueOf(professionName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("§c未知职业：" + professionName));
            return 0;
        }

        String fullFactionId = factionName == null ? "sagadyssey:wilderness"
                : (factionName.contains(":") ? factionName : "sagadyssey:" + factionName);
        Faction faction = FactionRegistry.get(fullFactionId);
        if (faction == null) {
            source.sendFailure(Component.literal("§c未知阵营：" + factionName));
            return 0;
        }

        Vec3 look = player.getLookAngle();
        Vec3 forward = new Vec3(look.x, 0.0D, look.z);
        if (forward.lengthSqr() < 1.0E-4D) forward = new Vec3(0.0D, 0.0D, 1.0D);
        forward = forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        NpcSocialStatus[] statuses = NpcSocialStatus.values();
        Vec3 first = player.position().add(forward.scale(5.0D))
                .subtract(right.scale((statuses.length - 1) * 1.1D));

        NpcGender[] genders = NpcGender.values();
        NpcFaceVariant[] faces = NpcFaceVariant.values();
        for (int row = 0; row < genders.length; row++) {
            for (int i = 0; i < statuses.length; i++) {
                NpcBase npc = new NpcBase(NpcEntityTypes.NPC_BASE.get(), player.level());
                Vec3 position = first.add(forward.scale(row * 2.6D)).add(right.scale(i * 2.2D));
                npc.setPos(position.x, player.getY(), position.z);
                npc.setYRot(player.getYRot() + 180.0F);
                npc.setProfession(profession);
                npc.setAppearance(genders[row], faces[(i + row * 3) % faces.length]);
                npc.setFaction(faction);
                npc.setSocialStatus(statuses[i]);
                String label = npc.getIdentityTitle().getString() + " · "
                        + genders[row].displayName().getString();
                npc.setNpcName(label + " · " + profession.getDisplayName());
                npc.setCustomName(Component.literal(label));
                npc.setCustomNameVisible(true);
                player.level().addFreshEntity(npc);
                npc.playSocialGreeting();
            }
        }

        source.sendSuccess(() -> Component.literal("§a已生成 14 个男女身份展示 NPC："
                + "COMMONER → KING（职业：" + profession.getDisplayName()
                + "，阵营：" + faction.displayName() + "）"), true);
        return statuses.length * genders.length;
    }
}
