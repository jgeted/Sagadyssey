package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.war.api.WarLoadout;
import com.jgeted.sagadyssey.social.war.api.WarMilitiaRequest.Role;
import com.jgeted.sagadyssey.social.war.domain.WarFarReadiness;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;

import java.util.*;

import static com.jgeted.sagadyssey.social.war.adapter.minecraft.WarManagementRuntime.*;

final class WarMusterPanel {
    private WarMusterPanel() {}

    static void armies(Session s) { armies(s, 0); }

    private static void armies(Session s, int pageNumber) {
        var armies = ownedArmies(s);
        var page = s.page("军籍簿");
        if (armies.isEmpty()) page.line("建立一支随行队后，可以编组并与已招募的 NPC 议定军役。");
        page.choices(armies, pageNumber, Army::name, a -> army(s, a), p -> armies(s, p))
                .button("新建军队", () -> s.page("新建军队")
                        .field("军队名", "", 24)
                        .action("登记军队", values -> {
                            result(s, WarMusterRuntime.open(s.server()).createArmy(s.player, values.getFirst().trim()));
                            armies(s);
                        }).button("返回", () -> armies(s)).show())
                .button("军令地图", s::commandMap);
        if (!armies.isEmpty()) page.button("联合计划", () -> WarJointPanel.plans(s, 0));
        page.show();
    }

    static List<Army> ownedArmies(Session s) {
        return WarMusterRuntime.existing(s.server()).map(m -> m.book().entries().stream()
                .filter(Army.class::isInstance).map(Army.class::cast).filter(a -> a.owner().equals(s.actor))
                .sorted(Comparator.comparing(Army::name)).toList()).orElse(List.of());
    }

    static Army army(Session s, UUID id) {
        return WarMusterRuntime.existing(s.server()).flatMap(m -> m.book().army(id))
                .filter(a -> a.owner().equals(s.actor)).orElseThrow(() -> new IllegalArgumentException("军队不属于当前人物。"));
    }

    static List<Unit> units(Session s, Army army) {
        return WarMusterRuntime.existing(s.server()).map(m -> m.book().entries().stream()
                .filter(Unit.class::isInstance).map(Unit.class::cast).filter(u -> u.army().equals(army.id()))
                .sorted(Comparator.comparing(Unit::name)).toList()).orElse(List.of());
    }

    private static void army(Session s, Army a) {
        var m = muster(s);
        var page = s.page(a.name()).button("返回军籍簿", () -> armies(s));
        if (!m.book().owns(s.actor, a.id())) {
            page.line("军队归属正在保存。").button("刷新", () -> army(s, a)).show();
            return;
        }
        page.button("兵团与编组", () -> unitList(s, a, 0))
                .button("核定编制与军职", () -> WarStaffPanel.army(s, a, () -> army(s, a)))
                .button("军库与粮饷", () -> WarSupportPanel.army(s, a, () -> army(s, a)))
                .button("军令地图", s::commandMap).show();
    }

    private static void unitList(Session s, Army army, int pageNumber) {
        s.page(army.name() + " · 兵团")
                .choices(units(s, army), pageNumber, WarMusterPanel::unitLabel, u -> unit(s, army, u.id()),
                        p -> unitList(s, army, p))
                .button("编组新兵团", () -> loadouts(s, army, 0))
                .button("返回", () -> army(s, army)).show();
    }

