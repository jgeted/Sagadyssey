package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.war.api.WarManagementInteraction.*;
import com.jgeted.sagadyssey.social.war.api.WarMapSheet;
import com.jgeted.sagadyssey.worldsim.transfer.ItemLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;

import static com.jgeted.sagadyssey.social.war.api.WarManagementInteraction.*;

/** Transient military conversations reached from the map or a real NPC. Saved effects use the existing runtimes. */
@EventBusSubscriber(modid = "sagadyssey")
public final class WarManagementRuntime {
    private static final int MAX_SESSIONS = 256;
    private static final long SESSION_TICKS = 2400;
    private static final Map<MinecraftServer, Map<UUID, Session>> SESSIONS = new HashMap<>();

    private WarManagementRuntime() {}

    public static Optional<View> open(ServerPlayer player) {
        return start(player, WarMusterPanel::armies);
    }

    public static Optional<View> interact(ServerPlayer player, NpcBase npc) {
        var existing = find(player);
        if (existing != null && existing.entityPick != null) {
            if (!existing.valid()) return expired(existing);
            if (!near(player, npc)) return Optional.empty();
            var action = existing.entityPick;
            return Optional.of(existing.run(() -> action.pick(npc)));
        }
        if (!near(player, npc) || !npc.isOwnedBy(player.getUUID())) return Optional.empty();
        return start(player, s -> WarMusterPanel.person(s, new Person(npc.getCharacterId(), npc.getUUID())));
    }

    /** Called only for a verified native block hit, before an ordinary map click can issue an order. */
    public static Optional<View> block(ServerPlayer player, BlockPos block, Direction face) {
        var s = find(player);
        if (s == null || s.blockPick == null) return Optional.empty();
        if (!s.valid()) return expired(s);
        var pick = s.blockPick;
        return Optional.of(s.run(() -> pick.pick(block, face)));
    }

    public static boolean awaitsBlock(ServerPlayer player) {
        var s = find(player);
        return s != null && s.blockPick != null;
    }

    public static void leave(ServerPlayer player) {
        var sessions = SESSIONS.get(player.level().getServer());
        if (sessions != null) sessions.remove(player.getUUID());
    }

    public static Optional<View> act(ServerPlayer player, Request request) {
        var s = find(player);
        if (s == null || !s.id.equals(request.session())) return Optional.empty();
        if (!s.valid()) return expired(s);
        if (request.control() == CLOSE) {
            leave(player);
            return Optional.of(s.transition(Display.CLOSED, ""));
        }
        if (request.revision() != s.view.revision()) return Optional.of(s.view);
        if (s.view.display() != Display.PANEL) return Optional.empty();
        if (request.control() == MAP_POINT && s.mapPick != null)
            return Optional.of(s.run(() -> s.mapPick.pick(request.x(), request.z())));
        if (request.control() < 0 || request.control() >= s.controls.size()) return Optional.empty();
        var control = s.controls.get(request.control());
        var values = request.values();
        if (values.size() != s.view.fields().size()) return Optional.empty();
        for (int i = 0; i < values.size(); i++)
            if (values.get(i).length() > s.view.fields().get(i).length()) return Optional.empty();
        return Optional.of(s.run(() -> control.action().run(values)));
    }

    private static Optional<View> start(ServerPlayer p, Open opener) {
        var server = p.level().getServer();
        if (!server.isSameThread()) throw new IllegalStateException("military panel requires the server thread");
        var sheet = WarMapRuntime.sheet(p);
        var actor = CharacterRegistry.currentId(p);
        if (actor == null || !p.isAlive() || p.isSpectator() || sheet.isEmpty()) return Optional.empty();
        var byPlayer = SESSIONS.computeIfAbsent(server, key -> new HashMap<>());
        byPlayer.values().removeIf(s -> s.expiresAt < s.now());
        if (byPlayer.size() >= MAX_SESSIONS && !byPlayer.containsKey(p.getUUID())) return Optional.empty();
        var s = new Session(p, actor, sheet.get());
        byPlayer.put(p.getUUID(), s);
        return Optional.of(s.run(() -> opener.open(s)));
    }

    private static Session find(ServerPlayer p) {
        var byPlayer = SESSIONS.get(p.level().getServer());
        var s = byPlayer == null ? null : byPlayer.get(p.getUUID());
        return s != null && s.player == p ? s : null;
    }

