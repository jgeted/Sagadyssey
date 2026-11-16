package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.social.war.api.WarIntent;
import com.jgeted.sagadyssey.social.war.api.WarKey;
import com.jgeted.sagadyssey.social.war.domain.WarEngineeringJob;
import com.jgeted.sagadyssey.social.war.domain.WarEngineeringJob.Mode;
import com.jgeted.sagadyssey.social.war.domain.WarEngineeringJob.Phase;
import com.jgeted.sagadyssey.social.war.domain.WarEngineeringLayouts;
import com.jgeted.sagadyssey.social.war.domain.WarEngineeringProgram.Kind;
import com.jgeted.sagadyssey.social.war.domain.WarHazardMemory;
import com.jgeted.sagadyssey.social.war.domain.WarHazardObservation;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.Army;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.Offer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.*;

import static com.jgeted.sagadyssey.social.war.adapter.minecraft.WarManagementRuntime.*;

final class WarEngineeringPanel {
    private WarEngineeringPanel() {}

    static void open(Session s, Person person, Offer offer) {
        var npc = person.near(s);
        var army = WarMusterPanel.army(s, offer.request().armyId());
        Step reopen = () -> open(s, person, offer);
        var page = s.page(npc.getNpcName() + " · 工程")
                .button("构筑工事", () -> materials(s, offer, reopen))
                .button("挖掘方块", () -> area(s, offer, Mode.DIG, Optional.empty(), reopen))
                .button("尖刺坑", () -> trap(s, offer, Kind.SPIKE_PIT, reopen))
                .button("蛛网陷阱", () -> trap(s, offer, Kind.WEB_SNARE, reopen))
                .button("开挖地道", () -> tunnel(s, offer, reopen))
                .button("现场侦察", () -> survey(s, offer, reopen))
                .button("危险报告与排障", () -> hazards(s, offer, army, reopen, 0))
                .button("本人工单", () -> jobs(s, army, Optional.of(offer.id()), reopen, 0))
                .button("返回", () -> WarMusterPanel.person(s, person));
        page.show();
    }

    private static void materials(Session s, Offer offer, Step back) throws Exception {
        var runtime = WarEngineeringRuntime.open(s.server());
        runtime.authorizedEngineer(s.player, offer.id(), true);
        var page = s.page("构筑工事 · 材料").line("由工兵本人使用实际背包材料。");
        for (var material : WarNativeBindings.currentBuild().engineering().buildMaterials())
            page.button(material.label(), () -> area(s, offer, Mode.BUILD, Optional.of(material.key()), back));
        page.button("返回", back).show();
    }

    private record Mark(BlockPos block, Direction face) {
        BlockPos adjacent() { return block.relative(face); }
    }

    @FunctionalInterface private interface Route { void selected(List<Mark> marks) throws Exception; }

    private static void mark(Session s, List<String> labels, List<Mark> marks, Route route) {
        s.pickBlock("持原地图右键" + labels.get(marks.size()) + "。潜行朝空中右键可取消。", (block, face) -> {
            var selected = new ArrayList<>(marks);
            selected.add(new Mark(block, face));
            if (selected.size() == labels.size()) route.selected(List.copyOf(selected));
            else mark(s, labels, List.copyOf(selected), route);
        });
    }

    private static void area(Session s, Offer offer, Mode mode, Optional<WarKey> material, Step back) throws Exception {
        var runtime = WarEngineeringRuntime.open(s.server());
        runtime.authorizedEngineer(s.player, offer.id(), true);
        var labels = mode == Mode.BUILD ? List.of("起点相邻方块的朝向面", "终点相邻方块的朝向面")
                : List.of("要挖的第一个方块", "对角处的最后一个方块");
        mark(s, labels, List.of(), points -> {
            var a = mode == Mode.BUILD ? points.getFirst().adjacent() : points.getFirst().block();
            var b = mode == Mode.BUILD ? points.getLast().adjacent() : points.getLast().block();
            confirm(s, runtime, runtime.preview(s.player, offer.id(), mode, a, b, material), offer, back);
        });
    }

