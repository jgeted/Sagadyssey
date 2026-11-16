package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.identity.*;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarProjectileSafety;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarWeaponSwitchAdapter;
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
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.*;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import java.nio.file.*;
import java.util.*;

/** 显式启用：四名原NPC的普通弓弩、实物弹药、在途友军拦截及原装备归还。 */
@GameTestHolder("sagadyssey_war_m3b")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = "sagadyssey")
public final class WarRangedGameTests {
    private static final String TEMPLATE = "sagadyssey_war_m3b:empty";
    private static final Map<UUID, List<Long>> shots = new HashMap<>();
    private static final Set<UUID> soldiers = new HashSet<>(), enemies = new HashSet<>(), crossbows = new HashSet<>();
    private static final List<String> errors = new ArrayList<>();
    private static NpcBase interceptor;
    private static boolean moved, intercepted;
    private static int hits;
    private static int meleeHits;
    private static JsonObject saveCapture;
    private static int offset;
    private static BlockPos scene(BlockPos p) { return p.offset(offset, 0, 0); }
    private static boolean enabled() { return Boolean.getBoolean("sagadyssey.war.m3b.test"); }
    private static String phase() { return System.getProperty("sagadyssey.war.m3b.phase", "single"); }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void started(ServerStartedEvent event) {
        if (!enabled() || !(event.getServer() instanceof GameTestServer)) return;
        shots.clear(); soldiers.clear(); enemies.clear(); crossbows.clear(); errors.clear(); interceptor = null; moved = false; intercepted = false; hits = 0; meleeHits = 0; saveCapture = null;
        offset = phase().equals("save") ? 2048 : 0;
        try {
            var level = event.getServer().overworld();
            if (phase().equals("resume")) {
                var proof = JsonParser.parseString(Files.readString(event.getServer().getWorldPath(LevelResource.ROOT).resolve("war-switch-save.json"))).getAsJsonObject();
                if (proof.get("process").getAsLong() == ProcessHandle.current().pid()) throw new IllegalStateException("resume must use another JVM");
                event.getServer().getWorldData().overworldData().setGameTime(proof.get("gameTime").getAsLong());
                event.getServer().getWorldData().overworldData().setDayTime(proof.get("dayTime").getAsLong());
            }
            level.getStructureManager().getOrCreate(Identifier.parse(TEMPLATE)).load(level.holderLookup(Registries.BLOCK),
                    TagParser.parseCompoundFully("{size:" + (phase().equals("resume") ? "[1,1,1]" : "[40,8,40]") + ",entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
        } catch (Exception exception) { throw new IllegalStateException("ranged fixture template", exception); }
    }
    @GameTestGenerator public static Collection<TestFunction> tests() {
        return enabled() ? List.of(new TestFunction("war_m3b", "original_archers_" + phase(), TEMPLATE, 3600, 0, true,
                phase().equals("legacy") ? WarRangedGameTests::legacy : phase().equals("resume") ? WarRangedGameTests::resume : WarRangedGameTests::lifecycle)) : List.of();
    }
    @SubscribeEvent public static void launched(EntityJoinLevelEvent event) {
        if (!enabled() || !(event.getEntity() instanceof AbstractArrow arrow) || !(arrow.getOwner() instanceof NpcBase npc) || !soldiers.contains(npc.getUUID())) return;
        shots.computeIfAbsent(npc.getUUID(), ignored -> new ArrayList<>()).add(npc.level().getGameTime());
        if (WarProjectileSafety.read(arrow).isEmpty()) errors.add("original soldier launched unmarked legacy arrow");
        if (arrow.pickup != AbstractArrow.Pickup.DISALLOWED) errors.add("consumed arrow became recoverable");
        if ((arrow.getWeaponItem() != null && arrow.getWeaponItem().is(Items.CROSSBOW)) != crossbows.contains(npc.getUUID())) errors.add("native projectile lost original weapon kind");
        if (!moved && interceptor != null) {
            moved = true;
            // 测试夹具在发射之后进入真实弹道；既不手动触发碰撞，也不手动扣血。
            var p = arrow.position().add(arrow.getDeltaMovement().scale(2));
            interceptor.teleportTo(p.x, p.y - .85, p.z); interceptor.setNoGravity(true);
        }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true) public static void impact(ProjectileImpactEvent event) {
        if (!enabled() || interceptor == null || !(event.getProjectile() instanceof AbstractArrow arrow) || !arrow.getPersistentData().contains(WarProjectileSafety.KEY)
                || !(event.getRayTraceResult() instanceof EntityHitResult hit) || hit.getEntity() != interceptor) return;
        intercepted = event.isCanceled() && arrow.isRemoved();
        if (!intercepted) errors.add("friendly physical impact was not canceled and discarded");
        interceptor.teleportTo(interceptor.getX() + 20, interceptor.getY(), interceptor.getZ());
    }
    @SubscribeEvent public static void damaged(LivingDamageEvent.Post event) {
        if (enabled() && event.getSource().getDirectEntity() instanceof NpcBase attacker && soldiers.contains(attacker.getUUID()) && event.getNewDamage() > 0) {
            if (!enemies.contains(event.getEntity().getUUID())) errors.add("backup sword damaged a friendly body"); else meleeHits++;
        }
        if (!enabled() || !(event.getSource().getDirectEntity() instanceof AbstractArrow) || !(event.getSource().getEntity() instanceof NpcBase npc) || !soldiers.contains(npc.getUUID())) return;
        if (!enemies.contains(event.getEntity().getUUID())) errors.add("native arrow damaged friendly or unclassified body");
        if (event.getNewDamage() > 0) hits++;
    }
    private static void lifecycle(GameTestHelper helper) {
        var level = helper.getLevel(); var server = level.getServer();
        for (int x = 0; x < 40; x++) for (int z = 0; z < 40; z++) {
            var p = helper.absolutePos(scene(new BlockPos(x, 0, z))); level.getChunkAt(p);
            if (offset != 0) level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
            helper.setBlock(scene(new BlockPos(x, 0, z)), Blocks.STONE);
        }
        var commander = dummy(helper, new BlockPos(2, 1, 25));
        var f = WarCandidateGameTests.fixture(helper, 4, commander.getCharacterId());
        var modes = f.npcs().stream().map(NpcBrainRuntime::mode).toList(); var professions = f.npcs().stream().map(NpcBase::getProfession).toList();
        var bodies = f.npcs().stream().map(NpcBase::getUUID).toList();
        var bowLoadout = new WarLoadout(WarLoadout.Mount.FOOT, WarLoadout.Weapon.BOW, false, true).id();
        var crossbowLoadout = new WarLoadout(WarLoadout.Mount.FOOT, WarLoadout.Weapon.CROSSBOW, false, true).id();
        UUID crossbowUnit = UUID.randomUUID(); var requests = new ArrayList<WarMilitiaRequest>();
        for (int i = 0; i < 4; i++) {
            var r = f.requests().get(i); var npc = f.npcs().get(i); soldiers.add(npc.getUUID()); if (i >= 2) crossbows.add(npc.getUUID());
            if (offset != 0) { var p = helper.absolutePos(scene(new BlockPos(1 + i, 1, 1))); npc.teleportTo(p.getX() + .5, p.getY(), p.getZ() + .5); }
            requests.add(new WarMilitiaRequest(r.id(), r.actorId(), r.armyId(), i < 2 ? r.unitId() : crossbowUnit, r.characterId(), i < 2 ? bowLoadout : crossbowLoadout, 1, r.kind(), r.role(), r.wage(), r.requestedAtTick()));
            npc.setCommand(NpcCommand.STAY); npc.setNoGravity(false); npc.setOnGround(true); npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            npc.getPersonalInventory().setItem(0, new ItemStack(Items.BREAD, 8)); npc.getPersonalInventory().setItem(1, new ItemStack(Items.IRON_PICKAXE));
            npc.getPersonalInventory().setItem(2, new ItemStack(Items.IRON_SWORD)); npc.getPersonalInventory().setItem(3, new ItemStack(Items.ARROW, 3));
            npc.getPersonalInventory().setItem(4, new ItemStack(i < 2 ? Items.BOW : Items.CROSSBOW));
            f.sources().life().setHunger(npc.getCharacterId(), 100, level.getGameTime());
        }
        var first = requests.getFirst(); var crossbowRequest = requests.get(2); boolean[] authorized = {true}; var core = WarServerEvents.activate(server);
        core.bindAuthority((actor, army) -> authorized[0] && actor.equals(first.actorId()) && army.equals(first.armyId()) ? WarCommandService.Permission.ALLOWED : WarCommandService.Permission.DENIED);
        for (var request : List.of(first, crossbowRequest)) helper.assertTrue(core.submit(new WarCommand.CreateUnit(UUID.randomUUID(), request.actorId(), request.armyId(), request.unitId(), request.loadout(), 1)).status() == WarResult.Status.APPLIED, "create actual ranged unit");
        server.overworld().getDataStorage().saveAndJoin();
        helper.setBlock(scene(new BlockPos(2, 1, 30)), Blocks.CHEST); helper.setBlock(scene(new BlockPos(5, 1, 30)), Blocks.CHEST);
        var armoryBlock = (ChestBlockEntity)level.getBlockEntity(helper.absolutePos(scene(new BlockPos(2, 1, 30))));
        var treasuryBlock = (ChestBlockEntity)level.getBlockEntity(helper.absolutePos(scene(new BlockPos(5, 1, 30))));
        var armory = ItemLocation.block(UUID.randomUUID(), level.dimension().identifier().toString(), armoryBlock.getBlockPos());
        var treasury = ItemLocation.block(UUID.randomUUID(), level.dimension().identifier().toString(), treasuryBlock.getBlockPos()); armory.bindBlock(server); treasury.bindBlock(server);
        for (int i = 0; i < 4; i++) { armoryBlock.setItem(i * 2, new ItemStack(i < 2 ? Items.BOW : Items.CROSSBOW)); armoryBlock.setItem(i * 2 + 1, new ItemStack(Items.IRON_CHESTPLATE)); }
        treasuryBlock.setItem(0, new ItemStack(Items.EMERALD, 64)); var rights = RightsStateData.get(server); var owner = SubjectRef.character(first.actorId());
        rights.ensureContext(armory.id(), "test:ranged_armory", owner, owner, AccessPolicy.PRIVATE); rights.ensureContext(treasury.id(), "test:ranged_treasury", owner, owner, AccessPolicy.PRIVATE);
        for (var npc : f.npcs()) {
            var person = SubjectRef.character(npc.getCharacterId());
            rights.ensureContext(ItemLocation.personal(npc.getCharacterId(), npc.getUUID()).id(), "test:private_bag", person, person, AccessPolicy.PRIVATE);
            rights.ensureContext(ItemLocation.equipment(npc.getCharacterId(), npc.getUUID()).id(), "test:ranged_gear", person, person, AccessPolicy.PRIVATE);
        }
        final WarNativeMobilization nativePorts;
        try { nativePorts = core.bindNativeMobilization(f.sources()); } catch (Exception e) { throw new IllegalStateException(e); }
        var military = core.mobilization().orElseThrow();
        var field = core.bindFieldExecution(new WarFieldRuntime.Sources(f.sources().characters(), f.sources().injuries(), CombatEncounterData.get(server), f.sources().life()));
        var orders = new ArrayList<UUID>(); var foes = new ArrayList<NpcBase>(); int[] exhausted = {0};
        var sequence = helper.startSequence().thenWaitUntil(() -> {
            boolean ready = true;
            for (int i = 0; i < 4; i++) ready &= nativePorts.configure(requests.get(i), treasury, armory,
                    List.of(new WarKitRecord.Line(ItemFingerprint.of(new ItemStack(i < 2 ? Items.BOW : Items.CROSSBOW)), 1), new WarKitRecord.Line(ItemFingerprint.of(new ItemStack(Items.IRON_CHESTPLATE)), 1))) == WarMobilizationPorts.Status.APPLIED;
            helper.assertTrue(ready, "wait for original physical supply plans");
            for (var request : requests) helper.assertTrue(military.preview(request).ready(), "real ranged preflight: " + military.preview(request));
        }).thenExecute(() -> {
            for (var request : requests) helper.assertTrue(military.begin(request).outcome() == WarMobilizationService.Outcome.ACCEPTED, "activate original archer");
        }).thenWaitUntil(() -> {
            for (var request : requests) helper.assertTrue(military.service().orElseThrow().find(request.id()).orElseThrow().stage() == WarMobilization.Stage.ACTIVE, "wait for original ranged contract");
            helper.assertTrue(field.reports(first.actorId(), first.armyId()).size() == 4, "wait for real body reports");
        }).thenExecute(() -> {
            orders.add(issue(helper, core, field, first, new BlockPos(10, 1, 10))); orders.add(issue(helper, core, field, crossbowRequest, new BlockPos(20, 1, 10)));
        }).thenWaitUntil(() -> {
            for (int i = 0; i < 2; i++) {
                var instruction = field.transmission(first.actorId(), first.armyId(), orders.get(i)).orElseThrow(); helper.assertTrue(instruction.received().isPresent(), "wait for received order");
                for (var npc : f.npcs().subList(i * 2, i * 2 + 2)) {
                    var report = field.reports(first.actorId(), first.armyId()).stream().filter(r -> r.body().equals(npc.getUUID())).findFirst().orElseThrow();
                    helper.assertTrue(report.state() == WarFieldReport.State.HOLDING && report.order().filter(orders.get(i)::equals).isPresent(), "wait for physical ranged station: " + report);
                }
            }
        }).thenExecute(() -> {
            interceptor = dummy(helper, new BlockPos(32, 1, 10)); dummy(helper, new BlockPos(16, 1, 18));
            for (int x : List.of(10, 20)) {
                var foe = dummy(helper, new BlockPos(x, 1, 20)); foe.getAttribute(Attributes.MAX_HEALTH).setBaseValue(500); foe.setHealth(500); foes.add(foe); enemies.add(foe.getUUID());
            }
            var friendly = new HashSet<>(f.npcs().stream().map(NpcBase::getCharacterId).toList()); friendly.add(interceptor.getCharacterId());
            CombatEncounterService.start(CombatEncounterData.get(server), UUID.randomUUID(), null, level.dimension().identifier().toString(), helper.absolutePos(scene(new BlockPos(15, 1, 15))), 32,
                    List.of(new CombatSide(UUID.randomUUID(), "original archers and ally", null, friendly, EngagementRule.LETHAL_FORCE),
                            new CombatSide(UUID.randomUUID(), "explicit hostile targets", null, new HashSet<>(foes.stream().map(NpcBase::getCharacterId).toList()), EngagementRule.LETHAL_FORCE)), level.getGameTime());
        }).thenWaitUntil(() -> {
            helper.assertTrue(errors.isEmpty(), "native launch/impact errors: " + errors);
            helper.assertTrue(f.npcs().stream().allMatch(n -> n.getPersonalInventory().count(s -> s.is(Items.ARROW)) == 0), "wait for actual ammo exhaustion: " + shots);
            helper.assertTrue(field.reports(first.actorId(), first.armyId()).stream().allMatch(r -> r.reason().equals("ACTUAL_AMMUNITION_REQUIRED")), "wait for explicit no-ammo stop");
        }).thenExecute(() -> {
            exhausted[0] = totalShots(); helper.assertTrue(exhausted[0] == 12, "12 real arrows consumed for exactly 12 native projectiles");
            helper.assertTrue(intercepted && interceptor.getHealth() == interceptor.getMaxHealth(), "real friendly entered flight path and was protected");
            helper.assertTrue(hits > 0 && f.sources().injuries().wounds().stream().anyMatch(w -> foes.stream().anyMatch(n -> n.getCharacterId().equals(w.patientCharacterId()))), "native projectile hits produce original injuries");
            for (var npc : f.npcs()) {
                var times = shots.getOrDefault(npc.getUUID(), List.of()); helper.assertTrue(times.size() == 3, "each original body shot exactly its real ammo");
                for (int i = 1; i < times.size(); i++) helper.assertTrue(times.get(i) - times.get(i - 1) >= (crossbows.contains(npc.getUUID()) ? 55 : 40), "native shot spacing respects separate draw and recovery: " + times);
                helper.assertTrue(!npc.isUsingItem(), "no-ammo stop cancels original item use");
            }
        }).thenIdle(60).thenExecute(() -> {
            helper.assertTrue(totalShots() == exhausted[0], "no free arrows or idle catch-up shots");
            var npc = f.npcs().getFirst(); var foe = foes.getFirst(); foe.teleportTo(npc.getX(), npc.getY(), npc.getZ() + 2);
            var crossbowNpc = f.npcs().get(2); foes.get(1).teleportTo(crossbowNpc.getX(), crossbowNpc.getY(), crossbowNpc.getZ() + 2);
            // 一支明确的夹具补箭占用刚用空的槽位，其余空位填满，要求借弓只能原子交换进腾出的剑槽。
            npc.getPersonalInventory().setItem(3, new ItemStack(Items.ARROW));
            for (int slot = 0; slot < npc.getPersonalInventory().getContainerSize(); slot++) if (npc.getPersonalInventory().getItem(slot).isEmpty()) npc.getPersonalInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }).thenWaitUntil(() -> { helper.assertTrue(f.npcs().stream().allMatch(n -> n.getMainHandItem().is(Items.IRON_SWORD) && n.getPersistentData().getCompoundOrEmpty(WarWeaponSwitchAdapter.KEY).getBooleanOr("Done", false)) && meleeHits > 0, "wait for both original bow/crossbow squads to swap and native sword hit: " + field.reports(first.actorId(), first.armyId())); })
                .thenExecute(() -> {
                    var npc = f.npcs().getFirst(); helper.assertTrue(npc.getPersonalInventory().getItem(2).is(Items.BOW), "issued primary occupies the exact vacated sword slot in a full bag");
                    var claim = rights.claimAt(ItemLocation.personal(npc.getCharacterId(), npc.getUUID()).id(), 2, ItemFingerprint.of(npc.getPersonalInventory().getItem(2)));
                    helper.assertTrue(claim != null && claim.beneficialOwner().equals(owner) && claim.encumbrances().contains("issued") && claim.provenance().isEmpty(), "original borrowed owner and provenance follow the exact bow");
                    helper.assertTrue(npc.getPersonalInventory().getItem(4).is(Items.BOW) && rights.claimAt(ItemLocation.personal(npc.getCharacterId(), npc.getUUID()).id(), 4, ItemFingerprint.of(new ItemStack(Items.BOW))) == null, "identical private bow retains original NPC title");
                });
        if (phase().equals("save")) {
            sequence.thenExecute(() -> {
                var proof = new JsonObject(); proof.addProperty("process", ProcessHandle.current().pid()); proof.addProperty("gameTime", level.getGameTime()); proof.addProperty("dayTime", level.getDayTime());
                proof.addProperty("origin", helper.absolutePos(scene(BlockPos.ZERO)).asLong());
                proof.add("bodies", new Gson().toJsonTree(bodies)); proof.add("positions", new Gson().toJsonTree(f.npcs().stream().map(n -> n.blockPosition().asLong()).toList()));
                proof.add("professions", new Gson().toJsonTree(professions)); proof.add("modes", new Gson().toJsonTree(modes));
                proof.add("cursors", new Gson().toJsonTree(f.npcs().stream().map(n -> n.getPersistentData().getCompoundOrEmpty(WarWeaponSwitchAdapter.KEY).toString()).toList()));
                proof.addProperty("projectilesBeforeSave", totalShots()); proof.addProperty("allFourBackupSwordsHeld", true); proof.addProperty("fullBagFirstOriginal", true);
                saveCapture = proof;
                try { Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("war-switch-save.json"), new GsonBuilder().setPrettyPrinting().create().toJson(proof)); }
                catch (Exception e) { throw new IllegalStateException(e); }
                server.overworld().getDataStorage().saveAndJoin();
            }).thenSucceed(); return;
        }
        sequence.thenExecute(() -> {
            var far = helper.absolutePos(scene(new BlockPos(10, 1, 20))); foes.getFirst().teleportTo(far.getX() + .5, far.getY(), far.getZ() + .5);
            var crossbowFar = helper.absolutePos(scene(new BlockPos(20, 1, 20))); foes.get(1).teleportTo(crossbowFar.getX() + .5, crossbowFar.getY(), crossbowFar.getZ() + .5);
        }).thenWaitUntil(() -> helper.assertTrue(f.npcs().getFirst().isUsingItem(), "real original bow is drawing after fixture refill"))
                .thenIdle(17).thenExecute(() -> f.npcs().getFirst().stopUsingItem()).thenIdle(7).thenExecute(() -> {
                    helper.assertTrue(f.npcs().getFirst().isUsingItem() && totalShots() == exhausted[0] && f.npcs().getFirst().getPersonalInventory().count(s -> s.is(Items.ARROW)) == 1,
                            "an external interruption restarts the full draw instead of completing the old timer");
                    authorized[0] = false;
                }).thenIdle(8).thenExecute(() -> {
                    for (var npc : f.npcs()) helper.assertTrue(npc.getTarget() == null && !npc.isUsingItem() && npc.getNavigation().isDone(), "revocation cancels draw target and navigation");
                    helper.assertTrue(totalShots() == exhausted[0] && f.npcs().getFirst().getPersonalInventory().count(s -> s.is(Items.ARROW)) == 1, "canceled draw consumes no arrow");
                    var npc = f.npcs().getFirst(); foes.getFirst().teleportTo(npc.getX(), npc.getY(), npc.getZ() + 2); authorized[0] = true;
                }).thenWaitUntil(() -> helper.assertTrue(f.npcs().getFirst().getMainHandItem().is(Items.IRON_SWORD), "demobilize while the borrowed bow is stowed"))
                .thenExecute(() -> { for (var request : requests) military.demobilize(request.id(), first.actorId());
                }).thenWaitUntil(() -> {
                    for (var request : requests) helper.assertTrue(military.service().orElseThrow().find(request.id()).orElseThrow().stage() == WarMobilization.Stage.RELEASED, "return real bow/crossbow and release original labor");
                }).thenExecute(() -> {
                    helper.assertTrue(errors.isEmpty(), "no friendly or legacy damage: " + errors);
                    for (int i = 0; i < 4; i++) {
                        var npc = f.npcs().get(i); helper.assertTrue(npc.getUUID().equals(bodies.get(i)) && !npc.isNoAi() && npc.getProfession() == professions.get(i) && NpcBrainRuntime.mode(npc) == modes.get(i)
                                && !NpcPrimaryWorkRuntime.reserved(npc) && !WorkCommitmentRuntime.blocksCivil(npc), "original identity profession and civilian mode retained");
                        helper.assertTrue(npc.getMainHandItem().isEmpty() && npc.getItemBySlot(EquipmentSlot.CHEST).isEmpty() && npc.getPersonalInventory().getItem(1).is(Items.IRON_PICKAXE) && npc.getPersonalInventory().getItem(2).is(Items.IRON_SWORD)
                                && npc.getPersonalInventory().getItem(4).is(i < 2 ? Items.BOW : Items.CROSSBOW), "borrowed returned, original private tools and identical private ranged weapon retained");
                    }
                    helper.assertTrue(armoryBlock.countItem(Items.BOW) == 2 && armoryBlock.countItem(Items.CROSSBOW) == 2 && armoryBlock.countItem(Items.IRON_CHESTPLATE) == 4, "original armory gear conserved");
                    helper.assertTrue(f.npcs().stream().mapToInt(n -> n.getPersonalInventory().count(s -> s.is(Items.EMERALD))).sum() + treasuryBlock.countItem(Items.EMERALD) == 64, "currency conserved");
                    helper.assertTrue(nativePorts.reconcile(military.service().orElseThrow().records()).eligible(), "real shared receipts reconcile");
                    for (var budget : field.budgets().values()) helper.assertTrue(budget.failures() == 0 && budget.blocked() == 0, "no queue failures");
                    var proof = new JsonObject(); proof.addProperty("people", 4); proof.addProperty("nativeProjectiles", totalShots()); proof.addProperty("initialArrows", 12); proof.addProperty("fixtureRefillUnconsumed", 1);
                    proof.addProperty("nativeDamageEvents", hits); proof.addProperty("friendlyPhysicalInterception", intercepted); proof.addProperty("drawCanceledOnRevocation", true);
                    proof.addProperty("drawRestartedAfterExternalCancel", true);
                    proof.addProperty("fullBagWeaponExchange", true); proof.addProperty("nativeBackupSwordHits", meleeHits); proof.addProperty("borrowedTitleRetained", true); proof.addProperty("stowedPrimaryRestoredBeforeReturn", true);
                    proof.addProperty("bowAndCrossbowBackupExchange", true);
                    proof.add("weaponSwitchCursors", new Gson().toJsonTree(f.npcs().stream().filter(n -> n.getPersistentData().contains(WarWeaponSwitchAdapter.KEY)).map(n -> n.getPersistentData().getCompoundOrEmpty(WarWeaponSwitchAdapter.KEY).toString()).toList()));
                    proof.add("bodyShotTicks", new Gson().toJsonTree(shots)); proof.add("originalBodies", new Gson().toJsonTree(bodies)); proof.add("budgets", new Gson().toJsonTree(field.budgets()));
                    try { Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("war-m3b-proof.json"), new GsonBuilder().setPrettyPrinting().create().toJson(proof)); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                }).thenSucceed();
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void stopped(ServerStoppedEvent event) {
        if (!enabled() || saveCapture == null || !phase().equals("save")) return;
        try {
             var root = event.getServer().getWorldPath(LevelResource.ROOT);
            var disk = NbtIo.readCompressed(root.resolve("level.dat"), NbtAccounter.create(64 * 1024 * 1024L)).getCompoundOrEmpty("Data");
            saveCapture.addProperty("gameTime", disk.getLongOr("Time", 0L)); saveCapture.addProperty("dayTime", disk.getLongOr("DayTime", 0L)); saveCapture.addProperty("clockFromSavedLevelDat", true);
            Files.writeString(root.resolve("war-switch-save.json"), new GsonBuilder().setPrettyPrinting().create().toJson(saveCapture));
        } catch (Exception e) { throw new IllegalStateException("switched archer saved proof", e); }
    }
    private static void resume(GameTestHelper helper) {
        try {
            var level = helper.getLevel(); var server = level.getServer(); var root = server.getWorldPath(LevelResource.ROOT);
            var saved = JsonParser.parseString(Files.readString(root.resolve("war-switch-save.json"))).getAsJsonObject();
            helper.assertTrue(saved.get("process").getAsLong() != ProcessHandle.current().pid(), "separate JVM loads original archers");
            var plans = List.copyOf(new WarKitCodec().read(Files.readAllBytes(root.resolve("data/" + WarKitAdapter.FILE_NAME))));
            helper.assertTrue(plans.size() == 4, "original four supply plans retained");
            var first = plans.getFirst().request(); var sources = WarNativeMobilizationGameTests.sources(server); var core = WarServerEvents.existing(server).orElseThrow();
            core.bindAuthority((actor, army) -> actor.equals(first.actorId()) && army.equals(first.armyId()) ? WarCommandService.Permission.ALLOWED : WarCommandService.Permission.DENIED);
            core.pause(); var nativePorts = core.bindNativeMobilization(sources); var military = core.mobilization().orElseThrow();
            core.bindFieldExecution(new WarFieldRuntime.Sources(sources.characters(), sources.injuries(), CombatEncounterData.get(server), sources.life()));
            var origin = BlockPos.of(saved.get("origin").getAsLong());
            for (int x = origin.getX() >> 4; x <= (origin.getX() + 40) >> 4; x++) for (int z = origin.getZ() >> 4; z <= (origin.getZ() + 40) >> 4; z++) level.setChunkForced(x, z, true);
            var original = new ArrayList<NpcBase>();
            helper.startSequence().thenWaitUntil(() -> {
                original.clear();
                for (var id : saved.getAsJsonArray("bodies")) {
                    var entity = level.getEntity(UUID.fromString(id.getAsString())); helper.assertTrue(entity instanceof NpcBase, "wait for exact original body load"); original.add((NpcBase)entity);
                }
                helper.assertTrue(nativePorts.reconcile(military.service().orElseThrow().records()).eligible(), "loaded shared military evidence reconciles");
            }).thenExecute(() -> {
                for (int i = 0; i < 4; i++) {
                    var npc = original.get(i); soldiers.add(npc.getUUID());
                    helper.assertTrue(npc.getMainHandItem().is(Items.IRON_SWORD) && npc.getPersonalInventory().getItem(2).is(i < 2 ? Items.BOW : Items.CROSSBOW), "reloaded actual held sword and exact stowed primary");
                    helper.assertTrue(npc.getPersistentData().getCompoundOrEmpty(WarWeaponSwitchAdapter.KEY).toString().equals(saved.getAsJsonArray("cursors").get(i).getAsString()), "original native switch cursor round-trips on body");
                    var claim = RightsStateData.get(server).claimAt(ItemLocation.personal(npc.getCharacterId(), npc.getUUID()).id(), 2, ItemFingerprint.of(npc.getPersonalInventory().getItem(2)));
                    helper.assertTrue(claim != null && claim.beneficialOwner().equals(SubjectRef.character(first.actorId())) && claim.encumbrances().contains("issued"), "stowed borrowed title reloads in native world");
                }
                core.activate(); for (var plan : plans) military.demobilize(plan.request().id(), first.actorId());
            }).thenWaitUntil(() -> {
                for (var plan : plans) helper.assertTrue(military.service().orElseThrow().find(plan.request().id()).orElseThrow().stage() == WarMobilization.Stage.RELEASED, "restored primary returned and original labor released");
            }).thenExecute(() -> {
                helper.assertTrue(totalShots() == 0 && meleeHits == 0, "no original attack is replayed on recovery");
                for (int i = 0; i < 4; i++) {
                    var npc = original.get(i); helper.assertTrue(npc.getMainHandItem().isEmpty() && npc.getItemBySlot(EquipmentSlot.CHEST).isEmpty() && !NpcPrimaryWorkRuntime.reserved(npc), "loaded original equipment returned");
                    helper.assertTrue(npc.getPersonalInventory().getItem(2).is(Items.IRON_SWORD) && npc.getPersonalInventory().getItem(4).is(i < 2 ? Items.BOW : Items.CROSSBOW), "loaded private sword and identical private primary retained");
                    helper.assertTrue(npc.getProfession().name().equals(saved.getAsJsonArray("professions").get(i).getAsString()) && NpcBrainRuntime.mode(npc).name().equals(saved.getAsJsonArray("modes").get(i).getAsString()) && !npc.isNoAi(), "original profession AI and stored mode retained");
                }
                var armory = plans.getFirst().armory().resolve(server, sources.characters()).orElseThrow().container();
                helper.assertTrue(armory.countItem(Items.BOW) == 2 && armory.countItem(Items.CROSSBOW) == 2 && armory.countItem(Items.IRON_CHESTPLATE) == 4, "four original issued primary weapons and armor conserved after restart");
                var proof = new JsonObject(); proof.addProperty("process", ProcessHandle.current().pid()); proof.addProperty("priorProcess", saved.get("process").getAsLong()); proof.addProperty("people", 4);
                proof.addProperty("stage", "RELEASED"); proof.addProperty("attackReplays", 0); proof.addProperty("stowedBorrowedTitleRestored", true); proof.add("bodies", saved.get("bodies"));
                try { Files.writeString(root.resolve("war-switch-resume.json"), new GsonBuilder().setPrettyPrinting().create().toJson(proof)); }
                catch (Exception e) { throw new IllegalStateException(e); }
            }).thenSucceed();
        } catch (Exception e) { throw new IllegalStateException("original switched archer recovery fixture", e); }
    }
    /** Opens only an explicitly supplied copy of the stopped, released 1.21.1 fixture. */
    private static void legacy(GameTestHelper helper) {
        try {
            var level = helper.getLevel(); var server = level.getServer(); var root = server.getWorldPath(LevelResource.ROOT);
            var saved = JsonParser.parseString(Files.readString(root.resolve("war-switch-save.json"))).getAsJsonObject();
            var released = JsonParser.parseString(Files.readString(root.resolve("war-switch-resume.json"))).getAsJsonObject();
            helper.assertTrue(released.get("stage").getAsString().equals("RELEASED"), "legacy fixture must already be retired");
            var plans = List.copyOf(new WarKitCodec().read(Files.readAllBytes(root.resolve("data/" + WarKitAdapter.FILE_NAME))));
            var sources = WarNativeMobilizationGameTests.sources(server); var core = WarServerEvents.existing(server).orElseThrow();
            core.pause(); var ports = core.bindNativeMobilization(sources); var military = core.mobilization().orElseThrow();
            var origin = BlockPos.of(saved.get("origin").getAsLong());
            for (int x = origin.getX() >> 4; x <= (origin.getX() + 40) >> 4; x++) for (int z = origin.getZ() >> 4; z <= (origin.getZ() + 40) >> 4; z++) level.setChunkForced(x, z, true);
            helper.startSequence().thenWaitUntil(() -> {
                for (var id : saved.getAsJsonArray("bodies")) helper.assertTrue(level.getEntity(UUID.fromString(id.getAsString())) instanceof NpcBase, "load the actual legacy body");
            }).thenExecute(() -> {
                helper.assertTrue(plans.size() == 4 && ports.reconcile(military.service().orElseThrow().records()).eligible(), "legacy shared records reconcile");
                for (var plan : plans) helper.assertTrue(military.service().orElseThrow().find(plan.request().id()).orElseThrow().stage() == WarMobilization.Stage.RELEASED, "retirement is retained across MC versions");
                for (int i = 0; i < 4; i++) {
                    var npc = (NpcBase)level.getEntity(UUID.fromString(saved.getAsJsonArray("bodies").get(i).getAsString()));
                    helper.assertTrue(npc.getMainHandItem().isEmpty() && npc.getItemBySlot(EquipmentSlot.CHEST).isEmpty() && !NpcPrimaryWorkRuntime.reserved(npc), "retired body has no borrowed kit or military labor");
                    helper.assertTrue(npc.getPersonalInventory().getItem(2).is(Items.IRON_SWORD) && npc.getPersonalInventory().getItem(4).is(i < 2 ? Items.BOW : Items.CROSSBOW), "legacy private sword and ranged item remain in their original slots");
                    helper.assertTrue(npc.getProfession().name().equals(saved.getAsJsonArray("professions").get(i).getAsString()) && !npc.isNoAi(), "legacy profession and original AI survive native loading");
                }
                var armory = plans.getFirst().armory().resolve(server, sources.characters()).orElseThrow().container();
                helper.assertTrue(armory.countItem(Items.BOW) == 2 && armory.countItem(Items.CROSSBOW) == 2 && armory.countItem(Items.IRON_CHESTPLATE) == 4, "legacy armory contents are conserved");
                var proof = new JsonObject(); proof.addProperty("sourceMinecraft", "1.21.1"); proof.addProperty("targetMinecraft", "1.21.11");
                proof.addProperty("people", 4); proof.addProperty("stage", "RELEASED"); proof.addProperty("process", ProcessHandle.current().pid()); proof.add("bodies", saved.get("bodies"));
                try { Files.writeString(root.resolve("war-legacy-version-proof.json"), new GsonBuilder().setPrettyPrinting().create().toJson(proof)); }
                catch (Exception e) { throw new IllegalStateException(e); }
            }).thenSucceed();
        } catch (Exception e) { throw new IllegalStateException("legacy released body migration fixture", e); }
    }
    private static int totalShots() { return shots.values().stream().mapToInt(List::size).sum(); }
    private static NpcBase dummy(GameTestHelper helper, BlockPos pos) {
        var npc = helper.spawn(NpcEntityTypes.NPC_BASE.get(), scene(pos)); npc.setNoAi(true); npc.setNoGravity(false); npc.setCommand(NpcCommand.STAY);
        var characters = CharacterRegistryData.get(helper.getLevel().getServer()); long now = helper.getLevel().getGameTime();
        characters.ensureNpcCharacter(npc.getCharacterId(), "ranged native target", NpcSocialClass.COMMONER, NpcOffice.NONE, "neutral", now);
        characters.bindNpcBody(npc.getCharacterId(), helper.getLevel().dimension().identifier().toString(), npc.getUUID(), now); return npc;
    }
    private static UUID issue(GameTestHelper helper, WarServerRuntime core, WarFieldRuntime field, WarMilitiaRequest request, BlockPos relative) {
        var p = helper.absolutePos(scene(relative)); var unit = core.session().orElseThrow().unit(request.unitId()).orElseThrow();
        var command = new WarCommand.IssueOrder(UUID.randomUUID(), request.actorId(), request.armyId(), request.unitId(), unit.revision(), new WarIntent(InitialWarDefinitions.HOLD, 1,
                new WarIntent.Position(WarKey.parse(helper.getLevel().dimension().identifier().toString()), p.getX(), p.getY(), p.getZ())));
        var result = field.dispatch(command); helper.assertTrue(result.outcome() == WarDispatchService.Outcome.ACCEPTED, "real ranged dispatch: " + result); return command.operationId();
    }
}