    private static void loadouts(Session s, Army army, int pageNumber) {
        var catalog = InitialWarDefinitions.LOADOUTS;
        var presets = new LinkedHashSet<WarLoadout>();
        catalog.find(new WarLoadout(WarLoadout.Mount.FOOT, WarLoadout.Weapon.SWORD, false, false).id())
                .ifPresent(presets::add);
        catalog.legacyAliases().values().forEach(key -> catalog.find(key).ifPresent(presets::add));
        presets.addAll(catalog.profiles().values());
        s.page("选择兵团配装").line("军士的武器、护甲和坐骑以本人实际持有或军库可借的装具为准。")
                .choices(List.copyOf(presets), pageNumber, WarLoadout::displayName, loadout ->
                        s.page("编组新兵团").line(loadout.displayName()).field("兵团名", "", 24)
                                .action("登记兵团", values -> {
                                    result(s, muster(s).createUnit(s.player, army.name(), values.getFirst().trim(), loadout.id()));
                                    unitList(s, army, 0);
                                }).button("返回配装", () -> loadouts(s, army, pageNumber)).show(),
                        p -> loadouts(s, army, p))
                .button("返回", () -> unitList(s, army, 0)).show();
    }

    private static void unit(Session s, Army army, UUID id) {
        var m = muster(s);
        var u = m.book().unit(id).orElseThrow();
        var page = s.page(army.name() + " · " + u.name()).line(unitLabel(u))
                .button("返回", () -> unitList(s, army, 0)).button("刷新", () -> unit(s, army, id));
        if (u.recorded() && m.book().durable(u)) {
            var core = WarServerEvents.existing(s.server());
            int size = core.flatMap(WarServerRuntime::mobilization).map(mob ->
                    (int) mob.activeUnit(id).stream().filter(row -> row.request().role() == Role.COMBATANT).count()).orElse(0);
            page.line("正式战斗人员 " + size)
                    .button("设为现场指挥兵团", () -> {
                        var media = WarServerEvents.existing(s.server()).flatMap(WarServerRuntime::openCommandMedia)
                                .orElseThrow(() -> new IllegalArgumentException("现场指挥暂未就绪。"));
                        if (!media.select(s.player, id)) throw new IllegalArgumentException("无法选择该兵团。");
                        s.notice("已选中 " + u.name() + "。可持地图登记实体集结旗，再在附近吹角。");
                        unit(s, army, id);
                    }).button("兵团报告", () -> reports(s, army, u))
                    .button("离屏整备", () -> far(s, army, u));
            core.flatMap(WarServerRuntime::commandMedia).filter(media ->
                    media.selection(s.player).filter(value -> value.unit().equals(id)).isPresent())
                    .ifPresent(media -> page.button("取消现场指挥", () -> {
                        media.clear(s.player);
                        s.notice("已取消现场兵团选择。");
                        unit(s, army, id);
                    }));
        } else page.line("兵团正在登记。");
        page.show();
    }

    private static void reports(Session s, Army army, Unit unit) {
        var field = WarServerEvents.existing(s.server()).flatMap(WarServerRuntime::field)
                .orElseThrow(() -> new IllegalArgumentException("暂无可用军报。"));
        var page = s.page(unit.name() + " · 军报").button("返回", () -> unit(s, army, unit.id()))
                .button("刷新", () -> reports(s, army, unit));
        var rows = field.reports(s.actor, army.id()).stream().filter(r -> r.unit().equals(unit.id())).toList();
        page.line("原兵团报告 " + rows.size() + " 份");
        rows.stream().limit(20).forEach(r -> {
            var p = r.position();
            page.line(personName(s, r.character()) + " · " + r.state() + " · " + p.x() + " " + p.y() + " " + p.z()
                    + " · " + Math.max(0, (s.now() - r.observedAt()) / 20) + " 秒前");
        });
        page.show();
    }