    private static void trap(Session s, Offer offer, Kind kind, Step back) {
        s.page(kind == Kind.SPIKE_PIT ? "尖刺坑" : "蛛网陷阱")
                .line("沿一条水平直线标记两端。沿起点至终点的方向，可以指定工兵从哪一侧施工。")
                .button("左侧施工", () -> trapRoute(s, offer, kind, -1, back))
                .button("右侧施工", () -> trapRoute(s, offer, kind, 1, back)).button("返回", back).show();
    }

    private static void trapRoute(Session s, Offer offer, Kind kind, int side, Step back) throws Exception {
        var runtime = WarEngineeringRuntime.open(s.server());
        runtime.authorizedEngineer(s.player, offer.id(), true);
        mark(s, List.of("线路起点地面", "线路终点地面"), List.of(), marks -> {
            var a = marks.getFirst().block();
            var b = marks.getLast().block();
            if (kind == Kind.WEB_SNARE) { a = a.above(); b = b.above(); }
            var layout = WarEngineeringLayouts.trap(kind, position(s, a), position(s, b), side);
            confirm(s, runtime, runtime.previewProgram(s.player, offer.id(), layout), offer, back);
        });
    }

    private static void tunnel(Session s, Offer offer, Step back) throws Exception {
        var runtime = WarEngineeringRuntime.open(s.server());
        runtime.authorizedEngineer(s.player, offer.id(), true);
        mark(s, List.of("入口脚下的方块", "转点上方的方块", "终点上方的方块"), List.of(), marks -> {
            var entrance = marks.getFirst().block().above();
            var bend = marks.get(1).block().atY(entrance.getY());
            var end = marks.getLast().block().atY(entrance.getY());
            var layout = WarEngineeringLayouts.tunnel(position(s, entrance), position(s, bend), position(s, end));
            confirm(s, runtime, runtime.previewProgram(s.player, offer.id(), layout), offer, back);
        });
    }

    private static void survey(Session s, Offer offer, Step back) throws Exception {
        WarEngineeringRuntime.open(s.server()).authorizedEngineer(s.player, offer.id(), true);
        mark(s, List.of("侦察站位脚下方块", "要观察的第一格", "要观察的对角格"), List.of(), marks ->
                s.confirm("批准现场侦察", "工兵将到站位观察实际可见的工作面。", "确认派遣", () -> {
                    var hazards = WarHazardRuntime.open(s.server());
                    s.notice(hazards.survey(s.player, offer.id(), position(s, marks.getFirst().block().above()),
                            position(s, marks.get(1).block()), position(s, marks.getLast().block())));
                    hazards(s, offer, WarMusterPanel.army(s, offer.request().armyId()), back, 0);
                }, back));
    }

    private static void hazards(Session s, Offer offer, Army army, Step back, int pageNumber) {
        var runtime = WarHazardRuntime.existing(s.server());
        var memory = runtime.flatMap(r -> r.memory(army.id()));
        boolean avoid = memory.map(WarHazardMemory::avoid).orElse(true);
        var page = s.page("已知危险").line(avoid ? "兵团避让已知危险。" : "兵团采用承担风险的路线策略。")
                .button(avoid ? "允许承担风险" : "避让已知危险", () -> s.confirm("设置避让", "该策略用于本军已观察到的危险。",
                        "确认策略", () -> {
                            s.notice(WarHazardRuntime.open(s.server()).policy(s.player, army.id(), !avoid));
                            hazards(s, offer, army, back, pageNumber);
                        }, () -> hazards(s, offer, army, back, pageNumber)))
                .button("刷新", () -> hazards(s, offer, army, back, pageNumber)).button("返回", back);
        if (runtime.filter(r -> r.surveying(offer.id())).isPresent())
            page.button("撤回侦察", () -> {
                s.notice(runtime.orElseThrow().cancel(s.player, offer.id()));
                hazards(s, offer, army, back, pageNumber);
            });
        var observations = memory.map(WarHazardMemory::observations).orElse(List.of()).stream()
                .sorted(Comparator.comparingLong(WarHazardObservation::observedAt).reversed()).toList();
        if (observations.isEmpty()) page.line("尚无实际观察报告。");
        page.choices(observations, pageNumber, o -> (o.kind().path().endsWith("/spike") ? "尖刺坑" : "蛛网")
                + " · " + o.position().x() + " " + o.position().z(), o -> {
            var engineering = WarEngineeringRuntime.open(s.server());
            confirm(s, engineering, runtime.orElseThrow().disarm(s.player, offer.id(), o.id()), offer,
                    () -> hazards(s, offer, army, back, pageNumber));
        }, next -> hazards(s, offer, army, back, next)).show();
    }

