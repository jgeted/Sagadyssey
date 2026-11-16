package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.identity.*;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts;
import com.jgeted.sagadyssey.social.work.WorkCommitmentRuntime;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.server.*;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import java.nio.file.*;
import java.util.*;

/** 显式启用的原生近场验收。只有敌方靶子和命令签发夹具禁用AI，原民兵保持原AI及职业。 */
@GameTestHolder("sagadyssey_war_m3")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = "sagadyssey")
public final class WarFieldGameTests {
    private static final String TEMPLATE = "sagadyssey_war_m3:empty";
    private static final Map<UUID, List<Long>> attempts = new HashMap<>();
    private static final Map<UUID, Integer> damage = new HashMap<>();
    private static final Set<UUID> soldiers = new HashSet<>();
    private static UUID enemy;
    private static JsonObject capture;
    private static int offset;
    private static boolean enabled() { return Boolean.getBoolean("sagadyssey.war.m3.test"); }
    private static String phase() { return System.getProperty("sagadyssey.war.m3.phase", "single"); }
    private static BlockPos scene(BlockPos pos) { return pos.offset(offset, 0, 0); }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void started(ServerStartedEvent event) {
        if (!enabled() || !(event.getServer() instanceof GameTestServer)) return;
        attempts.clear(); damage.clear(); soldiers.clear(); enemy = null; capture = null;
        try {
            var level = event.getServer().overworld();
            if (phase().equals("resume")) {
                var proof = JsonParser.parseString(Files.readString(event.getServer().getWorldPath(LevelResource.ROOT).resolve("war-m3-save-proof.json"))).getAsJsonObject();
                if (proof.get("process").getAsLong() == ProcessHandle.current().pid()) throw new IllegalStateException("resume must use another JVM");
                // GameTestServer重建关卡元数据；仅测试恢复上次实际写入level.dat的世界时钟。
                event.getServer().getWorldData().overworldData().setGameTime(proof.get("gameTime").getAsLong());
                event.getServer().getWorldData().overworldData().setDayTime(proof.get("dayTime").getAsLong());
            }
            level.getStructureManager().getOrCreate(Identifier.parse(TEMPLATE)).load(level.holderLookup(Registries.BLOCK),
                    TagParser.parseCompoundFully("{size:[32,8,32],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
        } catch (Exception exception) { throw new IllegalStateException("M3 fixture template", exception); }
    }
    @GameTestGenerator public static Collection<TestFunction> tests() {
        return enabled() ? List.of(new TestFunction("war_m3", "original_people_field_" + phase(), TEMPLATE, 3600, 0, true,
                phase().equals("resume") ? WarFieldGameTests::resume : WarFieldGameTests::lifecycle)) : List.of();
    }
    @SubscribeEvent public static void incoming(LivingIncomingDamageEvent event) {
        if (!enabled() || !(event.getSource().getEntity() instanceof NpcBase npc) || !soldiers.contains(npc.getUUID())) return;
        attempts.computeIfAbsent(npc.getUUID(), ignored -> new ArrayList<>()).add(npc.level().getGameTime());
        if (!event.getEntity().getUUID().equals(enemy)) throw new IllegalStateException("militia attacked an ally or unclassified body");
    }
    @SubscribeEvent public static void damaged(LivingDamageEvent.Post event) {
        if (enabled() && event.getSource().getEntity() instanceof NpcBase npc && soldiers.contains(npc.getUUID()) && event.getNewDamage() > 0)
            damage.merge(npc.getUUID(), 1, Integer::sum);
    }
    private static void lifecycle(GameTestHelper helper) {
        int count = Integer.getInteger("sagadyssey.war.m3.people", 3); if (count != 3 && count != 10) throw new IllegalArgumentException("M3 fixture count");
        offset = phase().equals("save") ? 2048 : 0;
        var level = helper.getLevel(); var server = level.getServer();
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            var pos = helper.absolutePos(scene(new BlockPos(x, 0, z))); level.getChunkAt(pos);
            if (offset != 0) level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
            helper.setBlock(scene(new BlockPos(x, 0, z)), Blocks.STONE);
        }
        var commander = dummy(helper, new BlockPos(1, 1, 18));
        var f = WarCandidateGameTests.fixture(helper, count, commander.getCharacterId());
        var originalModes = f.npcs().stream().map(NpcBrainRuntime::mode).toList();
        var originalProfessions = f.npcs().stream().map(NpcBase::getProfession).toList();
        for (var npc : f.npcs()) {
            if (offset != 0) { var pos = helper.absolutePos(scene(new BlockPos(1 + f.npcs().indexOf(npc), 1, 1))); npc.teleportTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5); }
            npc.setNoGravity(false); npc.setOnGround(true); npc.setCommand(NpcCommand.STAY); npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            npc.getPersonalInventory().setItem(0, new ItemStack(Items.BREAD, 8)); npc.getPersonalInventory().setItem(1, new ItemStack(Items.IRON_PICKAXE)); soldiers.add(npc.getUUID());
            f.sources().life().setHunger(npc.getCharacterId(), 100, server.overworld().getGameTime());
        }
        var first = f.requests().getFirst(); var loadout = new WarLoadout(WarLoadout.Mount.FOOT, WarLoadout.Weapon.SWORD, false, true).id();
        var requests = f.requests().stream().map(r -> new WarMilitiaRequest(r.id(), r.actorId(), r.armyId(), r.unitId(), r.characterId(), loadout, 1, r.kind(), r.role(), r.wage(), r.requestedAtTick())).toList();
        boolean[] authorized = {true}; var runtime = WarServerEvents.activate(server);
        runtime.bindAuthority((actor, army) -> authorized[0] && actor.equals(first.actorId()) && army.equals(first.armyId()) ? WarCommandService.Permission.ALLOWED : WarCommandService.Permission.DENIED);
        helper.assertTrue(runtime.submit(new WarCommand.CreateUnit(UUID.randomUUID(), first.actorId(), first.armyId(), first.unitId(), loadout, 1)).status() == WarResult.Status.APPLIED, "actual unit created");
        server.overworld().getDataStorage().saveAndJoin();
        helper.setBlock(scene(new BlockPos(2, 1, 22)), Blocks.CHEST); helper.setBlock(scene(new BlockPos(5, 1, 22)), Blocks.CHEST);
        var armoryBlock = (ChestBlockEntity)level.getBlockEntity(helper.absolutePos(scene(new BlockPos(2, 1, 22))));
        var treasuryBlock = (ChestBlockEntity)level.getBlockEntity(helper.absolutePos(scene(new BlockPos(5, 1, 22))));
        var armory = ItemLocation.block(UUID.randomUUID(), level.dimension().identifier().toString(), armoryBlock.getBlockPos());
        var treasury = ItemLocation.block(UUID.randomUUID(), level.dimension().identifier().toString(), treasuryBlock.getBlockPos()); armory.bindBlock(server); treasury.bindBlock(server);
        for (int i = 0; i < count; i++) { armoryBlock.setItem(i * 2, new ItemStack(Items.IRON_SWORD)); armoryBlock.setItem(i * 2 + 1, new ItemStack(Items.IRON_CHESTPLATE)); }
        treasuryBlock.setItem(0, new ItemStack(Items.EMERALD, 64)); var rights = RightsStateData.get(server); var owner = SubjectRef.character(first.actorId());
        rights.ensureContext(armory.id(), "test:armory", owner, owner, AccessPolicy.PRIVATE); rights.ensureContext(treasury.id(), "test:treasury", owner, owner, AccessPolicy.PRIVATE);
        for (var npc : f.npcs()) {
            var person = SubjectRef.character(npc.getCharacterId());
            rights.ensureContext(ItemLocation.personal(npc.getCharacterId(), npc.getUUID()).id(), "test:bag", person, person, AccessPolicy.PRIVATE);
            rights.ensureContext(ItemLocation.equipment(npc.getCharacterId(), npc.getUUID()).id(), "test:equipment", person, person, AccessPolicy.PRIVATE);
        }
        final WarNativeMobilization nativePorts;
        try { nativePorts = runtime.bindNativeMobilization(f.sources()); } catch (Exception e) { throw new IllegalStateException(e); }
        var military = runtime.mobilization().orElseThrow();
        var field = runtime.bindFieldExecution(new WarFieldRuntime.Sources(f.sources().characters(), f.sources().injuries(), CombatEncounterData.get(server), f.sources().life()));
        var originalActor = f.npcs().getFirst(); var stubTarget = f.npcs().get(1);
        float targetHealth = stubTarget.getHealth(); var actorPosition = originalActor.position(); var issuedWeapon = originalActor.getMainHandItem().copy();
        for (var kind : List.of(WarAttackKind.SPEAR_JAB, WarAttackKind.SPEAR_CHARGE, WarAttackKind.MOUNTED_SPEAR_CHARGE)) {
            var unsupported = WarNativeBindings.currentBuild().actors().attack(originalActor, stubTarget, kind);
            helper.assertTrue(unsupported.status() == com.jgeted.sagadyssey.social.war.spi.WarFieldPorts.AttackStatus.BLOCKED
                    && stubTarget.getHealth() == targetHealth && originalActor.position().equals(actorPosition)
                    && ItemStack.matches(originalActor.getMainHandItem(), issuedWeapon) && originalActor.getTarget() == null, "spear actions without the actual spear have no native effect");
        }
        var kit = List.of(new WarKitRecord.Line(ItemFingerprint.of(new ItemStack(Items.IRON_SWORD)), 1), new WarKitRecord.Line(ItemFingerprint.of(new ItemStack(Items.IRON_CHESTPLATE)), 1));
        var orders = new ArrayList<UUID>(); var bodies = f.npcs().stream().map(NpcBase::getUUID).toList();
        int[] beforeOutside = {0}; NpcBase[] foe = {null};
        var sequence = helper.startSequence().thenWaitUntil(() -> {
            boolean ready = true; for (var request : requests) ready &= nativePorts.configure(request, treasury, armory, kit) == WarMobilizationPorts.Status.APPLIED;
            helper.assertTrue(ready, "wait for durable physical supply plans");
            for (var request : requests) helper.assertTrue(military.preview(request).ready(), "real preflight: " + military.preview(request));
        }).thenExecute(() -> {
            for (var request : requests) helper.assertTrue(military.begin(request).outcome() == WarMobilizationService.Outcome.ACCEPTED, "mobilize original person");
        }).thenWaitUntil(() -> {
            for (var request : requests) helper.assertTrue(military.service().orElseThrow().find(request.id()).orElseThrow().stage() == WarMobilization.Stage.ACTIVE, "wait for real active contract");
            helper.assertTrue(field.reports(first.actorId(), first.armyId()).size() == count, "reports come from the real original bodies");
        }).thenExecute(() -> {
            orders.add(issue(helper, runtime, field, first, InitialWarDefinitions.MOVE, new BlockPos(12, 1, 3)));
            helper.assertTrue(field.transmission(first.actorId(), first.armyId(), orders.getLast()).orElseThrow().received().isEmpty(), "accepted dispatch is still in transit");
            for (var report : field.reports(first.actorId(), first.armyId())) helper.assertTrue(report.order().isEmpty(), "unreceived order cannot drive the body");
        }).thenWaitUntil(() -> atStations(helper, field, first, f.npcs(), orders.getLast())).thenExecute(() -> {
            UUID previous = orders.getLast(); orders.add(issue(helper, runtime, field, first, InitialWarDefinitions.HOLD, new BlockPos(12, 1, 13)));
            for (var report : field.reports(first.actorId(), first.armyId())) helper.assertTrue(report.order().orElseThrow().equals(previous), "previous received order persists while new order is in transit");
        }).thenWaitUntil(() -> atStations(helper, field, first, f.npcs(), orders.getLast())).thenExecute(() -> {
            orders.add(issue(helper, runtime, field, first, InitialWarDefinitions.HOLD, new BlockPos(12, 5, 13)));
        }).thenWaitUntil(() -> {
            var reports = field.reports(first.actorId(), first.armyId());
            helper.assertTrue(reports.size() == count && reports.stream().allMatch(r -> r.order().filter(orders.getLast()::equals).isPresent() && r.state() == WarFieldReport.State.BLOCKED), "unsupported destination must report actual blockage: " + reports);
            for (var npc : f.npcs()) helper.assertTrue(npc.getY() < helper.absolutePos(scene(new BlockPos(12, 3, 13))).getY(), "no teleport to unsupported order height");
        }).thenExecute(() -> orders.add(issue(helper, runtime, field, first, InitialWarDefinitions.HOLD, new BlockPos(12, 1, 13))))
                .thenWaitUntil(() -> atStations(helper, field, first, f.npcs(), orders.getLast())).thenExecute(() -> {
            foe[0] = dummy(helper, new BlockPos(12, 1, 15)); foe[0].getAttribute(Attributes.MAX_HEALTH).setBaseValue(200); foe[0].setHealth(200); enemy = foe[0].getUUID();
            var ally = dummy(helper, new BlockPos(13, 1, 14)); dummy(helper, new BlockPos(11, 1, 14));
            var friendly = new HashSet<>(f.npcs().stream().map(NpcBase::getCharacterId).toList()); friendly.add(ally.getCharacterId());
            CombatEncounterService.start(CombatEncounterData.get(server), UUID.randomUUID(), null, level.dimension().identifier().toString(), helper.absolutePos(scene(new BlockPos(12, 1, 13))), 24,
                    List.of(new CombatSide(UUID.randomUUID(), "mobilized originals", null, friendly, EngagementRule.LETHAL_FORCE),
                            new CombatSide(UUID.randomUUID(), "known hostile fixture", null, Set.of(foe[0].getCharacterId()), EngagementRule.LETHAL_FORCE)), server.overworld().getGameTime());
        }).thenWaitUntil(() -> helper.assertTrue(!damage.isEmpty() && f.sources().injuries().wounds().stream().anyMatch(w -> w.patientCharacterId().equals(foe[0].getCharacterId())), "native hits create native injury evidence"))
                .thenIdle(65).thenExecute(() -> {
            for (var times : attempts.values()) for (int i = 1; i < times.size(); i++) helper.assertTrue(times.get(i) - times.get(i - 1) >= 20, "one native melee attempt per body per 20 ticks; no legacy double attack");
            helper.assertTrue(foe[0].getHealth() < 200, "actual native health damage"); beforeOutside[0] = attempts.values().stream().mapToInt(List::size).sum();
            var far = helper.absolutePos(scene(new BlockPos(28, 1, 28))); foe[0].teleportTo(far.getX() + .5, far.getY(), far.getZ() + .5);
        }).thenIdle(50).thenExecute(() -> {
            helper.assertTrue(attempts.values().stream().mapToInt(List::size).sum() == beforeOutside[0], "target beyond station bounds is not chased or hit");
        }).thenWaitUntil(() -> atStations(helper, field, first, f.npcs(), orders.getLast())).thenExecute(() -> {
            authorized[0] = false;
            helper.assertTrue(field.dispatch(command(helper, runtime, first, InitialWarDefinitions.MOVE, new BlockPos(22, 1, 3))).outcome() == WarDispatchService.Outcome.DENIED, "current authority required");
        }).thenIdle(10).thenExecute(() -> {
            for (var npc : f.npcs()) helper.assertTrue(npc.getNavigation().isDone() && npc.getTarget() == null, "revoked authority stops movement and combat"); authorized[0] = true;
        });
        if (phase().equals("save")) {
            sequence.thenWaitUntil(() -> atStations(helper, field, first, f.npcs(), orders.getLast())).thenExecute(() -> {
                capture = new JsonObject(); capture.addProperty("people", count); capture.addProperty("orders", orders.size()); capture.addProperty("stage", "ACTIVE");
                capture.addProperty("scene", helper.absolutePos(scene(BlockPos.ZERO)).asLong()); capture.addProperty("order", orders.getLast().toString());
                capture.addProperty("nativeDamageEvents", damage.values().stream().mapToInt(Integer::intValue).sum()); capture.addProperty("nativeAttackAttempts", beforeOutside[0]);
                capture.add("budgets", new Gson().toJsonTree(field.budgets())); capture.add("originalBodies", new Gson().toJsonTree(bodies));
                capture.add("originalModes", new Gson().toJsonTree(originalModes)); capture.add("originalProfessions", new Gson().toJsonTree(originalProfessions));
                capture.add("bodyStamps", new Gson().toJsonTree(f.npcs().stream().map(n -> n.getPersistentData().getCompoundOrEmpty("SagadysseyFieldControlV1").toString()).toList()));
                helper.assertTrue(nativePorts.reconcile(military.service().orElseThrow().records()).eligible(), "save active original service reconciles");
                server.overworld().getDataStorage().saveAndJoin();
            }).thenSucceed(); return;
        }
        sequence.thenExecute(() -> {
            for (var request : requests) military.demobilize(request.id(), first.actorId());
        }).thenWaitUntil(() -> {
            for (var request : requests) helper.assertTrue(military.service().orElseThrow().find(request.id()).orElseThrow().stage() == WarMobilization.Stage.RELEASED, "release field control and borrowed actual kit");
        }).thenExecute(() -> {
            for (int i = 0; i < count; i++) {
                var npc = f.npcs().get(i); helper.assertTrue(npc.getUUID().equals(bodies.get(i)) && !npc.isNoAi() && npc.getProfession() == originalProfessions.get(i)
                        && NpcBrainRuntime.mode(npc) == originalModes.get(i) && !NpcPrimaryWorkRuntime.reserved(npc) && !WorkCommitmentRuntime.blocksCivil(npc), "original identity profession stored mode and civil admission retained");
                helper.assertTrue(npc.getMainHandItem().isEmpty() && npc.getItemBySlot(EquipmentSlot.CHEST).isEmpty() && npc.getPersonalInventory().getItem(1).is(Items.IRON_PICKAXE), "return issued kit while retaining private tool");
            }
            helper.assertTrue(armoryBlock.countItem(Items.IRON_SWORD) == count && armoryBlock.countItem(Items.IRON_CHESTPLATE) == count, "actual issued kit conserved");
            helper.assertTrue(f.npcs().stream().mapToInt(n -> n.getPersonalInventory().count(s -> s.is(Items.EMERALD))).sum() + treasuryBlock.countItem(Items.EMERALD) == 64, "payroll cannot create money");
            helper.assertTrue(nativePorts.reconcile(military.service().orElseThrow().records()).eligible(), "shared receipts reconcile after field work");
            var budgets = field.budgets(); for (var budget : budgets.values()) helper.assertTrue(budget.failures() == 0 && budget.blocked() == 0, "no swallowed queue errors: " + budget);
            capture = new JsonObject(); capture.addProperty("people", count); capture.addProperty("process", ProcessHandle.current().pid()); capture.addProperty("orders", orders.size());
            capture.addProperty("nativeDamageEvents", damage.values().stream().mapToInt(Integer::intValue).sum()); capture.addProperty("nativeAttackAttempts", beforeOutside[0]);
            capture.add("budgets", new Gson().toJsonTree(budgets)); capture.add("originalBodies", new Gson().toJsonTree(bodies));
            server.overworld().getDataStorage().saveAndJoin();
        }).thenSucceed();
    }
    private static NpcBase dummy(GameTestHelper helper, BlockPos pos) {
        var npc = helper.spawn(NpcEntityTypes.NPC_BASE.get(), scene(pos)); npc.setNoAi(true); npc.setNoGravity(false); npc.setCommand(NpcCommand.STAY);
        var characters = CharacterRegistryData.get(helper.getLevel().getServer()); long now = helper.getLevel().getGameTime();
        characters.ensureNpcCharacter(npc.getCharacterId(), "test original body", NpcSocialClass.COMMONER, NpcOffice.NONE, "neutral", now);
        characters.bindNpcBody(npc.getCharacterId(), helper.getLevel().dimension().identifier().toString(), npc.getUUID(), now); return npc;
    }
    private static WarCommand.IssueOrder command(GameTestHelper helper, WarServerRuntime core, WarMilitiaRequest first, WarKey kind, BlockPos relative) {
        var target = helper.absolutePos(scene(relative)); var unit = core.session().orElseThrow().unit(first.unitId()).orElseThrow();
        return new WarCommand.IssueOrder(UUID.randomUUID(), first.actorId(), first.armyId(), first.unitId(), unit.revision(), new WarIntent(kind, 1,
                new WarIntent.Position(WarKey.parse(helper.getLevel().dimension().identifier().toString()), target.getX(), target.getY(), target.getZ())));
    }
    private static UUID issue(GameTestHelper helper, WarServerRuntime core, WarFieldRuntime field, WarMilitiaRequest first, WarKey kind, BlockPos relative) {
        var command = command(helper, core, first, kind, relative); var result = field.dispatch(command);
        helper.assertTrue(result.outcome() == WarDispatchService.Outcome.ACCEPTED, "actual dispatch: " + result); return command.operationId();
    }
    private static void atStations(GameTestHelper helper, WarFieldRuntime field, WarMilitiaRequest first, List<NpcBase> npcs, UUID order) {
        var received = field.transmission(first.actorId(), first.armyId(), order).orElseThrow(); var reports = field.reports(first.actorId(), first.armyId());
        helper.assertTrue(received.received().isPresent() && reports.size() == npcs.size(), "wait for real received order");
        for (var npc : npcs) {
            var member = received.members().stream().filter(m -> m.body().equals(npc.getUUID())).findFirst().orElseThrow(); var p = received.station(member);
            var report = reports.stream().filter(r -> r.body().equals(npc.getUUID())).findFirst().orElseThrow();
            helper.assertTrue(report.order().filter(order::equals).isPresent() && report.state() == WarFieldReport.State.HOLDING && npc.distanceToSqr(p.x() + .5, p.y(), p.z() + .5) <= 1.01,
                    "wait for physical station: " + report + ", actual=" + npc.position() + ", station=" + p);
        }
    }
    private static void force(net.minecraft.server.MinecraftServer server, BlockPos scene, boolean value) {
        for (int x = scene.getX() >> 4; x <= (scene.getX() + 31) >> 4; x++) for (int z = scene.getZ() >> 4; z <= (scene.getZ() + 31) >> 4; z++) server.overworld().setChunkForced(x, z, value);
    }
    private static void resume(GameTestHelper helper) {
        try {
            var server = helper.getLevel().getServer(); var root = server.getWorldPath(LevelResource.ROOT);
            var proof = JsonParser.parseString(Files.readString(root.resolve("war-m3-save-proof.json"))).getAsJsonObject();
            helper.assertTrue(proof.get("process").getAsLong() != ProcessHandle.current().pid(), "new JVM actually reads the saved world");
            var plans = List.copyOf(new WarKitCodec().read(Files.readAllBytes(root.resolve("data/" + WarKitAdapter.FILE_NAME))));
            plans.forEach(plan -> soldiers.add(plan.body()));
            helper.assertTrue(plans.size() == 3, "original three kit plans retained"); var first = plans.getFirst().request(); var scene = BlockPos.of(proof.get("scene").getAsLong());
            var original = WarNativeMobilizationGameTests.sources(server); var core = WarServerEvents.existing(server).orElseThrow();
            core.bindAuthority((actor, army) -> actor.equals(first.actorId()) && army.equals(first.armyId()) ? WarCommandService.Permission.ALLOWED : WarCommandService.Permission.DENIED);
            var nativePorts = core.bindNativeMobilization(original); var military = core.mobilization().orElseThrow(); core.activate();
            var field = core.bindFieldExecution(new WarFieldRuntime.Sources(original.characters(), original.injuries(), CombatEncounterData.get(server), original.life()));
            UUID savedOrder = UUID.fromString(proof.get("order").getAsString()); force(server, scene, true);
            var loaded = new ArrayList<NpcBase>(); var orders = new ArrayList<UUID>(); orders.add(savedOrder);
            helper.startSequence().thenWaitUntil(() -> {
                loaded.clear(); for (var plan : plans) {
                    var body = ItemLocation.personal(plan.request().characterId(), plan.body()).body(server, original.characters()); helper.assertTrue(body.isPresent(), "original saved body loaded"); loaded.add(body.orElseThrow());
                }
                atStations(helper, field, first, loaded, savedOrder);
                for (var npc : loaded) {
                    int index = proof.getAsJsonArray("originalBodies").asList().indexOf(new JsonPrimitive(npc.getUUID().toString())); helper.assertTrue(index >= 0, "same UUID, no replacement body");
                    helper.assertTrue(!npc.isNoAi() && npc.getMainHandItem().is(Items.IRON_SWORD) && npc.getPersonalInventory().getItem(1).is(Items.IRON_PICKAXE), "actual equipped original survives restart");
                    helper.assertTrue(npc.getProfession().name().equals(proof.getAsJsonArray("originalProfessions").get(index).getAsString())
                            && NpcBrainRuntime.mode(npc).name().equals(proof.getAsJsonArray("originalModes").get(index).getAsString()), "original stored mode and profession survive restart");
                    helper.assertTrue(npc.getPersistentData().getCompoundOrEmpty("SagadysseyFieldControlV1").equals(readStamp(proof.getAsJsonArray("bodyStamps").get(index).getAsString())), "body reception sequence and attack cooldown are not reset");
                }
            }).thenExecute(() -> force(server, scene, false)).thenWaitUntil(() -> {
                helper.assertTrue(!server.overworld().hasChunkAt(scene) && loaded.stream().allMatch(npc -> server.overworld().getEntity(npc.getUUID()) == null), "actual commanded bodies and scene unload");
            }).thenIdle(60).thenExecute(() -> force(server, scene, true)).thenWaitUntil(() -> {
                loaded.clear(); for (var plan : plans) {
                    var body = ItemLocation.personal(plan.request().characterId(), plan.body()).body(server, original.characters()); helper.assertTrue(body.isPresent(), "reload same commanded body"); loaded.add(body.orElseThrow());
                }
                atStations(helper, field, first, loaded, savedOrder);
                var issuer = original.characters().bodyOf(first.actorId());
                helper.assertTrue(issuer != null && ItemLocation.personal(first.actorId(), issuer.entityId()).body(server, original.characters()).isPresent(), "wait for the original issuing NPC's separate chunk to reload: " + issuer);
            }).thenExecute(() -> {
                var prior = field.transmission(first.actorId(), first.armyId(), savedOrder).orElseThrow(); var target = prior.command().intent().target();
                var unit = core.session().orElseThrow().unit(first.unitId()).orElseThrow();
                var command = new WarCommand.IssueOrder(UUID.randomUUID(), core.session().orElseThrow().generation(), first.actorId(), first.armyId(), first.unitId(), unit.revision(),
                        new WarIntent(InitialWarDefinitions.HOLD, 1, new WarIntent.Position(target.dimension(), target.x() + 4, target.y(), target.z())));
                var result = field.dispatch(command); helper.assertTrue(result.outcome() == WarDispatchService.Outcome.ACCEPTED, "new order after reload: " + result); orders.add(command.operationId());
            }).thenWaitUntil(() -> atStations(helper, field, first, loaded, orders.getLast())).thenExecute(() -> {
                helper.assertTrue(attempts.isEmpty() && damage.isEmpty(), "restart and reload do not replay physical attacks");
                for (var plan : plans) military.demobilize(plan.request().id(), first.actorId());
            }).thenWaitUntil(() -> {
                for (var plan : plans) helper.assertTrue(military.service().orElseThrow().find(plan.request().id()).orElseThrow().stage() == WarMobilization.Stage.RELEASED, "recovered field service demobilizes");
            }).thenExecute(() -> {
                for (var npc : loaded) helper.assertTrue(!NpcPrimaryWorkRuntime.reserved(npc) && !WorkCommitmentRuntime.blocksCivil(npc) && !npc.isNoAi()
                        && npc.getMainHandItem().isEmpty() && npc.getItemBySlot(EquipmentSlot.CHEST).isEmpty() && npc.getPersonalInventory().getItem(1).is(Items.IRON_PICKAXE), "recovered original is available for civil work with private tool");
                var armory = plans.getFirst().armory().resolve(server, original.characters()).orElseThrow();
                helper.assertTrue(armory.container().countItem(Items.IRON_SWORD) == 3 && armory.container().countItem(Items.IRON_CHESTPLATE) == 3, "same original gear returned across restart");
                var treasury = nativePorts.provisions().find(first.id()).orElseThrow().terms().treasury().resolve(server, original.characters()).orElseThrow();
                helper.assertTrue(loaded.stream().mapToInt(n -> n.getPersonalInventory().count(s -> s.is(Items.EMERALD))).sum() + treasury.container().countItem(Items.EMERALD) == 64, "money conserved across command recovery");
                helper.assertTrue(nativePorts.reconcile(military.service().orElseThrow().records()).eligible(), "recovered work and supply receipts agree");
                capture = new JsonObject(); capture.addProperty("people", 3); capture.addProperty("orders", proof.get("orders").getAsInt() + 1); capture.addProperty("stage", "RELEASED");
                capture.addProperty("scene", scene.asLong()); capture.addProperty("priorProcess", proof.get("process").getAsLong()); capture.addProperty("receivedBeforeRestart", savedOrder.toString());
                capture.addProperty("receivedAfterReload", orders.getLast().toString()); capture.addProperty("attackReplays", 0); capture.add("budgets", new Gson().toJsonTree(field.budgets()));
                capture.add("originalBodies", proof.getAsJsonArray("originalBodies").deepCopy()); server.overworld().getDataStorage().saveAndJoin();
            }).thenSucceed();
        } catch (Exception exception) { throw new IllegalStateException("M3 native recovery", exception); }
    }
    private static net.minecraft.nbt.CompoundTag readStamp(String value) {
        try { return TagParser.parseCompoundFully(value); } catch (Exception exception) { throw new IllegalStateException("invalid saved body proof", exception); }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void stopped(ServerStoppedEvent event) {
        if (!enabled() || capture == null || !(event.getServer() instanceof GameTestServer)) return;
        try {
             var root = event.getServer().getWorldPath(LevelResource.ROOT);
            var disk = new WarDispatchCodec().read(Files.readAllBytes(root.resolve("data/" + WarDispatchJournal.FILE_NAME)));
            if (disk.size() != capture.get("orders").getAsInt() || disk.stream().anyMatch(r -> r.received().isEmpty())) throw new IllegalStateException("field reception journal not actually durable");
            var level = net.minecraft.nbt.NbtIo.readCompressed(root.resolve("level.dat"), net.minecraft.nbt.NbtAccounter.create(64 * 1024 * 1024L)).getCompoundOrEmpty("Data");
            capture.addProperty("gameTime", level.getLongOr("Time", 0L)); capture.addProperty("dayTime", level.getLongOr("DayTime", 0L)); capture.addProperty("process", ProcessHandle.current().pid());
            Files.writeString(root.resolve(phase().equals("single") ? "war-m3-proof.json" : "war-m3-" + phase() + "-proof.json"), capture.toString(), StandardOpenOption.CREATE_NEW);
        } catch (Exception e) { throw new IllegalStateException("native field proof", e); }
    }
}
