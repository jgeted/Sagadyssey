package com.jgeted.sagadyssey.npc.brain.debug;

import com.jgeted.sagadyssey.npc.brain.debug.NpcBrainSnapshot.BlockerSummary;
import com.jgeted.sagadyssey.npc.brain.debug.NpcBrainSnapshot.HistorySummary;
import com.jgeted.sagadyssey.npc.brain.debug.NpcBrainSnapshot.PlanSummary;
import com.jgeted.sagadyssey.npc.brain.debug.NpcBrainSnapshot.ReservationSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 将大脑快照格式化为可直接发送给管理员的紧凑中文行。 */
public final class NpcBrainSnapshotFormatter {
    private static final int TEXT_LIMIT = 72;
    private static final int HISTORY_OUTPUT_LIMIT = 5;
    private static final int RESERVATION_OUTPUT_LIMIT = 5;

    private NpcBrainSnapshotFormatter() {
    }

    public static List<String> format(NpcBrainSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
        List<String> lines = new ArrayList<>();
        lines.add("NPC脑状态 " + shortId(snapshot.npcId())
                + "｜模式=" + modeLabel(snapshot.behaviorMode().name()));

        PlanSummary active = snapshot.activePlan();
        if (active == null) {
            lines.add("当前计划：无");
        } else {
            lines.add("当前计划：" + active.kind() + "/" + active.priority()
                    + "｜" + active.status() + "｜阶段=" + active.phase());
            lines.add("步骤：" + (active.currentStepIndex() + 1) + "/" + active.stepCount()
                    + " " + active.currentStep().status()
                    + "｜执行器=" + active.currentStep().executorId());
            if (!active.currentStep().detail().isBlank()) {
                lines.add("步骤说明：" + compact(active.currentStep().detail()));
            }
            if (active.blocker() != null) {
                lines.add(formatBlocker(active.blocker()));
            }
        }

        lines.add(formatHistory(snapshot.recentHistory()));
        lines.add(formatReservations(snapshot));
        return List.copyOf(lines);
    }

    public static String formatInline(NpcBrainSnapshot snapshot) {
        return String.join("\n", format(snapshot));
    }

    private static String formatBlocker(BlockerSummary blocker) {
        StringBuilder result = new StringBuilder("阻塞：")
                .append(blocker.code());
        if (!blocker.detail().isBlank()) {
            result.append("(").append(compact(blocker.detail())).append(")");
        }
        result.append("｜重试@").append(blocker.retryAt());
        if (blocker.dependencyKey() != null) {
            result.append("｜依赖=").append(compact(blocker.dependencyKey()));
        }
        return result.toString();
    }

    private static String formatHistory(List<HistorySummary> history) {
        if (history.isEmpty()) return "最近历史：无";
        String entries = history.stream()
                .limit(HISTORY_OUTPUT_LIMIT)
                .map(entry -> entry.status() + " " + compact(entry.candidateKey())
                        + "@" + entry.updatedAt())
                .reduce((left, right) -> left + "；" + right)
                .orElse("无");
        String omitted = history.size() > HISTORY_OUTPUT_LIMIT
                ? "；另" + (history.size() - HISTORY_OUTPUT_LIMIT) + "项" : "";
        return "最近历史(" + history.size() + ")：" + entries + omitted;
    }

    private static String formatReservations(NpcBrainSnapshot snapshot) {
        List<ReservationSummary> reservations = snapshot.reservations();
        if (reservations.isEmpty()) return "资源保留：无";
        String entries = reservations.stream()
                .limit(RESERVATION_OUTPUT_LIMIT)
                .map(entry -> compact(entry.type()) + "=" + compact(entry.resourceId())
                        + "→" + entry.expiresAt() + "｜计划=" + shortId(entry.planId())
                        + (snapshot.isActiveReservation(entry) ? "" : "（非活动计划）"))
                .reduce((left, right) -> left + "；" + right)
                .orElse("无");
        String omitted = reservations.size() > RESERVATION_OUTPUT_LIMIT
                ? "；另" + (reservations.size() - RESERVATION_OUTPUT_LIMIT) + "项" : "";
        return "资源保留(" + reservations.size() + ")：" + entries + omitted;
    }

    private static String modeLabel(String name) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "LEGACY" -> "旧版";
            case "PLANNED" -> "计划制";
            default -> compact(name);
        };
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    /** 防止存档中的换行污染管理员聊天，并限制单项文本长度。 */
    private static String compact(String value) {
        String result = value == null ? "" : value
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim();
        if (result.length() <= TEXT_LIMIT) return result;
        return result.substring(0, TEXT_LIMIT - 1) + "…";
    }
}
