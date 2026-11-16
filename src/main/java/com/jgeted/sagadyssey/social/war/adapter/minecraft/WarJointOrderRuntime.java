package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.politics.PoliticalRelation;
import com.jgeted.sagadyssey.social.politics.PoliticalStateData;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarJointOrderCodec;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarCommandService;
import com.jgeted.sagadyssey.social.war.application.WarDispatchService;
import com.jgeted.sagadyssey.social.war.domain.WarJointOrder;
import com.jgeted.sagadyssey.social.war.domain.WarJointOrder.*;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry;
import com.jgeted.sagadyssey.social.war.runtime.WarSession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Shared scheduling and signed intelligence scopes, separate from army ownership and NPC labor. */
@EventBusSubscriber(modid = "sagadyssey")
public final class WarJointOrderRuntime implements AutoCloseable {
    private static final String FILE = "sagadyssey_war_joint_order.sgw";
    private static final Map<MinecraftServer, WarJointOrderRuntime> STATES = new WeakHashMap<>();
    private static final Map<MinecraftServer, String> FAILURES = new WeakHashMap<>();
    private static final long START_WINDOW = 20;
    private static final long RECEIPT_WINDOW = 400;

    private final MinecraftServer server;
    private final DurableSnapshotStore<UUID, WarJointOrder> store;
    private final LinkedHashSet<UUID> pending = new LinkedHashSet<>();
    private final Set<UUID> recoveredFences = new HashSet<>();
    private final Map<UUID, List<Receipt>> sent = new HashMap<>();
    private String problem = "";

    private WarJointOrderRuntime(MinecraftServer server) throws IOException {
        this.server = server;
        store = new DurableSnapshotStore<>(file(server), new WarJointOrderCodec(), WarJointOrder::id,
                WarJointOrder::follows, "sagadyssey-war-joint-order");
        for (var plan : store.recover()) {
            if (plan.waiting()) pending.add(plan.id());
            if (plan.phase() == Phase.ISSUING) recoveredFences.add(plan.id());
        }
    }

    public static WarJointOrderRuntime open(MinecraftServer server) throws IOException {
        if (!server.isSameThread()) throw new IllegalStateException("joint order requires the server thread");
        var existing = STATES.get(server);
        if (existing != null) return existing;
        if (FAILURES.containsKey(server)) throw new IOException(FAILURES.get(server));
        try {
            var value = new WarJointOrderRuntime(server);
            STATES.put(server, value);
            return value;
        } catch (IOException | RuntimeException invalid) {
            FAILURES.put(server, "联合军令档需要核对 · " + invalid.getClass().getSimpleName());
            throw invalid;
        }
    }

    public static Optional<WarJointOrderRuntime> existing(MinecraftServer server) {
        return Optional.ofNullable(STATES.get(server));
    }

    public UUID prepare(ServerPlayer player, UUID partner, int waitTicks, int reportTicks) {
        var organiser = actor(player);
        if (waitTicks < 80 || waitTicks > 24000 || reportTicks < 20 || reportTicks > 24000)
            throw new IllegalArgumentException("发令等待应为80至24,000刻，军报共享为20至24,000刻");
        if (!allied(organiser, partner)) throw new IllegalArgumentException("需要当前盟友的人物身份");
        long start = Math.addExact(now(), waitTicks);
        var plan = WarJointOrder.draft(UUID.randomUUID(), organiser, partner, start, Math.addExact(start, reportTicks));
        save(plan);
        return plan.id();
    }

