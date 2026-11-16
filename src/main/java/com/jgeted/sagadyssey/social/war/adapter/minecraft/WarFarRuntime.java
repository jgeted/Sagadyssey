package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarFarReadinessCodec;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarCommandService;
import com.jgeted.sagadyssey.social.war.domain.WarFarReadiness;
import com.jgeted.sagadyssey.social.war.domain.WarTransmission;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.work.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Optional off-screen preparation. Near actions are fenced until the return or cancel record is durable. */
public final class WarFarRuntime implements AutoCloseable {
    public static final String FILE = "sagadyssey_war_far_readiness.sgw";
    private enum Presence { LOADED, UNLOADED, UNKNOWN, INVALID }
    private record Observation(Presence presence, boolean fit) {}
    private static final class Survey {
        final WarFarReadiness plan;
        int cursor, unloaded, fit;
        boolean loaded, invalid;

        Survey(WarFarReadiness plan) { this.plan = plan; }
    }

    private final MinecraftServer server;
    private final WarServerRuntime core;
    private final WarFieldRuntime field;
    private final WarFieldRuntime.Sources sources;
    private final DurableSnapshotStore<UUID,WarFarReadiness> store;
    private final LinkedHashSet<UUID> ready = new LinkedHashSet<>();
    private final Map<UUID, Survey> surveys = new HashMap<>();
    private String problem = "";

    WarFarRuntime(MinecraftServer server, WarServerRuntime core, WarFieldRuntime field, WarFieldRuntime.Sources sources) throws IOException {
        this.server = server;
        this.core = core;
        this.field = field;
        this.sources = sources;
        store = new DurableSnapshotStore<>(path(server), new WarFarReadinessCodec(), WarFarReadiness::unit, WarFarReadiness::follows, "sagadyssey-war-far");
        for (var plan : store.recover()) {
            if (plan.changedAt() > now() || core.session().flatMap(s -> s.unit(plan.unit()))
                    .filter(u -> u.armyId().equals(plan.army())).isEmpty()) {
                store.close();
                throw new IOException("orphan or future readiness checkpoint");
            }
            if (!plan.terminal()) ready.add(plan.unit());
        }
    }

    public static Path path(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data/" + FILE);
    }

    static boolean present(MinecraftServer server) {
        Path p = path(server);
        return Files.exists(p) || Files.exists(p.resolveSibling(FILE + ".old")) || Files.exists(p.resolveSibling(FILE + "_old"));
    }

    boolean holds(UUID unit) {
        if (store.desired(unit).filter(p -> !p.terminal()).isPresent()) return true;
        return store.durable(unit).filter(p -> !p.terminal()).isPresent();
    }

    public Optional<WarFarReadiness> plan(UUID unit) { return store.desired(unit); }
    public boolean durable(WarFarReadiness plan) { return store.durable(plan.unit()).filter(plan::equals).isPresent(); }
    public String problem() { return problem; }

    public UUID prepare(ServerPlayer player, UUID army, UUID unit, int duration) {
        UUID principal = owner(player, army, unit);
        if (!problem.isEmpty() || holds(unit)) throw new IllegalStateException("兵团已有离屏整备或存档待核对");
        var order = field.received(unit, now()).filter(t -> t.command().intent().kind().equals(InitialWarDefinitions.HOLD))
                .orElseThrow(() -> new IllegalArgumentException("远场整备要求先收到驻守令并实际到位"));
        var reports = new HashMap<UUID, WarFieldReport>();
        for (var report : field.reports(principal, army)) if (report.unit().equals(unit)) reports.put(report.character(), report);
        var posts = new ArrayList<WarFarReadiness.Post>();
        if (order.members().size() > WarFarReadiness.MAX_MEMBERS) throw new IllegalArgumentException("单次离屏整备最多16名原战斗人员");
        for (var member : order.members()) {
            var report = reports.get(member.character());
            if (report == null || !report.order().equals(Optional.of(order.command().operationId()))
                    || !report.contract().equals(member.contract()) || !report.body().equals(member.body())
                    || report.observedAt() > now() || now() - report.observedAt() > 40 || report.state() != WarFieldReport.State.HOLDING)
                throw new IllegalArgumentException("每名原战斗人员必须有当前驻守到位报告");
            var level = level(report.position());
            var entity = level == null ? null : level.getEntity(member.body());
            if (!(entity instanceof NpcBase npc) || !npc.getCharacterId().equals(member.character()) || npc.isPassenger()
                    || npc.isInWater() || npc.isNoAi() || !npc.isAlive()
                    || sources.encounters().activeFor(member.character(), now()) != null)
                throw new IllegalArgumentException("首版整备仅接受在场核验的非交战步兵");
            var actual = new WarIntent.Position(report.position().dimension(), npc.getBlockX(), npc.getBlockY(), npc.getBlockZ());
            posts.add(new WarFarReadiness.Post(member,actual));
        }
        long revision = Math.addExact(plan(unit).map(WarFarReadiness::revision).orElse(0L), 1);
        var plan = WarFarReadiness.approve(unit, army, principal, order.command().operationId(), UUID.randomUUID(), posts, duration, now(), revision);
        if (!currentOrder(plan) || posts.stream().anyMatch(p -> observe(plan, p).presence() != Presence.LOADED))
            throw new IllegalArgumentException("原军役、身体或驻守令已变更");
        put(plan);
        if (!problem.isEmpty()) throw new IllegalStateException(problem);
        return plan.operation();
    }

