package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.war.api.WarMapView.Mode;
import com.jgeted.sagadyssey.social.war.domain.WarJointOrder;
import com.jgeted.sagadyssey.social.war.domain.WarJointOrder.Phase;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.Army;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.Unit;

import java.util.*;

import static com.jgeted.sagadyssey.social.war.adapter.minecraft.WarManagementRuntime.*;

final class WarJointPanel {
    private WarJointPanel() {}

    static void plans(Session s, int pageNumber) throws Exception {
        var runtime = WarJointOrderRuntime.open(s.server());
        var plans = runtime.plans(s.player);
        var page = s.page("联合计划").button("本军多兵团计划", () -> schedule(s, runtime, s.actor))
                .button("邀请盟友", () -> partners(s, runtime, 0))
                .button("刷新", () -> plans(s, pageNumber)).button("返回军籍簿", () -> WarMusterPanel.armies(s));
        if (plans.isEmpty()) page.line("等待参与者选择各自兵团、确认目标并签署。");
        page.choices(plans, pageNumber, plan -> label(s, plan), plan -> view(s, runtime, plan.id()),
                next -> plans(s, next)).show();
    }

    private static void partners(Session s, WarJointOrderRuntime runtime, int pageNumber) {
        var allies = s.server().getPlayerList().getPlayers().stream().filter(p -> p != s.player && p.isAlive() && !p.isSpectator())
                .map(CharacterRegistry::currentId).filter(Objects::nonNull)
                .filter(person -> runtime.offeredPartner(s.player, person)).distinct()
                .sorted(Comparator.comparing(person -> WarMusterPanel.personName(s, person))).toList();
        var page = s.page("选择在线盟友");
        if (allies.isEmpty()) page.line("当前无在线盟友人物。");
        page.choices(allies, pageNumber, person -> WarMusterPanel.personName(s, person),
                person -> schedule(s, runtime, person), next -> partners(s, runtime, next))
                .button("返回", () -> plans(s, 0)).show();
    }

    private static void schedule(Session s, WarJointOrderRuntime runtime, UUID partner) {
        s.page(partner.equals(s.actor) ? "协调本军兵团" : "邀请 · " + WarMusterPanel.personName(s, partner))
                .field("开令倒计时（秒）", "120", 4).field("共享军报时限（秒）", "300", 4)
                .action("建立计划", values -> {
                    int wait = 20 * number(values, 0, 4, 1200, "开令倒计时");
                    int report = 20 * number(values, 1, 1, 1200, "军报时限");
                    UUID plan = runtime.prepare(s.player, partner, wait, report);
                    if (!s.actor.equals(partner)) {
                        for (var p : s.server().getPlayerList().getPlayers()) {
                            if (partner.equals(CharacterRegistry.currentId(p))) p.displayClientMessage(
                                    net.minecraft.network.chat.Component.literal("收到联合计划。可持地图打开军籍簿查看并签署。"), false);
                        }
                    }
                    view(s, runtime, plan);
                }).button("返回", () -> plans(s, 0)).show();
    }

    private static void view(Session s, WarJointOrderRuntime runtime, UUID id) {
        var plan = runtime.view(s.player, id);
        var page = s.page(label(s, plan)).line(runtime.isDurable(plan) ? "本版条款已保存。" : "条款正在保存。")
                .line("距约定发令 " + Math.max(0, (plan.executeAt() - s.now() + 19) / 20) + " 秒 · 已签 " + plan.signatures().size() + " 人");
        for (var instruction : plan.instructions()) {
            var c = instruction.command();
            var unit = WarMusterPanel.muster(s).book().unit(c.unitId()).map(Unit::name).orElse("原兵团");
            var pos = c.intent().target();
            var mode = Arrays.stream(Mode.values()).filter(m -> WarMapRuntime.kind(m).equals(c.intent().kind()))
                    .map(Mode::label).findFirst().orElse("军令");
            page.line(WarMusterPanel.personName(s, c.actorId()) + " · " + unit + " · " + mode
                    + " · " + pos.x() + " " + pos.y() + " " + pos.z() + (instruction.shareReport() ? " · 共享军报" : " · 私有军报"));
        }
        if (!plan.receipts().isEmpty()) page.line("已记录 " + plan.receipts().size() + " 道发令结果。");
        page.button("刷新", () -> view(s, runtime, id)).button("返回计划", () -> plans(s, 0));
        if (plan.phase() == Phase.DRAFT) {
            page.button("添加己方军令", () -> selectArmy(s, runtime, id, 0));
            if (!plan.instructions().isEmpty() && runtime.isDurable(plan) && !plan.signatures().contains(s.actor))
                page.button("签署本版计划", () -> {
                    runtime.sign(s.player, id, plan.revision());
                    s.notice("本版签署已受理。");
                    view(s, runtime, id);
                });
        }
        if (plan.phase() == Phase.SUBMITTED || plan.phase() == Phase.REVIEW) {
            page.button("查发令回执", () -> receipts(s, runtime, id));
            page.button("读共享军报", () -> s.page("共享军报").lines(runtime.reports(s.player, id))
                    .button("返回", () -> view(s, runtime, id)).show());
        }
        if (plan.phase() != Phase.CLOSED) page.button("关闭计划", () -> s.confirm("关闭计划",
                "停止本计划及共享，已经提交的原军令各自执行。", "确认关闭", () -> {
                    runtime.close(s.player, id);
                    view(s, runtime, id);
                }, () -> view(s, runtime, id)));
        page.show();
    }