    public WarJointOrder select(ServerPlayer player, UUID id, String armyName, String unitName, WarMapView.Mode mode,
                               int x, int z, boolean shareReport) {
        var person = actor(player);
        var plan = owned(id, person);
        if (now() >= plan.executeAt()) throw new IllegalArgumentException("联合计划的预备时间已过");
        var core = core();
        var session = core.session().orElseThrow();
        UUID army = WarMusterEntry.armyId(person, armyName);
        UUID unit = WarMusterEntry.unitId(army, unitName);
        var commandUnit = session.unit(unit).filter(value -> value.armyId().equals(army))
                .orElseThrow(() -> new IllegalArgumentException("请指定本人现有的军队和兵团"));
        if (core.permission(person, army) != WarCommandService.Permission.ALLOWED)
            throw new IllegalArgumentException("当前人物无该兵团的指挥权");
        var map = WarMapRuntime.sheet(player).orElseThrow(() -> new IllegalArgumentException("请手持有效的原版地图"));
        var target = WarMapRuntime.target(player, map, x, z);
        var command = new WarCommand.IssueOrder(WarJointOrder.operationId(id, unit), session.generation(), person, army, unit,
                commandUnit.revision(), new WarIntent(WarMapRuntime.kind(mode), 1, target));
        return save(plan.add(new Instruction(command, map, x, z, shareReport)));
    }

    /** Signing includes the entire plan, including the scope set by each original commander. */
    public WarJointOrder sign(ServerPlayer player, UUID id, long revision) {
        var person = actor(player);
        var plan = owned(id, person);
        if (plan.signatures().contains(person) && plan.phase() == Phase.SCHEDULED) return plan;
        if (plan.revision() != revision || !isDurable(plan) || now() >= plan.executeAt() - START_WINDOW)
            throw new IllegalArgumentException("请核对最新已保存条款，并在发令前20刻完成签署");
        if (!allied(plan.organizer(), plan.partner())) throw new IllegalArgumentException("双方原联盟已失效");
        return save(plan.sign(person));
    }

    public WarJointOrder close(ServerPlayer player, UUID id) {
        var plan = owned(id, actor(player));
        if (plan.phase() == Phase.ISSUING && now() >= plan.executeAt())
            throw new IllegalArgumentException("发令窗口已开始，须先核对原军令结果");
        return save(plan.close());
    }

    public WarJointOrder view(ServerPlayer player, UUID id) { return owned(id, actor(player)); }

    public List<WarJointOrder> plans(ServerPlayer player) {
        var person = actor(player);
        return store.desiredValues().stream().filter(plan -> plan.involves(person))
                .sorted(Comparator.comparingLong(WarJointOrder::executeAt).reversed().thenComparing(WarJointOrder::id)).toList();
    }

    boolean offeredPartner(ServerPlayer player, UUID partner) {
        return allied(actor(player), partner);
    }

    public boolean isDurable(WarJointOrder plan) { return store.durable(plan.id()).filter(plan::equals).isPresent(); }

    public List<String> reports(ServerPlayer player, UUID id) {
        var person = actor(player);
        var plan = owned(id, person);
        if (!plan.sharesReports(now()) || !isDurable(plan) || !allied(plan.organizer(), plan.partner()))
            return List.of("军报共享当前未生效");
        var field = core().field().orElseThrow();
        var armies = new HashMap<UUID, List<WarFieldReport>>();
        var output = new ArrayList<String>();
        for (var instruction : plan.instructions()) {
            var command = instruction.command();
            if (!instruction.shareReport() || command.actorId().equals(person)) continue;
            var views = armies.computeIfAbsent(command.armyId(), army -> field.reports(command.actorId(), army));
            var observations = views.stream().filter(row -> row.unit().equals(command.unitId())
                    && now() >= row.observedAt() && now() - row.observedAt() <= 1200).toList();
            if (observations.isEmpty()) continue;
            var latest = observations.stream().max(Comparator.comparingLong(WarFieldReport::observedAt)
                    .thenComparing(WarFieldReport::character)).orElseThrow();
            var at = latest.position();
            output.add("盟军兵团 " + command.unitId() + " · 新近报告 " + observations.size()
                    + " · 最近 " + at.dimension() + " " + at.x() + " " + at.y() + " " + at.z()
                    + " · " + latest.state() + " · 观测刻 " + latest.observedAt());
        }
        return output.isEmpty() ? List.of("尚无授权兵团的新近军报") : List.copyOf(output);
    }