    public String cancel(ServerPlayer player, UUID army, UUID unit) {
        owner(player, army, unit);
        var p = plan(unit).orElseThrow(() -> new IllegalArgumentException("没有本兵团的整备记录"));
        if (p.terminal()) return "整备已经结束：" + p.phase();
        put(p.cancel(now(), "领主取消离屏整备，保存后交回现役主劳动"));
        if (!problem.isEmpty()) throw new IllegalStateException(problem);
        return "取消正在保存，确认后交回原身体执行";
    }

    public String status(ServerPlayer player, UUID army, UUID unit) {
        owner(player, army, unit);
        var value = plan(unit);
        if (value.isEmpty()) return "本兵团没有离屏整备记录";
        var p = value.get();
        String fit = p.phase() == WarFarReadiness.Phase.PREPARED ? "，最近核验 " + p.fitCount() + "/" + p.posts().size() + " 人可用" : "";
        return p.phase() + " · " + p.reason() + fit + " · " + (durable(p) ? "已保存" : "保存中")
                + (problem.isEmpty() ? "" : " · " + problem);
    }

    private UUID owner(ServerPlayer player, UUID army, UUID unit) {
        var muster = WarMusterRuntime.existing(server).orElseThrow();
        UUID principal = muster.actor(player);
        if (!muster.book().owns(principal, army) || core.permission(principal, army) != WarCommandService.Permission.ALLOWED
                || core.session().flatMap(s -> s.unit(unit)).filter(u -> u.armyId().equals(army)).isEmpty()) {
            throw new IllegalArgumentException("仅本军当前领主可批准或读取离屏整备");
        }
        return principal;
    }

    void tick(long now) {
        if (!problem.isEmpty()) return;
        try { advanceReady(now); }
        catch (RuntimeException failure) { problem = "FAR_READINESS_REVIEW:" + failure.getClass().getSimpleName(); }
    }

    private void advanceReady(long now) {
        for (int n = Math.min(2, ready.size()); n > 0; n--) {
            var first = ready.iterator();
            UUID unit = first.next();
            first.remove();
            var plan = plan(unit).orElseThrow();
            if (!durable(plan)) {
                put(plan);
                continue;
            }
            if (plan.terminal()) {
                surveys.remove(unit);
                continue;
            }
            ready.add(unit);
            if (!currentOrder(plan) || plan.phase() == WarFarReadiness.Phase.ARMED && now - plan.approvedAt() >= WarFarReadiness.UNLOAD_WAIT_LIMIT) {
                put(plan.cancel(now, "当前驻守令或原授权已变化，或等待实际卸载到期"));
                continue;
            }
            scan(plan, now);
        }
    }