    private static Optional<View> expired(Session s) {
        leave(s.player);
        return Optional.of(s.transition(Display.CLOSED, "原地图、人物或会话已改变，请重新打开。"));
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) leave(p);
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent e) { SESSIONS.remove(e.getServer()); }

    static boolean near(ServerPlayer p, NpcBase npc) {
        return p.isAlive() && npc.isAlive() && p.level() == npc.level()
                && p.distanceToSqr(npc) <= 36 && p.hasLineOfSight(npc);
    }

    static int number(List<String> values, int index, int min, int max, String label) {
        try {
            int value = Integer.parseInt(values.get(index).trim());
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(label + "须为 " + min + " 至 " + max + " 的整数。");
    }

    @FunctionalInterface interface Open { void open(Session s) throws Exception; }
    @FunctionalInterface interface Step { void run() throws Exception; }
    @FunctionalInterface interface Action { void run(List<String> values) throws Exception; }
    @FunctionalInterface interface MapPick { void pick(int x, int z) throws Exception; }
    @FunctionalInterface interface BlockPick { void pick(BlockPos block, Direction face) throws Exception; }
    @FunctionalInterface interface EntityPick { void pick(NpcBase npc) throws Exception; }
    @FunctionalInterface interface Choice<T> { void choose(T value) throws Exception; }

    private record Control(String label, Action action) {}
    record Person(UUID character, UUID body) {
        NpcBase near(Session s) {
            var npc = ItemLocation.personal(character, body).body(s.server(), CharacterRegistryData.get(s.server()))
                    .filter(n -> WarManagementRuntime.near(s.player, n) && n.isOwnedBy(s.player.getUUID()));
            return npc.orElseThrow(() -> new IllegalArgumentException("请在六格内与可见、属于当前人物的原 NPC 交谈。"));
        }
    }

    static final class Session {
        final ServerPlayer player;
        final UUID actor;
        final WarMapSheet map;
        private final UUID id = UUID.randomUUID();
        private long revision;
        private long expiresAt;
        private View view;
        private List<Control> controls = List.of();
        private BlockPick blockPick;
        private EntityPick entityPick;
        private MapPick mapPick;
        private String message = "";

        private Session(ServerPlayer player, UUID actor, WarMapSheet map) {
            this.player = player;
            this.actor = actor;
            this.map = map;
            view = transition(Display.PANEL, "");
        }

        MinecraftServer server() { return player.level().getServer(); }
        long now() { return server().overworld().getGameTime(); }
        boolean valid() {
            return now() <= expiresAt && player.isAlive() && !player.isSpectator()
                    && actor.equals(CharacterRegistry.currentId(player))
                    && WarMapRuntime.sheet(player).filter(map::equals).isPresent();
        }

        Page page(String title) { return new Page(this, title); }
        void notice(String text) { message = shortText(text, 256); }

        void commandMap() {
            leave(player);
            view = transition(Display.COMMAND_MAP, "");
        }

        void pickBlock(String hint, BlockPick pick) {
            view = transition(Display.WORLD, hint);
            blockPick = pick;
        }

        void pickEntity(String hint, EntityPick pick) {
            view = transition(Display.WORLD, hint);
            entityPick = pick;
        }

        void confirm(String title, String detail, String label, Step apply, Step back) {
            page(title).line(detail).button(label, apply).button("返回", back).show();
        }

        private View run(Step step) {
            var before = view;
            try {
                step.run();
            } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException | IOException failure) {
                var reason = failure.getMessage() == null ? "当前操作不可用，请重新核对军役或物品。" : failure.getMessage();
                notice(reason);
                if (before.display() == Display.WORLD && view == before) {
                    view = new View(id, nextRevision(), Display.WORLD, "", shortText(takeNotice() + " " + before.message(), 256),
                            List.of(), List.of(), List.of(), Optional.empty());
                } else if (before.display() != Display.PANEL || before.controls().isEmpty()) {
                    page("军务").button("返回军令地图", this::commandMap).show();
                } else {
                    view = new View(id, nextRevision(), Display.PANEL, before.title(), takeNotice(), before.lines(),
                            before.fields(), before.controls(), before.map());
                }
            } catch (Exception failure) {
                throw new IllegalStateException("military panel action failed", failure);
            }
            return view;
        }

        private View transition(Display display, String text) {
            controls = List.of();
            blockPick = null;
            entityPick = null;
            mapPick = null;
            return new View(id, nextRevision(), display, "", shortText(text, 256),
                    List.of(), List.of(), List.of(), Optional.empty());
        }

        private long nextRevision() {
            expiresAt = now() + SESSION_TICKS;
            return ++revision;
        }

        private String takeNotice() {
            String current = message;
            message = "";
            return current;
        }
    }

    static final class Page {
        private final Session session;
        private final String title;
        private final List<String> lines = new ArrayList<>();
        private final List<Field> fields = new ArrayList<>();
        private final List<Control> actions = new ArrayList<>();
        private MapPick mapPick;

        private Page(Session session, String title) {
            this.session = session;
            this.title = shortText(title, 96);
        }

        Page line(String text) {
            if (lines.size() < MAX_LINES) lines.add(shortText(text, 256));
            return this;
        }

        Page lines(Collection<String> details) {
            details.forEach(this::line);
            return this;
        }

        Page field(String label, String initial, int length) {
            fields.add(new Field(label, initial, length));
            return this;
        }

        Page button(String label, Step step) { return action(label, ignored -> step.run()); }

        Page action(String label, Action action) {
            actions.add(new Control(shortText(label, 80), action));
            return this;
        }

        Page map(MapPick pick) { this.mapPick = pick; return this; }

        <T> Page choices(List<T> options, int page, Function<T, String> label, Choice<T> selected, Choice<Integer> switched) {
            int perPage = 8;
            int start = Math.clamp(page * perPage, 0, Math.max(0, (options.size() - 1) / perPage * perPage));
            for (T value : options.subList(start, Math.min(start + perPage, options.size()))) {
                button(label.apply(value), () -> selected.choose(value));
            }
            if (start > 0) button("上一页", () -> switched.choose(start / perPage - 1));
            if (start + perPage < options.size()) button("下一页", () -> switched.choose(start / perPage + 1));
            return this;
        }

        void show() {
            var map = Optional.ofNullable(mapPick).map(pick -> session.map);
            var view = new View(session.id, session.nextRevision(), Display.PANEL, title, session.takeNotice(),
                    lines, fields, actions.stream().map(Control::label).toList(), map);
            session.blockPick = null;
            session.entityPick = null;
            session.mapPick = mapPick;
            session.controls = List.copyOf(actions);
            session.view = view;
        }
    }

    private static String shortText(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}