    private void tick() {
        if (!problem.isEmpty() || pending.isEmpty()) return;
        for (int step = Math.min(2, pending.size()); step > 0; step--) {
            var iterator = pending.iterator();
            UUID id = iterator.next();
            iterator.remove();
            var plan = store.desired(id).orElseThrow();
            try {
                if (!isDurable(plan)) save(plan);
                else if (plan.waiting()) advance(plan);
            } catch (RuntimeException failure) {
                problem = "JOINT_ORDERS_REQUIRE_REVIEW:" + failure.getClass().getSimpleName();
                org.slf4j.LoggerFactory.getLogger(WarJointOrderRuntime.class).error("联合军令暂停，保留原军令和条款", failure);
                return;
            }
            var current = store.desired(id).orElseThrow();
            if (current.waiting() || !isDurable(current)) pending.add(id);
            else recoveredFences.remove(id);
        }
    }

    private void advance(WarJointOrder plan) {
        long now = now();
        if (plan.phase() == Phase.SCHEDULED) {
            if (now > plan.executeAt() + START_WINDOW) save(plan.close());
            else if (now >= plan.executeAt() - 40) save(plan.fence());
            return;
        }
        var field = WarServerEvents.existing(server).flatMap(WarServerRuntime::field).orElse(null);
        if (field == null && now <= plan.executeAt() + RECEIPT_WINDOW) return;
        if (recoveredFences.remove(plan.id())) {
            save(plan.finish(plan.instructions().stream().map(leg -> recover(leg.command(), field)).toList()));
            return;
        }
        if (now < plan.executeAt()) return;
        var rows = sent.get(plan.id());
        if (rows == null) {
            boolean ready = field != null && now <= plan.executeAt() + START_WINDOW
                    && allied(plan.organizer(), plan.partner()) && plan.instructions().stream().allMatch(this::ready);
            rows = ready ? plan.instructions().stream().map(leg -> dispatch(field, leg.command())).toList()
                    : plan.instructions().stream().map(leg -> receipt(leg.command(), Outcome.REJECTED, -1, "PLAN_PRECONDITIONS_CHANGED")).toList();
            sent.put(plan.id(), rows);
        }
        if (verify(plan, field, rows)) {
            save(plan.finish(rows));
            sent.remove(plan.id());
        } else if (now > plan.executeAt() + RECEIPT_WINDOW) {
            save(plan.finish(plan.instructions().stream().map(leg -> recover(leg.command(), field)).toList()));
            sent.remove(plan.id());
        }
    }

    private boolean ready(Instruction leg) {
        var command = leg.command();
        var player = player(command.actorId());
        if (player == null || player.isSpectator()) return false;
        var core = core();
        var session = core.session().orElseThrow();
        if (session.phase() != WarSession.Phase.ACTIVE || session.generation() != command.generation()
                || core.permission(command.actorId(), command.armyId()) != WarCommandService.Permission.ALLOWED
                || session.unit(command.unitId()).filter(unit -> unit.armyId().equals(command.armyId())
                        && unit.revision() == command.expectedRevision()).isEmpty()) return false;
        try {
            return WarMapRuntime.target(player, leg.map(), leg.x(), leg.z()).equals(command.intent().target())
                    && core.field().orElseThrow().route(command.actorId(), command.armyId(), command.unitId()).isPresent();
        } catch (IllegalArgumentException changed) { return false; }
    }

    private static Receipt dispatch(WarFieldRuntime field, WarCommand.IssueOrder command) {
        var result = field.dispatch(command);
        return switch (result.outcome()) {
            case ACCEPTED, REPLAYED -> receipt(command, Outcome.ACCEPTED, result.record().orElseThrow().route().dueAt(), "");
            case REVIEW -> receipt(command, Outcome.UNKNOWN, -1, result.reason());
            default -> receipt(command, Outcome.REJECTED, -1, result.reason());
        };
    }

    private static boolean verify(WarJointOrder plan, WarFieldRuntime field, List<Receipt> receipts) {
        for (int i = 0; i < receipts.size(); i++) {
            if (receipts.get(i).outcome() != Outcome.ACCEPTED) continue;
            if (matching(plan.instructions().get(i).command(), field).filter(field::checkpointed).isEmpty()) return false;
        }
        return true;
    }

