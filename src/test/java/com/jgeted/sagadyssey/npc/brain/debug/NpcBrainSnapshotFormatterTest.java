package com.jgeted.sagadyssey.npc.brain.debug;

import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPriority;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStepStatus;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcBrainSnapshotFormatterTest {
    @Test
    void formatterProducesCompactChineseAdministratorOutput() {
        UUID npcId = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        UUID planId = UUID.randomUUID();
        NpcBrainSnapshot.StepSummary step = new NpcBrainSnapshot.StepSummary(
                0, NpcPlanPhase.ACTING,
                ResourceLocation.fromNamespaceAndPath("sagadyssey", "smith_work"),
                NpcPlanStepStatus.BLOCKED, 80L, "动作第一行\n伪造下一行");
        NpcBrainSnapshot.BlockerSummary blocker = new NpcBrainSnapshot.BlockerSummary(
                "WORKSTATION_BUSY", "铁砧被占用", 120L, "forge:main");
        NpcBrainSnapshot.PlanSummary plan = new NpcBrainSnapshot.PlanSummary(
                planId, "work:blacksmith", ResourceLocation.fromNamespaceAndPath(
                "sagadyssey", "blacksmith"), NpcPlanKind.WORK, NpcPlanPriority.NORMAL,
                NpcPlanStatus.BLOCKED, NpcPlanPhase.ACTING, 0, 2, step, blocker,
                80L, 200L, "等待");
        NpcBrainSnapshot snapshot = new NpcBrainSnapshot(npcId, NpcBehaviorMode.PLANNED,
                plan,
                List.of(new NpcBrainSnapshot.HistorySummary(UUID.randomUUID(),
                        "work:previous", NpcPlanKind.WORK, NpcPlanPriority.LOW,
                        NpcPlanStatus.SUCCEEDED, 50L, "完成")),
                List.of(new NpcBrainSnapshot.ReservationSummary(
                        "workstation", "overworld:1,64,1", planId, 40L, 160L)));

        List<String> lines = NpcBrainSnapshotFormatter.format(snapshot);
        String output = String.join("\n", lines);

        assertEquals("NPC脑状态 12345678｜模式=计划制", lines.getFirst());
        assertTrue(output.contains("当前计划：WORK/NORMAL｜BLOCKED｜阶段=ACTING"));
        assertTrue(output.contains("步骤：1/2 BLOCKED｜执行器=sagadyssey:smith_work"));
        assertTrue(output.contains("步骤说明：动作第一行 伪造下一行"));
        assertTrue(output.contains("阻塞：WORKSTATION_BUSY(铁砧被占用)｜重试@120｜依赖=forge:main"));
        assertTrue(output.contains("最近历史(1)：SUCCEEDED work:previous@50"));
        assertTrue(output.contains("资源保留(1)：workstation=overworld:1,64,1→160"));
        assertFalse(lines.stream().anyMatch(line -> line.contains("\n")));
    }

    @Test
    void formatterIdentifiesReservationsLeftByInactivePlans() {
        UUID planId = UUID.fromString("87654321-1234-1234-1234-123456789abc");
        NpcBrainSnapshot snapshot = new NpcBrainSnapshot(UUID.randomUUID(),
                NpcBehaviorMode.PLANNED, null, List.of(),
                List.of(new NpcBrainSnapshot.ReservationSummary(
                        "workstation", "forge", planId, 10L, 20L)));

        String output = NpcBrainSnapshotFormatter.formatInline(snapshot);

        assertTrue(output.contains("当前计划：无"));
        assertTrue(output.contains("资源保留(1)：workstation=forge→20｜计划=87654321（非活动计划）"));
    }

    @Test
    void formatterHandlesEmptyStateAndReportsOmittedEntries() {
        List<NpcBrainSnapshot.HistorySummary> history = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            history.add(new NpcBrainSnapshot.HistorySummary(UUID.randomUUID(),
                    "idle:" + index, NpcPlanKind.AMBIENT, NpcPlanPriority.AMBIENT,
                    NpcPlanStatus.CANCELLED, index, ""));
        }
        NpcBrainSnapshot snapshot = new NpcBrainSnapshot(UUID.randomUUID(),
                NpcBehaviorMode.LEGACY, null, history, List.of());

        List<String> lines = NpcBrainSnapshotFormatter.format(snapshot);

        assertTrue(lines.getFirst().endsWith("模式=旧版"));
        assertEquals("当前计划：无", lines.get(1));
        assertTrue(lines.get(2).endsWith("；另3项"));
        assertEquals("资源保留：无", lines.get(3));
    }
}
