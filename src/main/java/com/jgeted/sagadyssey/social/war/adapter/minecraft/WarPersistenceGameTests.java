package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarCommandService;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.runtime.WarSession;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.event.server.*;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 独立战争命名空间、两个JVM正常停机重启；无NPC动员或战斗验收。 */
@GameTestHolder("sagadyssey_war")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = "sagadyssey")
public final class WarPersistenceGameTests {
    private static final UUID ACTOR = new UUID(0, 71), ARMY = new UUID(0, 72), UNIT = new UUID(0, 73);
    private static final UUID CREATE = new UUID(0, 74), FIRST = new UUID(0, 75), SECOND = new UUID(0, 76);
    private static final UUID THIRD = new UUID(0, 77), FOURTH = new UUID(0, 78), FIFTH = new UUID(0, 79);
    private static final Identifier EMPTY = Identifier.parse("sagadyssey_war:empty");
    private static final WarStateCodec CODEC = new WarStateCodec(WarStateCodec.Limits.defaults());
    private static WarState expectedFromPrior, terminal;
    private static WarSession terminalSession;
    private static boolean loadedBeforeTicks;
    private static long priorProcess;
    private static String phase() { return System.getProperty("sagadyssey.war.test.phase", ""); }
    private static boolean enabled() { return phase().equals("save") || phase().equals("resume"); }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }

    @GameTestGenerator public static Collection<TestFunction> tests() {
        if (!enabled()) return List.of();
        return List.of(new TestFunction("war_m1", "war_persistence_" + phase(), EMPTY.toString(), 600, 0, true,
                phase().equals("save") ? WarPersistenceGameTests::save : WarPersistenceGameTests::resume));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void started(ServerStartedEvent event) {
        if (!enabled() || !(event.getServer() instanceof GameTestServer)) return;
        try {
            var server = event.getServer(); var level = server.overworld();
            level.getStructureManager().getOrCreate(EMPTY).load(level.holderLookup(Registries.BLOCK),
                    TagParser.parseCompoundFully("{size:[4,3,4],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
            if (phase().equals("save")) {
                require(WarServerEvents.existing(server).isEmpty(), "unused world must not have a war runtime"); return;
            }
            Path root = server.getWorldPath(LevelResource.ROOT);
            var proof = JsonParser.parseString(Files.readString(root.resolve("war-m1-save-proof.json"))).getAsJsonObject();
            priorProcess = proof.get("process").getAsLong();
            require(priorProcess != ProcessHandle.current().pid(), "restart must use a different JVM");
            expectedFromPrior = ((WarStateCodec.Restored) CODEC.read(Files.readAllBytes(root.resolve("war-m1-save-checkpoint.sgw")))).state();
            var runtime = WarServerEvents.existing(server).orElseThrow();
            require(runtime.data().writable() && runtime.data().checkpoint().equals(expectedFromPrior), "exact disk state before first tick");
            require(runtime.session().orElseThrow().phase() == WarSession.Phase.PAUSED, "manual pause survives restart");
            // GameTestServer constructs fresh level metadata. Restore its own verified saved clock,
            // as the existing restart suites do; this is test-only, never product clock correction.
            server.getWorldData().overworldData().setGameTime(proof.get("savedGameTime").getAsLong());
            server.getWorldData().overworldData().setDayTime(proof.get("savedDayTime").getAsLong());
            loadedBeforeTicks = true;
        } catch (Exception exception) { throw new IllegalStateException("war restart pre-tick verification failed", exception); }
    }

    private static WarCommand.IssueOrder order(WarServerRuntime runtime, UUID id, int x) {
        var session = runtime.session().orElseThrow();
        return new WarCommand.IssueOrder(id, session.generation(), ACTOR, ARMY, UNIT,
                session.unit(UNIT).orElseThrow().revision(), new WarIntent(InitialWarDefinitions.MOVE, 1,
                new WarIntent.Position(WarKey.parse("minecraft:overworld"), x, 64, 0)));
    }
    private static void bind(WarServerRuntime runtime) {
        runtime.bindAuthority((actor, army) -> actor.equals(ACTOR) && army.equals(ARMY)
                ? WarCommandService.Permission.ALLOWED : WarCommandService.Permission.DENIED);
    }
    private static void applied(WarResult result) { require(result.status() == WarResult.Status.APPLIED, "expected application: " + result.status()); }

    private static void save(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); Path root = server.getWorldPath(LevelResource.ROOT);
        require(WarServerEvents.existing(server).isEmpty(), "ordinary ticks must not activate war");
        require(Files.notExists(root.resolve("data/" + WarSavedData.NAME + ".dat")), "unused world has no war file");
        require(Files.notExists(root.resolve("data/sagadyssey_war_archives")), "unused world has no archive directory");
        var runtime = WarServerEvents.activate(server);
        var create = new WarCommand.CreateUnit(CREATE, ACTOR, ARMY, UNIT, InitialWarDefinitions.TROOPS.getFirst(), 1);
        require(runtime.submit(create).status() == WarResult.Status.UNAVAILABLE, "missing authority is unavailable, never fake success");
        require(runtime.data().checkpoint().units().isEmpty(), "failed authorization creates no unit");
        bind(runtime); applied(runtime.submit(create));
        var first = order(runtime, FIRST, 1); applied(runtime.submit(first)); applied(runtime.deliver(UNIT, 0, FIRST));
        var second = order(runtime, SECOND, 2); applied(runtime.submit(second)); applied(runtime.deliver(UNIT, 0, SECOND));
        applied(runtime.submit(order(runtime, THIRD, 3)));
        require(runtime.session().orElseThrow().archive(), "history can be archived");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                runtime.session().orElseThrow().phase() == WarSession.Phase.ACTIVE, "wait for archive publication"))
                .thenExecute(() -> {
                    require(runtime.session().orElseThrow().generation() == 1, "published archive advances generation");
                    require(runtime.submit(first).status() == WarResult.Status.RETIRED_GENERATION, "retired request cannot execute again");
                    applied(runtime.submit(order(runtime, FOURTH, 4)));
                    runtime.pause(); capture(runtime);
                }).thenSucceed();
    }

    private static void resume(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); var runtime = WarServerEvents.existing(server).orElseThrow();
        require(loadedBeforeTicks && runtime.data().checkpoint().equals(expectedFromPrior), "startup state survived before resumed work");
        WarServerEvents.activate(server); bind(runtime);
        var fourth = runtime.data().checkpoint().operations().get(FOURTH).command();
        applied(runtime.deliver(UNIT, 1, FOURTH));
        require(runtime.deliver(UNIT, 0, THIRD).status() == WarResult.Status.STALE_ORDER, "older pending order cannot undo newer delivery");
        require(runtime.submit(fourth).status() == WarResult.Status.REPLAYED, "original acceptance survives real process restart");
        var fifth = order(runtime, FIFTH, 5); applied(runtime.submit(fifth)); applied(runtime.deliver(UNIT, 1, FIFTH));
        runtime.pause(); capture(runtime); helper.succeed();
    }

    private static void capture(WarServerRuntime runtime) {
        terminal = runtime.data().checkpoint(); terminalSession = runtime.session().orElseThrow();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void stopped(ServerStoppedEvent event) {
        if (!enabled() || terminal == null || !(event.getServer() instanceof GameTestServer)) return;
        try {
            var server = event.getServer(); Path root = server.getWorldPath(LevelResource.ROOT);
            require(WarServerEvents.existing(server).isEmpty(), "server scope must be released");
            require(terminalSession.phase() == WarSession.Phase.CLOSED, "session must be closed");

            var disk = WarSavedData.readDisk(root.resolve("data"), CODEC).orElseThrow();
            require(disk.writable() && !disk.enabled() && disk.checkpoint().equals(terminal), "final disk state and pause match final memory");
            try (var archive = new WarArchiveStore(root.resolve("data/sagadyssey_war_archives"), CODEC)) { archive.verify(disk.checkpoint()); }
            byte[] bytes = CODEC.write(terminal);
            Files.write(root.resolve("war-m1-" + phase() + "-checkpoint.sgw"), bytes, StandardOpenOption.CREATE_NEW);
            var level = NbtIo.readCompressed(root.resolve("level.dat"), NbtAccounter.create(64 * 1024 * 1024L)).getCompoundOrEmpty("Data");
            var proof = new JsonObject(); proof.addProperty("phase", phase()); proof.addProperty("process", ProcessHandle.current().pid());
            proof.addProperty("priorProcess", priorProcess); proof.addProperty("savedGameTime", level.getLongOr("Time", 0L));
            proof.addProperty("savedDayTime", level.getLongOr("DayTime", 0L)); proof.addProperty("generation", terminal.generation());
            proof.addProperty("currentSequence", terminal.units().get(UNIT).currentOrder().orElseThrow().sequence());
            proof.addProperty("pending", terminal.units().get(UNIT).pendingOrders().size());
            proof.addProperty("hotOperations", terminal.operations().size()); proof.addProperty("runtimeReleased", true);
            proof.addProperty("loadedBeforeTicks", loadedBeforeTicks); proof.addProperty("paused", true);
            proof.addProperty("checkpointSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            Files.writeString(root.resolve("war-m1-" + phase() + "-proof.json"), new GsonBuilder().setPrettyPrinting().create().toJson(proof), StandardOpenOption.CREATE_NEW);
            LoggerFactory.getLogger(WarPersistenceGameTests.class).info("WAR_M1_STOPPED {}", proof);
        } catch (Exception exception) { throw new IllegalStateException("war final stop verification failed", exception); }
    }
}
