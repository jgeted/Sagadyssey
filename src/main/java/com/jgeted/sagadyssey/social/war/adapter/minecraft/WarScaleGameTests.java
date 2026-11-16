package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.GsonBuilder;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.faction.FactionRegistry;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.combat.InjuryData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.politics.*;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarMapNetwork12111;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarDispatchService;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

/** Same original bodies before mobilization, on duty, and in actual contact. */
@GameTestHolder("sagadyssey_war_scale")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid="sagadyssey")
public final class WarScaleGameTests {
    private static final int SAMPLES = 320;
    private static final WarKey OVERWORLD = WarKey.parse("minecraft:overworld");
    private static final String[] NAMES = {"North", "South"};
    private static final String[] FACTIONS = {"sagadyssey:kingdom", "sagadyssey:wilderness"};
    private static Probe probe;

    private record Squad(int side, String name, UUID army, UUID unit, boolean bow, BlockPos station, List<NpcBase> soldiers) {}

    private static boolean enabled() { return Boolean.getBoolean("sagadyssey.war.scale.test"); }
    private static int people() { return Integer.getInteger("sagadyssey.war.scale.people", 32); }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) throws Exception {
        if (!enabled() || !(event.getServer() instanceof GameTestServer)) return;
        var level = event.getServer().overworld();
        level.setDayTime(1000);
        level.getGameRules().set(GameRules.ADVANCE_TIME, false, event.getServer());
        level.getGameRules().set(GameRules.SPAWN_MOBS, false, event.getServer());
        level.getStructureManager().getOrCreate(Identifier.parse("sagadyssey_war_scale:empty"))
                .load(level.holderLookup(Registries.BLOCK), TagParser.parseCompoundFully("{size:[1,1,1],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
    }

    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        return enabled() ? List.of(new TestFunction("war_scale", "scale_" + people(), "sagadyssey_war_scale:empty", 28000, 0, true, WarScaleGameTests::run)) : List.of();
    }

    private static void run(GameTestHelper h) {
        int count = people();
        if (count < 16 || count > 128 || count % 16 != 0) throw new IllegalArgumentException("expected a whole number of 8 vs 8 lanes");
        var level = h.getLevel();
        var server = level.getServer();
        int lanes = count / 16;
        var origin = h.absolutePos(BlockPos.ZERO).offset(3072, 12, 0);
        for (int cx = origin.getX() >> 4; cx <= (origin.getX() + lanes * 18 + 9) >> 4; cx++) {
            for (int cz = origin.getZ() >> 4; cz <= (origin.getZ() + 44) >> 4; cz++) level.setChunkForced(cx, cz, true);
        }
        for (int x = 0; x <= lanes * 18 + 9; x++) for (int z = 0; z <= 44; z++) {
            level.setBlockAndUpdate(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState());
        }
        var leaders = List.of(WarMusterGameTests.battlePlayer(h, "ScaleNorth", BlockPos.ZERO),
                WarMusterGameTests.battlePlayer(h, "ScaleSouth", BlockPos.ZERO));
        for (int side = 0; side < 2; side++) {
            var player = leaders.get(side);
            player.setPos(origin.offset(5, 1, side == 0 ? 3 : 40).getCenter());
            player.getInventory().setItem(3, new ItemStack(Items.EMERALD, 64));
        }
        var actors = leaders.stream().map(CharacterRegistry::currentId).toList();
        var squads = new ArrayList<Squad>();
        var all = new ArrayList<NpcBase>();
        var life = NpcLifeData.get(server);
        PoliticalStateData.get(server).setRelation(server, FACTIONS[0], FACTIONS[1], PoliticalRelation.NEUTRAL, level.getGameTime());
        boolean mixed = Boolean.getBoolean("sagadyssey.war.scale.mixed");
        for (int side = 0; side < 2; side++) {
            UUID army = WarMusterEntry.armyId(actors.get(side), NAMES[side]);
            for (int lane = 0; lane < lanes; lane++) {
                String name = "Unit" + lane;
                var soldiers = new ArrayList<NpcBase>();
                var station = origin.offset(9 + 18 * lane, 1, side == 0 ? 19 : 26);
                boolean bow = mixed && lane == lanes - 1;
                for (int i = 0; i < 8; i++) {
                    var soldier = h.spawn(NpcEntityTypes.NPC_BASE.get(), BlockPos.ZERO);
                    var at = station.offset((i % 4) * 3 - 4, 0, side == 0 ? -10 - (i / 4) * 5 : 8 + (i / 4) * 5);
                    soldier.teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
                    soldier.setPersistenceRequired();
                    soldier.setProfession(NpcProfession.WORKER);
                    soldier.setCommand(NpcCommand.STAY);
                    soldier.setFaction(FactionRegistry.get(FACTIONS[side]));
                    soldier.getAttribute(Attributes.MAX_HEALTH).setBaseValue(120);
                    soldier.setHealth(120);
                    for (var slot : EquipmentSlot.values()) soldier.setItemSlot(slot, ItemStack.EMPTY);
                    soldier.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(bow ? Items.BOW : Items.IRON_SWORD));
                    soldier.getPersonalInventory().clearContent();
                    soldier.getPersonalInventory().setItem(0, new ItemStack(Items.BREAD, 16));
                    if (bow) {
                        soldier.getPersonalInventory().setItem(1, new ItemStack(Items.ARROW, 64));
                        soldier.getPersonalInventory().setItem(2, new ItemStack(Items.IRON_SWORD));
                    }
                    life.onRecruited(soldier, actors.get(side), level.getGameTime());
                    life.setHunger(soldier.getCharacterId(), 100, level.getGameTime());
                    soldiers.add(soldier);
                    all.add(soldier);
                }
                squads.add(new Squad(side, name, army, WarMusterEntry.unitId(army, name), bow, station, List.copyOf(soldiers)));
            }
        }
        var contracts = new ArrayList<UUID>();
        var orders = new ArrayList<UUID>();
        var evidence = new LinkedHashMap<String,Object>();
        var ids = all.stream().map(NpcBase::getCharacterId).collect(Collectors.toUnmodifiableSet());
        var hp = all.stream().map(NpcBase::getHealth).toList();
        var q = h.startSequence().thenIdle(120).thenExecute(() -> begin(server))
                .thenWaitUntil(() -> h.assertTrue(probe.complete(), "civilian baseline samples complete"))
                .thenExecute(() -> {
                    evidence.put("baseline", finish());
                    h.assertTrue(WarServerEvents.existing(server).isEmpty(), "no war state during baseline");
                    for (int side = 0; side < 2; side++) command(h, leaders.get(side), "saga war muster create " + NAMES[side]);
                })
                .thenWaitUntil(() -> h.assertTrue(squads.stream().allMatch(s -> muster(server).book().owns(actors.get(s.side()), s.army())), "two durable original armies"))
                .thenExecute(() -> squads.forEach(s -> command(h, leaders.get(s.side()), "saga war muster unit " + NAMES[s.side()] + " " + s.name() + (s.bow() ? " bow" : " sword"))))
                .thenWaitUntil(() -> h.assertTrue(squads.stream().allMatch(s -> muster(server).book().unit(s.unit())
                        .filter(u -> u.recorded() && muster(server).book().durable(u)).isPresent()), "all original units recorded"));
        for (int lane = 0; lane < lanes; lane++) {
            var north = squads.get(lane);
            var south = squads.get(lanes + lane);
            for (int i = 0; i < 8; i++) {
                int member = i, prior = (lane * 8 + i) * 2;
                q.thenExecute(() -> {
                    contracts.add(WarMusterGameTests.battleOffer(h, leaders.getFirst(), north.soldiers().get(member), NAMES[0], north.name()));
                    contracts.add(WarMusterGameTests.battleOffer(h, leaders.getLast(), south.soldiers().get(member), NAMES[1], south.name()));
                }).thenWaitUntil(() -> h.assertTrue(contracts.size() >= prior + 2
                        && activated(server,contracts.get(prior)) && activated(server,contracts.get(prior+1)),
                        "both principals remain within the original six-block handoff range for pair " + prior / 2));
            }
        }
        q.thenWaitUntil(() -> h.assertTrue(contracts.size() == count && contracts.stream().allMatch(id -> muster(server).book().offer(id)
                        .filter(o -> o.stage() == WarMusterEntry.Stage.ACTIVE && muster(server).book().durable(o)).isPresent()), "same actual original roster activated"))
                .thenExecute(() -> {
                    core(server).telemetry().enabled(true);
                    for (var s : squads) orders.add(order(h, core(server), leaders.get(s.side()), s, InitialWarDefinitions.MOVE, s.station()));
                })
                .thenWaitUntil(() -> h.assertTrue(squads.stream().allMatch(s -> holding(server, s, actors.get(s.side()))), "each original body physically arrives by command navigation"))
                .thenExecute(() -> begin(server))
                .thenWaitUntil(() -> h.assertTrue(probe.complete(), "armed peacetime samples complete"))
                .thenExecute(() -> {
                    evidence.put("holding", finish());
                    evidence.put("mapPayloadBytes", mapBytes(leaders.getFirst(), squads.getFirst().station()));
                    for (int side=0;side<2;side++) leaders.get(side).setPos(origin.offset(4,1,side==0?4:41).getCenter());
                    evidence.put("beforeCombatBudget", field(server).budgets());
                    PoliticalStateData.get(server).setRelation(server, FACTIONS[0], FACTIONS[1], PoliticalRelation.WAR, level.getGameTime());
                    begin(server);
                })
                .thenWaitUntil(() -> h.assertTrue(probe.complete(), "live contact samples complete"))
                .thenExecute(() -> {
                    evidence.put("contact", finish());
                    var wounds = InjuryData.get(server).wounds().stream().filter(w -> w.attackerCharacterId() != null && ids.contains(w.attackerCharacterId()) && ids.contains(w.patientCharacterId())).toList();
                    var touched = new HashSet<UUID>();
                    for (var wound : wounds) {
                        touched.add(wound.attackerCharacterId());
                        touched.add(wound.patientCharacterId());
                    }
                    long changedHp = 0;
                    for (int i=0;i<all.size();i++) if (all.get(i).getHealth() < hp.get(i)) changedHp++;
                    h.assertTrue(wounds.size() >= lanes && touched.size() >= Math.max(4, lanes * 2) && changedHp >= lanes,
                            "native contact made original wounds and changed original health: wounds=" + wounds.size() + ", participants=" + touched.size());
                    evidence.put("wounds", wounds.size());
                    evidence.put("contactParticipants", touched.size());
                    evidence.put("actualHealthDecreases", changedHp);
                    evidence.put("afterCombatBudget", field(server).budgets());
                    evidence.put("bodyIds", all.stream().map(NpcBase::getUUID).toList());
                    evidence.put("characterIds", ids);
                    evidence.put("orderIds", orders);
                    evidence.put("contracts", contracts);
                    evidence.put("people", count);
                    evidence.put("units", squads.size());
                    evidence.put("mixed", mixed);
                    evidence.put("process", ProcessHandle.current().pid());
                    evidence.put("physicalProcessorsVisible", Runtime.getRuntime().availableProcessors());
                    evidence.put("jvm", System.getProperty("java.version"));
                    evidence.put("heapLimitBytes", Runtime.getRuntime().maxMemory());
                    core(server).telemetry().enabled(false);
                    write(server, evidence);
                }).thenSucceed();
    }

    static UUID order(GameTestHelper h, WarServerRuntime core, ServerPlayer player, UUID army, UUID unit, WarKey kind, BlockPos at) {
        var session = core.session().orElseThrow();
        var command = new WarCommand.IssueOrder(UUID.randomUUID(), session.generation(), CharacterRegistry.currentId(player), army, unit,
                session.unit(unit).orElseThrow().revision(), new WarIntent(kind, 1, new WarIntent.Position(OVERWORLD, at.getX(), at.getY(), at.getZ())));
        var r = core.field().orElseThrow().dispatch(command);
        h.assertTrue(r.outcome() == WarDispatchService.Outcome.ACCEPTED, "ordinary field dispatch accepted: " + r);
        return command.operationId();
    }

    private static UUID order(GameTestHelper h, WarServerRuntime core, ServerPlayer player, Squad s, WarKey kind, BlockPos at) {
        return order(h,core,player,s.army(),s.unit(),kind,at);
    }

    private static boolean holding(MinecraftServer server, Squad s, UUID actor) {
        var rows = field(server).reports(actor, s.army()).stream().filter(r -> r.unit().equals(s.unit())).toList();
        return rows.size() == s.soldiers().size() && rows.stream().allMatch(r -> r.state() == WarFieldReport.State.HOLDING);
    }

    private static int mapBytes(ServerPlayer player, BlockPos at) {
        var level = player.level();
        var stack = MapItem.create(level, at.getX(), at.getZ(), (byte)0,true,true);
        player.setItemSlot(EquipmentSlot.MAINHAND,stack);
        Arrays.fill(MapItem.getSavedData(stack,level).colors,(byte)40);
        var update = WarMapRuntime.get(level.getServer()).begin(player);
        var buffer = Unpooled.buffer();
        try {
            WarMapNetwork12111.UpdatePayload.CODEC.encode(buffer,new WarMapNetwork12111.UpdatePayload(update));
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    private static void write(MinecraftServer server, Map<String, Object> evidence) {
        try {
            var path = server.getWorldPath(LevelResource.ROOT).resolve("war-scale.json");
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(evidence));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static WarServerRuntime core(MinecraftServer s) { return WarServerEvents.existing(s).orElseThrow(); }
    private static WarFieldRuntime field(MinecraftServer s) { return core(s).field().orElseThrow(); }
    private static WarMusterRuntime muster(MinecraftServer s) { return WarMusterRuntime.existing(s).orElseThrow(); }
    private static boolean activated(MinecraftServer s, UUID request) {
        return muster(s).book().offer(request).filter(o->o.stage()==WarMusterEntry.Stage.ACTIVE && muster(s).book().durable(o)).isPresent();
    }
    static void command(GameTestHelper h, ServerPlayer p, String command) {
        h.assertTrue(WarMusterGameTests.command(p,command) == 1, "ordinary command " + command + " " + WarMusterGameTests.feedback(p));
    }

    private static void begin(MinecraftServer server) { probe = new Probe(server); }
    private static Map<String,Object> finish() {
        var result = probe.result();
        probe = null;
        return result;
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void pre(ServerTickEvent.Pre event) {
        if (probe != null && probe.server == event.getServer()) probe.start = System.nanoTime();
    }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void post(ServerTickEvent.Post event) {
        if (probe != null && probe.server == event.getServer()) probe.record();
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        if (probe != null && probe.server == event.getServer()) probe = null;
    }

    private static final class Probe {
        final MinecraftServer server;
        final long[] world = new long[SAMPLES], nativeLoop = new long[SAMPLES], war = new long[SAMPLES];
        final long heapStart = heap();
        final com.sun.management.ThreadMXBean allocation;
        final long thread = Thread.currentThread().threadId();
        final long bytesStart;
        int n, maximumBodies;
        long start;

        Probe(MinecraftServer server) {
            this.server = server;
            var bean = ManagementFactory.getThreadMXBean();
            allocation = bean instanceof com.sun.management.ThreadMXBean a && a.isThreadAllocatedMemoryEnabled() ? a : null;
            bytesStart = bytes();
        }

        void record() {
            if (complete() || start == 0) return;
            long tick = server.overworld().getGameTime();
            var state = WarServerEvents.existing(server);
            if (state.isPresent()) {
                var sample = state.get().telemetry().sample(tick);
                war[n] = sample.fieldNanos() + sample.coordinatorNanos();
                maximumBodies = Math.max(maximumBodies, sample.bodies());
            }
            nativeLoop[n] = server.getTickTimesNanos()[server.getTickCount() % 100];
            world[n] = Math.max(1, System.nanoTime() - start);
            n++;
            start = 0;
        }

        boolean complete() { return n == SAMPLES; }
        private long bytes() { return allocation == null ? -1 : allocation.getThreadAllocatedBytes(thread); }
        static long heap() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
        private static Map<String,Long> summarize(long[] values) {
            long[] sorted = values.clone();
            Arrays.sort(sorted);
            return Map.of("p50",sorted[(SAMPLES+1)/2-1],"p95",sorted[(int)Math.ceil(SAMPLES*.95)-1],"max",sorted[SAMPLES-1]);
        }
        Map<String,Object> result() {
            if (!complete()) throw new IllegalStateException("unfinished tick capture");
            return Map.of("samples",n,"tickNanos",summarize(world),"nativeLoopNanos",summarize(nativeLoop),
                    "fieldAndCoordinatorNanos",summarize(war),"maximumBodiesInFieldCallback",maximumBodies,
                    "heapStartBytes",heapStart,"heapEndBytes",heap(),"mainThreadAllocatedBytes",bytesStart < 0 ? -1 : bytes() - bytesStart);
        }
    }
}
