package com.jgeted.sagadyssey.worldsim.verifier;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.Map;

/** Operator-only text interface for persistent before/after verification snapshots. */
public final class VerifierCommand {
    private static final int DISPLAY_LIMIT = 12;

    private VerifierCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("saga")
                .then(Commands.literal("verifier")
                        .requires(source -> MinecraftApi.hasPermission(source, 2))
                        .executes(ctx -> list(ctx.getSource()))
                        .then(Commands.literal("list")
                                .executes(ctx -> list(ctx.getSource())))
                        .then(Commands.literal("snapshot")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> snapshot(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("show")
                                .then(snapshotArgument()
                                        .executes(ctx -> show(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("diff")
                                .then(snapshotArgument()
                                        .executes(ctx -> diff(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"), false))))
                        .then(Commands.literal("conservation")
                                .then(snapshotArgument()
                                        .executes(ctx -> diff(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"), true))))
                        .then(Commands.literal("drop")
                                .then(snapshotArgument()
                                        .executes(ctx -> drop(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
    snapshotArgument() {
        return Commands.argument("name", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                        VerifierSnapshotStore.list(ctx.getSource().getServer()).stream()
                                .map(VerifierSnapshot::name), builder));
    }

    private static int snapshot(CommandSourceStack source, String name) {
        VerifierSnapshot snapshot;
        try {
            snapshot = VerifierSnapshotCapture.capture(source.getServer(), player(source), name);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("快照名称无效：" + exception.getMessage()));
            return 0;
        }
        boolean replaced = VerifierSnapshotStore.get(source.getServer(), snapshot.name()) != null;
        VerifierSnapshotStore.put(source.getServer(), snapshot);
        source.sendSuccess(() -> Component.literal("§a[验收快照] " + snapshot.name()
                + (replaced ? "（已替换）" : "") + " §7tick=" + snapshot.capturedAt()
                + " 摘要=" + snapshot.shortDigest()), false);
        sendSummary(source, snapshot);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        var snapshots = VerifierSnapshotStore.list(source.getServer());
        if (snapshots.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7这个世界还没有验收快照。固定测试场景完成时会自动创建 before，也可以使用："
                    + "/saga verifier snapshot before"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§6[验收快照] §f世界存档内共 "
                + snapshots.size() + " 个（重启后仍保留）："), false);
        snapshots.stream().sorted(Comparator.comparingLong(VerifierSnapshot::capturedAt))
                .forEach(snapshot -> source.sendSuccess(() -> Component.literal(" §e"
                        + snapshot.name() + " §7tick=" + snapshot.capturedAt() + " 容器="
                        + snapshot.containers().size() + " 物品=" + snapshot.totalItemCount()
                        + " 摘要=" + snapshot.shortDigest()), false));
        return snapshots.size();
    }

    private static int show(CommandSourceStack source, String name) {
        VerifierSnapshot snapshot = require(source, name);
        if (snapshot == null) return 0;
        source.sendSuccess(() -> Component.literal("§6[验收快照] §e" + snapshot.name()
                + " §7世界=" + snapshot.worldName() + " tick=" + snapshot.capturedAt()
                + " 维度=" + snapshot.dimension()), false);
        source.sendSuccess(() -> Component.literal("§7场景="
                + (snapshot.scenarioId().isBlank() ? "-" : snapshot.scenarioId()) + " 档位="
                + (snapshot.scenarioProfile().isBlank() ? "-" : snapshot.scenarioProfile())
                + " r" + snapshot.scenarioRevision() + " 摘要=" + snapshot.shortDigest()), false);
        sendSummary(source, snapshot);
        sendLargest(source, "§b物品总量", snapshot.assetTotals(), false);
        return 1;
    }

    private static int diff(CommandSourceStack source, String name, boolean conservationOnly) {
        VerifierSnapshot before = require(source, name);
        if (before == null) return 0;
        VerifierSnapshot after = VerifierSnapshotCapture.capture(source.getServer(), player(source),
                "current_" + Math.max(0L, source.getServer().overworld().getGameTime()));
        VerifierDiff diff = VerifierDiff.between(before, after);
        if (conservationOnly) {
            sendConservation(source, diff);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("§6[验收差异] §e" + name
                + " §7→ 当前；经过 " + diff.elapsedTicks() + " tick"), false);
        sendLargest(source, "§b物品净变化", diff.assetDeltas(), true);
        sendLargest(source, "§d领域计数变化", diff.domainDeltas(), true);
        if (diff.containerChanges().isEmpty()) {
            source.sendSuccess(() -> Component.literal("§a容器内容和修订未变化。"), false);
        } else {
            source.sendSuccess(() -> Component.literal("§e容器变化="
                    + diff.containerChanges().size() + "（最多显示 " + DISPLAY_LIMIT + " 项）"), false);
            diff.containerChanges().stream().limit(DISPLAY_LIMIT).forEach(change ->
                    source.sendSuccess(() -> Component.literal(" §7" + change.kind().name()
                            + " " + shortId(change.containerId()) + " r"
                            + change.beforeRevision() + "→" + change.afterRevision()
                            + formatDeltaSummary(change.assetDeltas(), 4)), false));
        }
        diff.warnings().forEach(warning -> source.sendSuccess(
                () -> Component.literal("§e覆盖警告：" + warning), false));
        source.sendSuccess(() -> Component.literal("§7守恒核对：/saga verifier conservation "
                + name), false);
        return 1;
    }

    private static void sendConservation(CommandSourceStack source, VerifierDiff diff) {
        if (diff.physicallyUnchanged()) {
            source.sendSuccess(() -> Component.literal("§a[守恒摘要] 已捕获范围内的物品指纹总量未变化。"), false);
        } else {
            source.sendSuccess(() -> Component.literal("§e[守恒摘要] 有 "
                    + diff.assetDeltas().size() + " 类物品出现净变化。"), false);
            sendLargest(source, "§e需由生产、消费、掉落进出或管理员重置解释",
                    diff.assetDeltas(), true);
        }
        if (!diff.before().coverageWarnings().isEmpty() || !diff.after().coverageWarnings().isEmpty()) {
            source.sendSuccess(() -> Component.literal("§c结果不完整：before 覆盖警告="
                    + diff.before().coverageWarnings().size() + "，当前覆盖警告="
                    + diff.after().coverageWarnings().size()), false);
        }
        long transferDelta = diff.domainDeltas().getOrDefault("transfer_audits", 0L);
        long historyDelta = diff.domainDeltas().getOrDefault("history_events", 0L);
        source.sendSuccess(() -> Component.literal("§7期间新增转移审计=" + transferDelta
                + "，新增历史事件=" + historyDelta
                + "。净变化不是自动判错，必须与其来源记录一起核对。"), false);
    }

    private static int drop(CommandSourceStack source, String name) {
        if (!VerifierSnapshotStore.remove(source.getServer(), name)) {
            source.sendFailure(Component.literal("没有名为 " + name + " 的验收快照。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§7已从世界存档删除验收快照 " + name
                + "；没有修改游戏世界。"), false);
        return 1;
    }

    @Nullable
    private static VerifierSnapshot require(CommandSourceStack source, String name) {
        VerifierSnapshot snapshot = VerifierSnapshotStore.get(source.getServer(), name);
        if (snapshot == null) source.sendFailure(Component.literal("没有名为 " + name
                + " 的验收快照；使用 /saga verifier list 查看。"));
        return snapshot;
    }

    private static void sendSummary(CommandSourceStack source, VerifierSnapshot snapshot) {
        source.sendSuccess(() -> Component.literal("§7容器=" + snapshot.containers().size()
                + " 物品总数=" + snapshot.totalItemCount() + " 物品指纹="
                + snapshot.assetTotals().size() + " 覆盖警告="
                + snapshot.coverageWarnings().size()), false);
        source.sendSuccess(() -> Component.literal("§7人物="
                + snapshot.domainCounts().getOrDefault("characters", 0L) + " 家庭="
                + snapshot.domainCounts().getOrDefault("households", 0L) + " 制度="
                + snapshot.domainCounts().getOrDefault("institutions", 0L) + " 信念="
                + snapshot.domainCounts().getOrDefault("beliefs", 0L) + " 交易会话="
                + snapshot.domainCounts().getOrDefault("trade_sessions", 0L) + " 运输订单="
                + snapshot.domainCounts().getOrDefault("logistics_orders", 0L)), false);
        snapshot.coverageWarnings().stream().limit(5).forEach(warning -> source.sendSuccess(
                () -> Component.literal(" §e- " + warning), false));
    }

    private static void sendLargest(CommandSourceStack source, String title,
                                    Map<String, Long> values, boolean signed) {
        if (values.isEmpty()) {
            source.sendSuccess(() -> Component.literal(title + "：§7无"), false);
            return;
        }
        source.sendSuccess(() -> Component.literal(title + "=" + values.size()
                + "（最多显示 " + DISPLAY_LIMIT + " 项）"), false);
        values.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(
                        Comparator.comparingLong(value -> Math.abs(value))).reversed())
                .limit(DISPLAY_LIMIT)
                .forEach(entry -> source.sendSuccess(() -> Component.literal(" §7"
                        + entry.getKey() + " " + (signed ? signed(entry.getValue())
                        : entry.getValue())), false));
    }

    private static String formatDeltaSummary(Map<String, Long> deltas, int limit) {
        if (deltas.isEmpty()) return "";
        String joined = deltas.entrySet().stream().limit(limit)
                .map(entry -> entry.getKey() + signed(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(", "));
        return " [" + joined + (deltas.size() > limit ? ", …" : "") + ']';
    }

    private static String signed(long value) {
        return value > 0L ? "+" + value : Long.toString(value);
    }

    private static String shortId(String value) {
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    @Nullable
    private static ServerPlayer player(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? player : null;
    }
}