    private static void receipts(Session s, WarJointOrderRuntime runtime, UUID id) {
        var plan = runtime.view(s.player, id);
        var page = s.page("联合发令回执").button("返回", () -> view(s, runtime, id));
        for (var receipt : plan.receipts()) page.line(receipt.outcome() + (receipt.dueAt() >= 0 ? " · 预计收令刻 " + receipt.dueAt() : "")
                + (receipt.reason().isBlank() ? "" : " · " + receipt.reason()));
        page.show();
    }

    private static void selectArmy(Session s, WarJointOrderRuntime runtime, UUID id, int pageNumber) {
        s.page("选择己方军队").choices(WarMusterPanel.ownedArmies(s), pageNumber, Army::name,
                army -> selectUnit(s, runtime, id, army, 0), next -> selectArmy(s, runtime, id, next))
                .button("返回", () -> view(s, runtime, id)).show();
    }

    private static void selectUnit(Session s, WarJointOrderRuntime runtime, UUID id, Army army, int pageNumber) {
        s.page("选择己方兵团").choices(WarMusterPanel.units(s, army).stream().filter(Unit::recorded).toList(),
                pageNumber, WarMusterPanel::unitLabel, unit -> chooseMode(s, runtime, id, army, unit),
                next -> selectUnit(s, runtime, id, army, next)).button("返回", () -> selectArmy(s, runtime, id, 0)).show();
    }

    private static void chooseMode(Session s, WarJointOrderRuntime runtime, UUID id, Army army, Unit unit) {
        var page = s.page(unit.name() + " · 军令");
        for (Mode mode : Mode.values()) page.button(mode.label(), () -> choosePosition(s, runtime, id, army, unit, mode));
        page.button("返回", () -> selectUnit(s, runtime, id, army, 0)).show();
    }

    private static void choosePosition(Session s, WarJointOrderRuntime runtime, UUID id, Army army, Unit unit, Mode mode) {
        s.page(unit.name() + " · " + mode.label()).line("在原地图上点选目标。")
                .map((x, z) -> {
                    var position = WarMapRuntime.target(s.player, s.map, x, z);
                    s.page("确认计划军令").line(unit.name() + " · " + mode.label() + " · " + position.x() + " " + position.y() + " " + position.z())
                            .button("加入计划 · 军报私有", () -> select(s, runtime, id, army, unit, mode, x, z, false))
                            .button("加入计划 · 共享该兵团军报", () -> select(s, runtime, id, army, unit, mode, x, z, true))
                            .button("返回地图", () -> choosePosition(s, runtime, id, army, unit, mode)).show();
                }).button("返回", () -> chooseMode(s, runtime, id, army, unit)).show();
    }

    private static void select(Session s, WarJointOrderRuntime runtime, UUID id, Army army, Unit unit,
                               Mode mode, int x, int z, boolean share) {
        runtime.select(s.player, id, army.name(), unit.name(), mode, x, z, share);
        view(s, runtime, id);
    }

    private static String label(Session s, WarJointOrder plan) {
        String owner = plan.organizer().equals(plan.partner()) ? "本军协调" :
                WarMusterPanel.personName(s, plan.organizer()) + " / " + WarMusterPanel.personName(s, plan.partner());
        String phase = switch (plan.phase()) {
            case DRAFT -> "拟定中"; case SCHEDULED -> "已签署"; case ISSUING -> "发令中";
            case SUBMITTED -> "已提交"; case STOPPED -> "已停止"; case REVIEW -> "待核对"; case CLOSED -> "已关闭";
        };
        return owner + " · " + phase;
    }
}