    private void scan(WarFarReadiness plan, long now) {
        var survey = surveys.computeIfAbsent(plan.unit(), u -> new Survey(plan));
        for (int n = 0; n < 4 && survey.cursor < plan.posts().size(); n++) {
            var result = observe(plan, plan.posts().get(survey.cursor++));
            if (result.presence() == Presence.LOADED) survey.loaded = true;
            if (result.presence() == Presence.INVALID) survey.invalid = true;
            if (result.presence() == Presence.UNLOADED) survey.unloaded++;
            if (result.fit()) survey.fit++;
        }
        if (survey.invalid) {
            put(plan.cancel(now, "原身份、军役、身体或当前职责证据已变化"));
        } else if (survey.loaded && plan.phase() != WarFarReadiness.Phase.ARMED) {
            put(plan.returned(now));
        } else if (survey.cursor == plan.posts().size()) {
            surveys.remove(plan.unit());
            if (survey.unloaded == plan.posts().size()) {
                if (plan.phase() == WarFarReadiness.Phase.ARMED) put(plan.begin(now));
                else if (plan.phase() == WarFarReadiness.Phase.OFFLINE && now - plan.departedAt() >= plan.duration()) put(plan.prepared(now, survey.fit));
            }
        }
    }

    private boolean currentOrder(WarFarReadiness plan) {
        return core.permission(plan.principal(),plan.army()) == WarCommandService.Permission.ALLOWED
                && field.received(plan.unit(),now()).filter(order -> order.command().armyId().equals(plan.army())
                && order.command().operationId().equals(plan.order()) && order.command().intent().kind().equals(InitialWarDefinitions.HOLD)
                && order.members().equals(plan.posts().stream().map(WarFarReadiness.Post::member).toList())).isPresent();
    }

    private Observation observe(WarFarReadiness plan, WarFarReadiness.Post post) {
        var member = post.member();
        var identity = sources.characters().byId(member.character());
        var body = sources.characters().bodyOf(member.character());
        var service = core.mobilization().flatMap(m -> m.activePerson(member.character())).map(r -> r.request());
        var work = WorkCommitmentRuntime.existing(server).flatMap(s -> s.holder(member.character()));
        if (identity == null || !identity.alive() || body == null || !body.entityId().equals(member.body())
                || !body.dimensionId().equals(post.station().dimension().toString())
                || service.filter(r -> r.id().equals(member.contract()) && r.unitId().equals(plan.unit())
                && r.armyId().equals(plan.army()) && r.role() == WarMilitiaRequest.Role.COMBATANT && core.servicePermits(r,now())
                && InitialWarDefinitions.LOADOUTS.find(r.loadout()).filter(l -> l.mount() == WarLoadout.Mount.FOOT).isPresent()).isEmpty()
                || work.filter(w -> w.intent().id().equals(member.contract()) && w.intent().body().equals(member.body())
                && w.phase() == WorkCommitment.Phase.ACTIVE).isEmpty()) return new Observation(Presence.INVALID,false);
        var level = level(post.station());
        if (level == null) return new Observation(Presence.UNKNOWN,false);
        var live = level.getEntity(member.body());
        if (live instanceof NpcBase npc && npc.getCharacterId().equals(member.character()) && !npc.isRemoved())
            return new Observation(Presence.LOADED,false);
        if (live != null) return new Observation(Presence.INVALID,false);
        if (level.getChunkSource().getChunkNow(post.station().x() >> 4, post.station().z() >> 4) != null)
            return new Observation(Presence.UNKNOWN,false);
        var health = sources.injuries().observeTriage(member.character());
        var needs = sources.life().needs(member.character());
        boolean fit = needs != null && needs.hunger() < 900 && needs.lastUpdatedAt() <= now()
                && !health.incapacitated() && !health.mortalDanger();
        return new Observation(Presence.UNLOADED,fit);
    }

    private net.minecraft.server.level.ServerLevel level(WarIntent.Position position) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(position.dimension().toString())));
    }

    private void put(WarFarReadiness plan) {
        var reply = store.persist(plan);
        if (reply.status() == DurableSnapshotStore.Status.UNKNOWN || reply.status() == DurableSnapshotStore.Status.UNAVAILABLE) {
            problem = "FAR_READINESS_REVIEW:" + reply.reason();
            return;
        }
        ready.add(plan.unit());
        surveys.remove(plan.unit());
    }

    private long now() { return server.overworld().getGameTime(); }

    @Override public void close() { store.close(); }
}