    private static void far(Session s, Army army, Unit unit) {
        var field = WarServerEvents.existing(s.server()).flatMap(WarServerRuntime::field)
                .orElseThrow(() -> new IllegalArgumentException("兵团尚未就绪。"));
        s.page(unit.name() + " · 离屏整备")
                .line(field.far().map(r -> r.status(s.player, army.id(), unit.id())).orElse("暂无离屏整备记录。"))
                .field("有界整备时长（秒）", Integer.toString(WarFarReadiness.MIN_DURATION / 20), 6)
                .action("批准整备", values -> {
                    int ticks = 20 * number(values, 0, (WarFarReadiness.MIN_DURATION + 19) / 20,
                            WarFarReadiness.MAX_DURATION / 20, "整备时长");
                    field.openFar().prepare(s.player, army.id(), unit.id(), ticks);
                    s.notice("已申请保存。原驻地卸载后开始计时。");
                    far(s, army, unit);
                }).button("撤销整备", () -> s.confirm("撤销离屏整备", unit.name(), "确认撤销", () -> {
                    var r = field.far().orElseThrow(() -> new IllegalArgumentException("此兵团暂无整备。"));
                    s.notice(r.cancel(s.player, army.id(), unit.id()));
                    far(s, army, unit);
                }, () -> far(s, army, unit))).button("刷新", () -> far(s, army, unit))
                .button("返回", () -> unit(s, army, unit.id())).show();
    }

    static void person(Session s, Person person) {
        NpcBase npc = person.near(s);
        var page = s.page(npc.getNpcName() + " · 军务").button("刷新", () -> person(s, person))
                .button("军籍簿", () -> armies(s));
        var current = latest(s, person);
        if (current.isEmpty()) page.line("暂无本领主军役。");
        else {
            var o = current.get();
            var u = muster(s).book().unit(o.request().unitId());
            page.line(role(o.request().role()) + " · " + u.map(Unit::name).orElse("原兵团") + " · " + stage(o.stage()))
                    .line("每日 " + o.request().wage().amountPerPeriod() + " 绿宝石").line(o.reason());
            var a = army(s, o.request().armyId());
            if (o.stage() == Stage.ACTIVE && muster(s).book().durable(o)
                    && muster(s).permits(o.request(), s.now())) {
                WarStaffPanel.person(s, person, o, a, page);
                if (o.request().role() == Role.ENGINEER) page.button("商议施工", () -> WarEngineeringPanel.open(s, person, o));
                if (o.request().role() == Role.STAFF) page.button("商议运输", () -> WarSupportPanel.carrier(s, person, o, a));
            }
            if (!o.terminal() && o.stage() != Stage.RELEASING)
                page.button("办理退役", () -> s.confirm("办理退役", "结清原装具、运单和在役责任后释放 " + npc.getNpcName() + "。",
                        "确认退役", () -> {
                            person.near(s);
                            result(s, muster(s).retire(s.player, o.id()));
                            person(s, person);
                        }, () -> person(s, person)));
        }
        if (current.filter(o -> o.reserves(s.now())).isEmpty())
            page.button("提出军役", () -> chooseArmy(s, person, 0));
        page.show();
    }

    private static void chooseArmy(Session s, Person person, int pageNumber) {
        person.near(s);
        var armies = ownedArmies(s).stream().filter(a -> muster(s).book().owns(s.actor, a.id())).toList();
        var page = s.page("选择征召军队");
        if (armies.isEmpty()) page.line("请先在军籍簿建立军队并编组。");
        page.choices(armies, pageNumber, Army::name, a -> enlistUnit(s, person, a, 0), p -> chooseArmy(s, person, p))
                .button("军籍簿", () -> armies(s)).button("返回原人物", () -> person(s, person)).show();
    }

    private static void enlistUnit(Session s, Person person, Army army, int pageNumber) {
        person.near(s);
        var units = units(s, army).stream().filter(u -> u.recorded() && muster(s).book().durable(u)).toList();
        var page = s.page(army.name() + " · 选择兵团");
        if (units.isEmpty()) page.line("军队需要一个完成登记的兵团。");
        page.choices(units, pageNumber, WarMusterPanel::unitLabel, u -> enlistRole(s, person, army, u),
                        p -> enlistUnit(s, person, army, p))
                .button("返回", () -> chooseArmy(s, person, 0)).show();
    }

