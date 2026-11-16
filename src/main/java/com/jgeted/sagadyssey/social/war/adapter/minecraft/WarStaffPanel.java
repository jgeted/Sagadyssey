package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.Army;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.Offer;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry.*;
import com.jgeted.sagadyssey.social.war.domain.WarOfficeQualification;
import com.jgeted.sagadyssey.social.war.spi.WarStaffPorts.Reply;
import com.jgeted.sagadyssey.social.war.spi.WarStaffPorts.Status;

import java.util.*;

import static com.jgeted.sagadyssey.social.war.adapter.minecraft.WarManagementRuntime.*;

final class WarStaffPanel {
    private WarStaffPanel() {}

    static void army(Session s, Army army, Step back) {
        var page = s.page(army.name() + " · 编制与军职")
                .button("核定战斗编制", () -> {
                    reply(s, WarStaffRuntime.open(s.server()).review(s.player, army.id()));
                    army(s, army, back);
                }).button("刷新", () -> army(s, army, back)).button("返回", back);
        var staff = WarStaffRuntime.existing(s.server());
        if (staff.isEmpty()) page.line("尚未核定编制。随行队由领主直接指挥。");
        else page.lines(staff.get().status(s.player, army.id()).stream().limit(8).toList())
                .button("查军职履历", () -> appointments(s, army, back, 0));
        page.show();
    }

    private static void appointments(Session s, Army army, Step back, int pageNumber) {
        var staff = WarStaffRuntime.existing(s.server()).orElseThrow();
        var entries = staff.book().entries().stream().filter(Appointment.class::isInstance).map(Appointment.class::cast)
                .filter(a -> a.terms().army().equals(army.id())).sorted(Comparator.comparingLong(Appointment::changedAt).reversed())
                .toList();
        s.page(army.name() + " · 军职履历")
                .choices(entries, pageNumber, a -> WarMusterPanel.personName(s, a.terms().holder()) + " · " + a.terms().role().title()
                        + " · " + phase(a.phase()), a -> history(s, army, a.terms().holder(),
                        () -> appointments(s, army, back, pageNumber)), p -> appointments(s, army, back, p))
                .button("返回", () -> army(s, army, back)).show();
    }

    static void person(Session s, Person who, Offer offer, Army army, Page page) {
        Step back = () -> WarMusterPanel.person(s, who);
        var staff = WarStaffRuntime.existing(s.server());
        var appointment = staff.flatMap(r -> r.book().forPerson(who.character()))
                .filter(a -> a.terms().army().equals(army.id()) && a.terms().service().equals(offer.id())
                        && a.phase() != Phase.REVOKED);
        page.button("查个人履历", () -> history(s, army, who.character(), back));
        if (appointment.isEmpty()) {
            page.button("任用军职", () -> assign(s, who, offer, army));
            return;
        }
        var a = appointment.get();
        page.line(a.terms().role().title() + " · " + phase(a.phase()));
        if (a.phase() == Phase.PREPARING || a.phase() == Phase.ACTIVE) {
            page.button("撤销任命", () -> s.confirm("撤销任命", "本次军职撤销后保留原军役和个人履历。",
                    "确认撤职", () -> {
                        who.near(s);
                        reply(s, staff.orElseThrow().revoke(s.player, a.id()));
                        back.run();
                    }, back));
        }
        if (!staff.orElseThrow().effective(a)) return;
        page.button("听取本人的建议", () -> {
            who.near(s);
            s.page(a.terms().role().title() + " · 建议").lines(staff.orElseThrow().advice(s.player, army.id(), Optional.of(who.character())))
                    .button("返回", back).show();
        }).button("约定岗位津贴", () -> allowance(s, who, a, back));
        if (a.terms().role() == Role.QUARTERMASTER)
            page.button("核对军库与粮饷", () -> WarSupportPanel.army(s, army, back));
        if (a.terms().role() == Role.ENGINEERING_LEAD)
            page.button("查看本军工单", () -> WarEngineeringPanel.jobs(s, army, Optional.empty(), back, 0));
        if (a.terms().role() != Role.ENGINEERING_LEAD || offer.request().role() != com.jgeted.sagadyssey.social.war.api.WarMilitiaRequest.Role.ENGINEER)
            page.button("委托向原旗集结", () -> {
                who.near(s);
                s.confirm("委托集结", "请先用军令地图选择兵团，登记附近的实体集结旗。",
                        "授权一次集结", () -> {
                            who.near(s);
                            var relay = WarStaffRelayRuntime.open(s.server());
                            var task = relay.prepare(s.player, a.id());
                            relay(s, relay, task, back);
                        }, back);
            });
    }