    private static Receipt recover(WarCommand.IssueOrder command, WarFieldRuntime field) {
        if (field == null) return receipt(command, Outcome.UNKNOWN, -1, "NO_ORIGINAL_DISPATCH_RECEIPT");
        return matching(command, field).filter(field::checkpointed)
                .map(value -> receipt(command, Outcome.ACCEPTED, value.route().dueAt(), "ORIGINAL_DISPATCH_VERIFIED"))
                .orElseGet(() -> receipt(command, Outcome.UNKNOWN, -1, "NO_ORIGINAL_DISPATCH_RECEIPT"));
    }

    private static Optional<com.jgeted.sagadyssey.social.war.domain.WarTransmission> matching(
            WarCommand.IssueOrder command, WarFieldRuntime field) {
        return field == null ? Optional.empty() : field.transmission(command.actorId(), command.armyId(), command.operationId())
                .filter(row -> row.command().equals(command));
    }

    private static Receipt receipt(WarCommand.IssueOrder command, Outcome result, long dueAt, String reason) {
        return new Receipt(command.operationId(), result, dueAt, reason.substring(0, Math.min(reason.length(), 256)));
    }

    private WarJointOrder owned(UUID id, UUID actor) {
        return store.desired(id).filter(plan -> plan.involves(actor))
                .orElseThrow(() -> new IllegalArgumentException("当前人物无法访问这份联合计划"));
    }

    private WarJointOrder save(WarJointOrder plan) {
        if (!problem.isEmpty()) throw new IllegalStateException(problem);
        var result = store.persist(plan);
        if (result.status() != DurableSnapshotStore.Status.APPLIED && result.status() != DurableSnapshotStore.Status.PENDING)
            throw new IllegalStateException(result.reason());
        if (plan.waiting() || result.status() == DurableSnapshotStore.Status.PENDING) pending.add(plan.id());
        return plan;
    }

    private boolean allied(UUID first, UUID second) {
        var registry = CharacterRegistryData.get(server);
        var a = registry.byId(first);
        var b = registry.byId(second);
        if (a == null || b == null || !a.alive() || !b.alive()
                || registry.accountForCharacter(first) == null || registry.accountForCharacter(second) == null) return false;
        if (first.equals(second)) return true;
        var politics = PoliticalStateData.get(server);
        return politics.relation(a.factionId(), b.factionId()) == PoliticalRelation.ALLIED
                && politics.relation(b.factionId(), a.factionId()) == PoliticalRelation.ALLIED;
    }

    private ServerPlayer player(UUID person) {
        var registry = CharacterRegistryData.get(server);
        var account = registry.accountForCharacter(person);
        var player = account == null ? null : server.getPlayerList().getPlayer(account);
        var active = player == null ? null : registry.current(player.getUUID());
        return active != null && active.id().equals(person) && player.isAlive() ? player : null;
    }

    private UUID actor(ServerPlayer player) {
        if (player.level().getServer() != server || player.isSpectator() || !player.isAlive())
            throw new IllegalArgumentException("当前领主身体不可用");
        return WarMusterRuntime.existing(server).orElseThrow(() -> new IllegalArgumentException("尚无原军队记录")).actor(player);
    }

    private WarServerRuntime core() {
        return WarServerEvents.existing(server).filter(value -> value.session().isPresent() && value.field().isPresent())
                .orElseThrow(() -> new IllegalArgumentException("军队运行尚不可用"));
    }

    private long now() { return server.overworld().getGameTime(); }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void started(ServerStartedEvent event) {
        if (Files.notExists(file(event.getServer()))) return;
        try { open(event.getServer()); }
        catch (IOException | RuntimeException invalid) {
            org.slf4j.LoggerFactory.getLogger(WarJointOrderRuntime.class).error("保留已有联合军令档，等待核对", invalid);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) { existing(event.getServer()).ifPresent(WarJointOrderRuntime::tick); }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        var value = STATES.remove(event.getServer());
        if (value != null) value.close();
        FAILURES.remove(event.getServer());
    }

    @Override public void close() {
        pending.clear();
        sent.clear();
        recoveredFences.clear();
        store.close();
    }
}
