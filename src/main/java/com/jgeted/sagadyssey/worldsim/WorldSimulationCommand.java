package com.jgeted.sagadyssey.worldsim;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import com.jgeted.sagadyssey.knowledge.KnowledgeStateData;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Operator-only rollout and read-only migration diagnostics. */
public final class WorldSimulationCommand {
    private WorldSimulationCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("saga")
                .then(Commands.literal("worldv2")
                        .requires(source -> MinecraftApi.hasPermission(source, 2))
                        .executes(context -> status(context.getSource()))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(Commands.literal("dryrun")
                                .executes(context -> dryRun(context.getSource())))
                        .then(Commands.literal("enable")
                                .then(Commands.argument("feature", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                Arrays.stream(WorldSimulationFeature.values())
                                                        .map(WorldSimulationFeature::key), builder))
                                        .executes(context -> enable(context.getSource(),
                                                StringArgumentType.getString(context, "feature")))))
                        .then(Commands.literal("disable")
                                .then(Commands.argument("feature", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                Arrays.stream(WorldSimulationFeature.values())
                                                        .map(WorldSimulationFeature::key), builder))
                                        .executes(context -> disable(context.getSource(),
                                                StringArgumentType.getString(context, "feature")))))));
        dispatcher.register(Commands.literal("saga")
                .then(Commands.literal("assets")
                        .requires(source -> MinecraftApi.hasPermission(source, 2))
                        .then(Commands.literal("audit")
                                .executes(context -> auditAssets(context.getSource(), 10))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                                        .executes(context -> auditAssets(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "limit")))))));
        dispatcher.register(Commands.literal("saga")
                .then(Commands.literal("service")
                        .requires(source -> MinecraftApi.hasPermission(source, 2))
                        .then(Commands.literal("audit")
                                .executes(context -> auditServices(context.getSource(), 10))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                                        .executes(context -> auditServices(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "limit")))))));
        dispatcher.register(Commands.literal("saga")
                .then(Commands.literal("knowledge")
                        .requires(source -> MinecraftApi.hasPermission(source, 2))
                        .then(Commands.literal("audit")
                                .then(Commands.argument("knower", StringArgumentType.word())
                                        .then(Commands.argument("subject", StringArgumentType.word())
                                                .executes(context -> auditKnowledge(context.getSource(),
                                                        StringArgumentType.getString(context, "knower"),
                                                        StringArgumentType.getString(context, "subject"))))))));
    }

    private static int status(CommandSourceStack source) {
        WorldSimulationData data = WorldSimulationData.get(source.getServer());
        String enabled = data.enabledFeatures().isEmpty() ? "无（兼容 v1 行为）"
                : data.enabledFeatures().stream().map(WorldSimulationFeature::key).sorted()
                .collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal("§6世界模拟 v2：源版本="
                + data.sourceSchemaVersion() + "，目标版本=" + WorldSimulationSchema.CURRENT_VERSION), false);
        source.sendSuccess(() -> Component.literal("§7已启用特性：" + enabled), false);
        if (!data.lastDryRunDigest().isBlank()) {
            source.sendSuccess(() -> Component.literal("§7最近 dry-run："
                    + data.lastDryRunDigest().substring(0, 12) + "，问题="
                    + data.lastDryRunIssueCount()), false);
        }
        return 1;
    }

    private static int dryRun(CommandSourceStack source) {
        MigrationBaselineReport report = MigrationDryRun.capture(source.getServer());
        WorldSimulationData.get(source.getServer()).recordDryRun(report,
                source.getServer().overworld().getGameTime());
        source.sendSuccess(() -> Component.literal("§6v2 dry-run：NPC=" + report.loadedNpcCount()
                + "，人物档案=" + report.registeredCharacterCount() + "，容器="
                + report.containers().size() + "，资产种类=" + report.assetTotals().size()), false);
        source.sendSuccess(() -> Component.literal("§7摘要=" + report.digest()), false);
        if (report.valid()) {
            source.sendSuccess(() -> Component.literal("§a只读检查通过；没有修改任何库存或人物记录。"), false);
            return 1;
        }
        report.issues().stream().limit(20).forEach(issue ->
                source.sendFailure(Component.literal(" - " + issue)));
        return 0;
    }

    private static int enable(CommandSourceStack source, String key) {
        WorldSimulationFeature feature = WorldSimulationFeature.byKey(key);
        if (feature == null) {
            source.sendFailure(Component.literal("未知 v2 特性：" + key));
            return 0;
        }
        var change = WorldSimulationData.get(source.getServer()).enable(feature);
        if (!change.missingPrerequisites().isEmpty()) {
            String missing = change.missingPrerequisites().stream().map(WorldSimulationFeature::key)
                    .sorted().collect(Collectors.joining(", "));
            source.sendFailure(Component.literal("缺少前置特性：" + missing));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(change.applied()
                ? "§a已为本世界启用：" + feature.key()
                : "§7该特性已经启用：" + feature.key()), true);
        return 1;
    }

    private static int disable(CommandSourceStack source, String key) {
        WorldSimulationFeature feature = WorldSimulationFeature.byKey(key);
        if (feature == null) {
            source.sendFailure(Component.literal("未知 v2 特性：" + key));
            return 0;
        }
        var change = WorldSimulationData.get(source.getServer()).disable(feature);
        String affected = change.affected().stream().map(WorldSimulationFeature::key).sorted()
                .collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal(change.applied()
                ? "§e已禁用（含依赖项）：" + affected
                : "§7该特性原本就未启用：" + feature.key()), true);
        return 1;
    }

    private static int auditAssets(CommandSourceStack source, int limit) {
        RightsStateData rights = RightsStateData.get(source.getServer());
        TransferLedgerData ledger = TransferLedgerData.get(source.getServer());
        source.sendSuccess(() -> Component.literal("§6资产审计：容器=" + rights.contexts().size()
                + "，例外权利声明=" + rights.claims().size() + "，转移记录="
                + ledger.audits().size()), false);
        var audits = ledger.audits();
        int start = Math.max(0, audits.size() - limit);
        for (int index = audits.size() - 1; index >= start; index--) {
            var audit = audits.get(index);
            source.sendSuccess(() -> Component.literal("§7" + audit.transferId().toString().substring(0, 8)
                    + " " + audit.intent().name() + " " + audit.status().name()
                    + (audit.lawful() ? " §a合法" : " §c无合法处分权")
                    + (audit.reason().isBlank() ? "" : " §8" + audit.reason())), false);
        }
        return 1;
    }

    private static int auditServices(CommandSourceStack source, int limit) {
        ServiceRelationshipData services = ServiceRelationshipData.get(source.getServer());
        long now = source.getServer().overworld().getGameTime();
        long active = services.relationships().stream().filter(value -> value.activeAt(now)).count();
        source.sendSuccess(() -> Component.literal("§6服务关系审计：总数="
                + services.relationships().size() + "，生效=" + active
                + "，授权判定=" + services.audits().size()), false);
        var audits = services.audits();
        int start = Math.max(0, audits.size() - limit);
        for (int index = audits.size() - 1; index >= start; index--) {
            var audit = audits.get(index);
            source.sendSuccess(() -> Component.literal("§7" + audit.agentId().toString().substring(0, 8)
                    + " " + audit.scope().name() + " " + audit.result().name()
                    + (audit.reason().isBlank() ? "" : " §8" + audit.reason())), false);
        }
        return 1;
    }

    private static int auditKnowledge(CommandSourceStack source, String knowerText,
                                      String subjectText) {
        java.util.UUID knower;
        java.util.UUID subject;
        try {
            knower = java.util.UUID.fromString(knowerText);
            subject = java.util.UUID.fromString(subjectText);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("knower 与 subject 必须是人物 UUID"));
            return 0;
        }
        KnowledgeStateData knowledge = KnowledgeStateData.get(source.getServer());
        var beliefs = knowledge.beliefsAbout(knower, subject);
        source.sendSuccess(() -> Component.literal("§6认知审计：knower="
                + knower.toString().substring(0, 8) + " subject="
                + subject.toString().substring(0, 8) + " beliefs=" + beliefs.size()), false);
        beliefs.stream().limit(12).forEach(belief -> source.sendSuccess(() -> Component.literal(
                "§7" + belief.key().predicate() + " = " + belief.displayValue()
                        + " §8来源=" + belief.source().name() + " 置信="
                        + String.format(java.util.Locale.ROOT, "%.2f", belief.confidence())), false));
        var npc = CharacterRegistryData.get(source.getServer()).findLoadedNpc(source.getServer(), subject);
        if (npc != null) {
            int occupied = 0;
            for (int slot = 0; slot < npc.getEquipmentInventory().getContainerSize(); slot++) {
                if (!npc.getEquipmentInventory().getItem(slot).isEmpty()) occupied++;
            }
            int occupiedSlots = occupied;
            source.sendSuccess(() -> Component.literal("§c[管理员真值] HP=" + npc.getHealth()
                    + "/" + npc.getMaxHealth() + " 攻击=" + npc.getAttackDamage()
                    + " 士气=" + npc.getMoral() + " 私人物品占用格=" + occupiedSlots), false);
        } else {
            source.sendSuccess(() -> Component.literal("§8[管理员真值] 人物身体当前未加载"), false);
        }
        return 1;
    }
}