    private static void assign(Session s, Person who, Offer offer, Army army) {
        who.near(s);
        var page = s.page("任用军职").button("返回原人物", () -> WarMusterPanel.person(s, who));
        var r = WarStaffRuntime.existing(s.server());
        if (r.isEmpty() || r.get().book().establishment(army.id()).filter(r.get().book()::durable).isEmpty()) {
            page.line("先核定并保存本军战斗编制，方可任用。");
            page.button("核定编制", () -> army(s, army, () -> assign(s, who, offer, army))).show();
            return;
        }
        for (Role role : Role.values()) {
            if (!WarOfficeQualification.compatible(role, offer.request().role())) continue;
            Optional<UUID> unit = role == Role.CAPTAIN ? Optional.of(offer.request().unitId()) : Optional.empty();
            var qualification = r.get().eligibility(s.player, army.id(), role, unit).orElseThrow();
            if (qualification.available()) page.button(role.title(), () -> modes(s, who, offer, army, role, unit));
            else page.line(role.title() + " · " + (role == Role.CAPTAIN ? "需达到军队和兵团规模" : "需要 " + qualification.required() + " 名战斗人员"));
        }
        page.button("核定编制", () -> army(s, army, () -> assign(s, who, offer, army))).show();
    }

    private static void modes(Session s, Person who, Offer offer, Army army, Role role, Optional<UUID> unit) {
        var page = s.page(role.title()).line("任用范围沿用本人的原军役。");
        for (Mode mode : Mode.values()) page.button(mode(mode), () -> {
            var operation = UUID.randomUUID();
            s.page(role.title() + " · " + mode(mode)).field("任用天数", "2", 2).field("任用理由", "", 80)
                    .action("确认任用", values -> {
                        who.near(s);
                        int days = number(values, 0, 1, 30, "任用天数");
                        String reason = values.get(1).trim();
                        if (reason.isBlank()) throw new IllegalArgumentException("请写明任用理由。");
                        reply(s, WarStaffRuntime.open(s.server()).appoint(s.player, operation, army.id(), role, unit, who.character(), days, mode, reason));
                        WarMusterPanel.person(s, who);
                    }).button("返回", () -> modes(s, who, offer, army, role, unit)).show();
        });
        page.button("返回", () -> assign(s, who, offer, army)).show();
    }

    private static void allowance(Session s, Person who, Appointment appointment, Step back) {
        s.page("岗位津贴").line("津贴按原军职独立记账。撤职后停止计提，欠款留账。")
                .field("每日绿宝石", "1", 2).action("确认津贴", values -> {
                    who.near(s);
                    int daily = number(values, 0, 1, 16, "每日津贴");
                    s.notice(WarCampaignRuntime.open(s.server()).allowance(s.player, appointment.id(), daily));
                    back.run();
                }).button("返回", back).show();
    }

    private static void relay(Session s, WarStaffRelayRuntime runtime, UUID task, Step back) {
        s.page("集结委托").line(runtime.status(s.player, task)).button("刷新", () -> relay(s, runtime, task, back))
                .button("返回", back).show();
    }

    private static void history(Session s, Army army, UUID person, Step back) throws Exception {
        var staff = WarStaffRuntime.open(s.server());
        s.page(WarMusterPanel.personName(s, person) + " · 履历").lines(staff.history(s.player, army.id(), person))
                .button("刷新", () -> history(s, army, person, back)).button("返回", back).show();
    }

    private static void reply(Session s, Reply reply) {
        s.notice(reply.status() == Status.PENDING ? "正在保存。可刷新查看实际状态。"
                : reply.status() == Status.APPLIED ? "已保存。" : reply.reason());
    }

    private static String mode(Mode mode) {
        return switch (mode) { case REGULAR -> "正式任用"; case ACTING -> "代理任用"; case EXCEPTIONAL -> "破格任用"; };
    }

    private static String phase(Phase phase) {
        return switch (phase) {
            case PREPARING -> "任命保存中"; case ACTIVE -> "在任"; case REVOKING -> "撤职保存中";
            case REVOKED -> "已撤职"; case REVIEW -> "待核对";
        };
    }
}