    private static void enlistRole(Session s, Person person, Army army, Unit unit) {
        person.near(s);
        var page = s.page(unit.name() + " · 军役职责").button("战斗军役", () -> terms(s, person, army, unit, Role.COMBATANT));
        InitialWarDefinitions.LOADOUTS.find(unit.profile()).filter(l -> l.mount() == WarLoadout.Mount.FOOT)
                .ifPresent(l -> page.button("军务勤务", () -> terms(s, person, army, unit, Role.STAFF))
                        .button("工程军役", () -> terms(s, person, army, unit, Role.ENGINEER)));
        page.button("返回", () -> enlistUnit(s, person, army, 0)).show();
    }

    private static void terms(Session s, Person person, Army army, Unit unit, Role role) {
        var npc = person.near(s);
        s.page(npc.getNpcName() + " · " + role(role)).line(army.name() + " · " + unitLabel(unit))
                .field("每日军饷（绿宝石）", "3", 2).field("服役天数", "7", 2)
                .action("预览条款", values -> {
                    int wage = number(values, 0, 1, 64, "每日军饷");
                    int days = number(values, 1, 1, 30, "服役天数");
                    var offer = muster(s).propose(s.player, person.near(s), army.name(), unit.name(), wage, days, role);
                    var page = s.page("确认征召条款")
                            .line(npc.getNpcName() + " · " + role(role) + " · " + army.name() + " / " + unit.name())
                            .line("每日 " + wage + " 绿宝石，至世界刻 " + offer.endsAt() + "。")
                            .line("本人会决定是否接受。期间消耗实际食物，原军饷按期计提。");
                    if (InitialWarDefinitions.LOADOUTS.find(unit.profile()).filter(l -> l.mount() != WarLoadout.Mount.FOOT).isPresent())
                        page.line("骑乘需定期从原背包消耗干草块。");
                    page.button("递交本人决定", () -> {
                        person.near(s);
                        result(s, muster(s).confirm(s.player, offer.ticket()));
                        person(s, person);
                    }).button("返回条款", () -> terms(s, person, army, unit, role)).show();
                }).button("返回", () -> enlistRole(s, person, army, unit)).show();
    }

    static Optional<Offer> latest(Session s, Person person) {
        return WarMusterRuntime.existing(s.server()).flatMap(m -> m.book().latest(person.character()))
                .filter(o -> o.body().equals(person.body()) && o.request().actorId().equals(s.actor));
    }

    static WarMusterRuntime muster(Session s) {
        return WarMusterRuntime.existing(s.server()).orElseThrow(() -> new IllegalArgumentException("尚无原军籍，请先登记军队。"));
    }

    static String personName(Session s, UUID id) {
        var who = CharacterRegistryData.get(s.server()).byId(id);
        return who == null ? "原军士" : who.name();
    }

    static String unitLabel(Unit unit) {
        return unit.name() + " · " + InitialWarDefinitions.LOADOUTS.find(unit.profile())
                .map(WarLoadout::displayName).orElse("原配装");
    }

    private static void result(Session s, WarMusterRuntime.Result result) {
        String message = result.message();
        int uuidSuffix = message.lastIndexOf('：');
        if (uuidSuffix >= 0 && result.identity().map(id -> message.endsWith(id.toString())).orElse(false))
            s.notice(message.substring(0, uuidSuffix));
        else s.notice(message);
    }

    static String role(Role role) {
        return switch (role) {
            case COMBATANT -> "战斗军役"; case STAFF -> "军务勤务"; case ENGINEER -> "工程军役";
            case STANDARD_BEARER -> "旗手军役"; case COURIER -> "运输军役";
        };
    }

    private static String stage(Stage stage) {
        return switch (stage) {
            case PROPOSED -> "等待本人决定"; case DECLINED -> "本人拒绝"; case GRANTING, PREPARING -> "整备中";
            case ACTIVE -> "服役中"; case RELEASING -> "退役结算中"; case ENDED -> "已退役"; case REVIEW -> "待核对";
        };
    }
}