    private static void confirm(Session s, WarEngineeringRuntime runtime, UUID proof, Offer offer, Step back) {
        s.confirm("工程预览", runtime.previewText(proof), "批准施工", () -> {
            UUID id = runtime.confirm(s.player, proof);
            s.notice("已批准，等待工兵实际到场。");
            job(s, WarMusterPanel.army(s, offer.request().armyId()), id, back);
        }, back);
    }

    static void jobs(Session s, Army army, Optional<UUID> service, Step back, int pageNumber) {
        var jobs = WarEngineeringRuntime.existing(s.server()).stream().flatMap(r -> r.entries().stream())
                .filter(j -> j.army().equals(army.id()) && service.map(j.service()::equals).orElse(true))
                .sorted(Comparator.comparingLong(WarEngineeringJob::createdAt).reversed()).toList();
        var page = s.page("原工单").button("刷新", () -> jobs(s, army, service, back, pageNumber)).button("返回", back);
        if (jobs.isEmpty()) page.line("这里暂无工程。");
        page.choices(jobs, pageNumber, j -> name(j) + " · " + j.next() + "/" + j.cells().size(),
                j -> job(s, army, j.id(), () -> jobs(s, army, service, back, pageNumber)),
                next -> jobs(s, army, service, back, next)).show();
    }

    private static void job(Session s, Army army, UUID id, Step back) {
        var r = WarEngineeringRuntime.existing(s.server()).orElseThrow();
        var j = r.find(id).filter(v -> v.army().equals(army.id())).orElseThrow();
        var page = s.page(name(j)).line(WarMusterPanel.personName(s, j.person()) + " · " + phase(j.phase()))
                .line("实际完成 " + j.next() + "/" + j.cells().size() + " 格").line(j.reason())
                .button("刷新", () -> job(s, army, id, back)).button("返回", back);
        if (j.phase() == Phase.ACTIVE) page.button("暂停", () -> control(s, army, id, back, "pause"));
        if (j.phase() == Phase.PAUSED) page.button("继续", () -> control(s, army, id, back, "resume"));
        if (!j.terminal()) page.button("取消工单", () -> s.confirm("取消工单", "已完成的实体方块保留。",
                "确认取消", () -> control(s, army, id, back, "cancel"), () -> job(s, army, id, back)));
        if (j.phase() == Phase.ACTIVE) page.button("验收外部施工", () -> s.confirm("验收外部施工", "只核对当前一格已实现的原目标。",
                "确认验收", () -> { s.notice(r.adopt(s.player, id)); job(s, army, id, back); }, () -> job(s, army, id, back)));
        page.show();
    }

    private static void control(Session s, Army army, UUID id, Step back, String control) {
        s.notice(WarEngineeringRuntime.existing(s.server()).orElseThrow().control(s.player, id, control));
        job(s, army, id, back);
    }

    private static String name(WarEngineeringJob job) {
        return job.program().map(p -> switch (p.kind()) {
            case SPIKE_PIT -> "尖刺坑"; case WEB_SNARE -> "蛛网陷阱";
            case TUNNEL -> "地道"; case DISARM -> "排障";
        }).orElse(job.mode() == Mode.BUILD ? "构筑工事" : "挖掘");
    }

    private static String phase(Phase phase) {
        return switch (phase) {
            case ACTIVE -> "施工中"; case PAUSED -> "已暂停"; case ISSUING -> "正在保存施工回执";
            case REVIEW -> "待核对"; case DONE -> "已完工"; case CANCELLED -> "已取消";
        };
    }

    private static WarIntent.Position position(Session s, BlockPos at) {
        return new WarIntent.Position(s.map.dimension(), at.getX(), at.getY(), at.getZ());
    }
}
