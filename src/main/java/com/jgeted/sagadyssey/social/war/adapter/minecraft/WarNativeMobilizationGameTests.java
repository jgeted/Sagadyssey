package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarSavedData;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarStateCodec;
import com.jgeted.sagadyssey.social.work.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.nbt.*;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import com.google.gson.*;
import java.nio.file.*;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import java.util.*;

/** opt-in原生纵切片：真实三人、同一兵团、九个适配步骤、粮饷与真实返还。 */
@GameTestHolder("sagadyssey_war_m2b2")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = "sagadyssey")
public final class WarNativeMobilizationGameTests {
    private static JsonObject capture;
    private static String phase() { return System.getProperty("sagadyssey.war.m2b2.phase", "single"); }
    private static boolean enabled() { return Boolean.getBoolean("sagadyssey.war.m2b2.test"); }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void started(ServerStartedEvent event) {
        if (!enabled() || !phase().equals("resume") || !(event.getServer() instanceof GameTestServer)) return;
        try {
            var proof = JsonParser.parseString(Files.readString(event.getServer().getWorldPath(LevelResource.ROOT).resolve("war-m2b2-save-proof.json"))).getAsJsonObject();
            if (proof.get("process").getAsLong() == ProcessHandle.current().pid()) throw new IllegalStateException("resume must use another JVM");
            // GameTestServer重建关卡元数据；仅测试恢复该世界已核实的保存时钟。
            event.getServer().getWorldData().overworldData().setGameTime(proof.get("gameTime").getAsLong());
            event.getServer().getWorldData().overworldData().setDayTime(proof.get("dayTime").getAsLong());
        } catch (Exception exception) { throw new IllegalStateException("native restart fixture clock failed", exception); }
    }
    @GameTestGenerator public static Collection<TestFunction> tests() {
        if (!enabled()) return List.of();
        return List.of(new TestFunction("war_m2b2", "three_people_native_" + phase(), "sagadyssey_war_m2b2:empty", 3600, 0, true,
                phase().equals("resume") ? WarNativeMobilizationGameTests::resume : WarNativeMobilizationGameTests::lifecycle));
    }
    private static void lifecycle(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); var fixture = WarCandidateGameTests.fixture(helper, 3); long now = server.overworld().getGameTime();
        int offset = phase().equals("save") ? 2048 : 0;
        for (int x = offset; x < offset + 12; x++) for (int z = 0; z < 12; z++) {
            var pos = helper.absolutePos(new BlockPos(x, 0, z)); helper.getLevel().getChunkAt(pos); if (offset != 0) helper.getLevel().setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        }
        if (offset != 0) for (int i = 0; i < fixture.npcs().size(); i++) { var pos = helper.absolutePos(new BlockPos(offset + 1 + i, 1, 1)); fixture.npcs().get(i).teleportTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5); }
        for (var npc : fixture.npcs()) {
            npc.setCommand(com.jgeted.sagadyssey.npc.entity.NpcCommand.STAY); npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            fixture.sources().life().setHunger(npc.getCharacterId(), 100, now); npc.getPersonalInventory().setItem(0, new ItemStack(Items.BREAD, 2));
            npc.getPersonalInventory().setItem(1, new ItemStack(Items.IRON_PICKAXE));
        }
        var first = fixture.requests().getFirst(); var loadout = new WarLoadout(WarLoadout.Mount.FOOT, WarLoadout.Weapon.SWORD, false, true).id();
        var requests = fixture.requests().stream().map(r -> new WarMilitiaRequest(r.id(), r.actorId(), r.armyId(), r.unitId(), r.characterId(), loadout, 1,
                r.kind(), r.role(), new WarMilitiaRequest.Wage(WarKey.parse("minecraft:emerald"), 1, 200), r.requestedAtTick())).toList();
        var runtime = WarServerEvents.activate(server); runtime.bindAuthority((actor, army) -> actor.equals(first.actorId()) && army.equals(first.armyId()) ? WarCommandService.Permission.ALLOWED : WarCommandService.Permission.DENIED);
        helper.assertTrue(runtime.submit(new WarCommand.CreateUnit(UUID.randomUUID(), first.actorId(), first.armyId(), first.unitId(), loadout, 1)).status() == WarResult.Status.APPLIED, "unit has actual authorized owner");
        server.overworld().getDataStorage().saveAndJoin();
        helper.setBlock(new BlockPos(offset + 2, 1, 5), Blocks.CHEST); helper.setBlock(new BlockPos(offset + 5, 1, 5), Blocks.CHEST);
        var armoryBlock = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(offset + 2, 1, 5)));
        var treasuryBlock = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(offset + 5, 1, 5)));
        var armory = ItemLocation.block(UUID.randomUUID(), helper.getLevel().dimension().identifier().toString(), armoryBlock.getBlockPos());
        var treasury = ItemLocation.block(UUID.randomUUID(), helper.getLevel().dimension().identifier().toString(), treasuryBlock.getBlockPos()); armory.bindBlock(server); treasury.bindBlock(server);
        for (int i = 0; i < 3; i++) { armoryBlock.setItem(i * 2, new ItemStack(Items.IRON_SWORD)); armoryBlock.setItem(i * 2 + 1, new ItemStack(Items.IRON_CHESTPLATE)); }
        var rights = RightsStateData.get(server); var actor = SubjectRef.character(first.actorId());
        rights.ensureContext(armory.id(), "test:armory", actor, actor, AccessPolicy.PRIVATE); rights.ensureContext(treasury.id(), "test:treasury", actor, actor, AccessPolicy.PRIVATE);
        for (var npc : fixture.npcs()) { var person = SubjectRef.character(npc.getCharacterId());
            rights.ensureContext(ItemLocation.personal(npc.getCharacterId(), npc.getUUID()).id(), "test:bag", person, person, AccessPolicy.PRIVATE);
            rights.ensureContext(ItemLocation.equipment(npc.getCharacterId(), npc.getUUID()).id(), "test:equipment", person, person, AccessPolicy.PRIVATE);
        }
        final WarNativeMobilization nativePorts;
        try { nativePorts = runtime.bindNativeMobilization(fixture.sources()); } catch (Exception exception) { throw new IllegalStateException(exception); }
        var military = runtime.mobilization().orElseThrow(); var kit = List.of(new WarKitRecord.Line(ItemFingerprint.of(new ItemStack(Items.IRON_SWORD)), 1), new WarKitRecord.Line(ItemFingerprint.of(new ItemStack(Items.IRON_CHESTPLATE)), 1));
        var sequence = helper.startSequence().thenWaitUntil(() -> {
            boolean ready = true; for (var request : requests) ready &= nativePorts.configure(request, treasury, armory, kit) == com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.Status.APPLIED;
            helper.assertTrue(ready, "wait for all real supply configurations to be durable");
            for (var request : requests) helper.assertTrue(military.preview(request).ready(), "native preview: " + military.preview(request));
        }).thenExecute(() -> {
            for (var request : requests) helper.assertTrue(military.begin(request).outcome() == WarMobilizationService.Outcome.ACCEPTED, "accept original person");
        }).thenWaitUntil(() -> {
            for (var request : requests) { var record = military.service().orElseThrow().find(request.id()).orElseThrow();
                helper.assertTrue(record.stage() == WarMobilization.Stage.ACTIVE, "wait for original NPC activation " + record.stage() + ":" + record.reviewReason()); }
        }).thenExecute(() -> {
            for (var npc : fixture.npcs()) {
                helper.assertTrue(!npc.isNoAi() && npc.getMainHandItem().is(Items.IRON_SWORD) && npc.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "original body equipped without disabling AI");
                fixture.sources().life().setHunger(npc.getCharacterId(), 550, server.overworld().getGameTime());
            }
        }).thenWaitUntil(() -> {
            for (var request : requests) helper.assertTrue(nativePorts.provisions().find(request.id()).orElseThrow().settlements().stream().anyMatch(s -> s.applied() && s.attempt().purpose() == ServiceProvision.Purpose.MEAL), "wait for real bread consumption");
        }).thenIdle(220);
        if (phase().equals("save")) {
            sequence.thenExecute(() -> {
                capture = new JsonObject(); capture.addProperty("scene", helper.absolutePos(new BlockPos(offset, 0, 0)).asLong());
                capture.addProperty("people", 3); capture.addProperty("stage", "ACTIVE");
                helper.assertTrue(nativePorts.reconcile(military.service().orElseThrow().records()).eligible(), "save phase shared evidence agrees");
                server.overworld().getDataStorage().saveAndJoin();
            }).thenSucceed(); return;
        }
        sequence.thenExecute(() -> {
            for (var request : requests) { helper.assertTrue(nativePorts.provisions().find(request.id()).orElseThrow().arrears(server.overworld().getGameTime()).signum() > 0, "unfunded elapsed wages are retained"); military.demobilize(request.id(), first.actorId()); }
        }).thenWaitUntil(() -> {
            for (var request : requests) helper.assertTrue(military.service().orElseThrow().find(request.id()).orElseThrow().stage() == WarMobilization.Stage.RELEASED, "release does not require an already funded treasury");
        }).thenExecute(() -> {
            for (int i = 0; i < 3; i++) { var npc = fixture.npcs().get(i); var request = requests.get(i);
                helper.assertTrue(!WorkCommitmentRuntime.blocksCivil(npc) && !ServiceProvisionRuntime.ownsNeeds(npc), "original person becomes available for civil work and needs");
                helper.assertTrue(npc.getProfession() == (i % 2 == 0 ? com.jgeted.sagadyssey.npc.profession.NpcProfession.BLACKSMITH : com.jgeted.sagadyssey.npc.profession.NpcProfession.WORKER), "profession retained");
                helper.assertTrue(npc.getMainHandItem().isEmpty() && npc.getItemBySlot(EquipmentSlot.CHEST).isEmpty() && npc.getPersonalInventory().getItem(1).is(Items.IRON_PICKAXE), "borrowed kit returned while private tool retained");
                helper.assertTrue(military.service().orElseThrow().find(request.id()).orElseThrow().hasOutstandingObligation(), "release records independent arrears");
            }
            helper.assertTrue(armoryBlock.countItem(Items.IRON_SWORD) == 3 && armoryBlock.countItem(Items.IRON_CHESTPLATE) == 3, "actual armory receives all kit");
            treasuryBlock.setItem(0, new ItemStack(Items.EMERALD, 64)); treasuryBlock.setChanged(); rights.bumpContext(treasury.id());
        }).thenWaitUntil(() -> {
            for (var request : requests) helper.assertTrue(nativePorts.provisions().find(request.id()).orElseThrow().arrears(server.overworld().getGameTime()).signum() == 0, "new real funds settle old closed debt");
        }).thenExecute(() -> {
            int paid = fixture.npcs().stream().mapToInt(npc -> npc.getPersonalInventory().count(s -> s.is(Items.EMERALD))).sum();
            helper.assertTrue(paid > 0 && paid + treasuryBlock.countItem(Items.EMERALD) == 64, "real treasury conservation; no wallet creation");
            helper.assertTrue(nativePorts.reconcile(military.service().orElseThrow().records()).eligible(), "whole native journal reconciliation");
            server.overworld().getDataStorage().saveAndJoin();
        }).thenSucceed();
    }
    static WarCandidateInspector.Sources sources(MinecraftServer server) {
        return new WarCandidateInspector.Sources(com.jgeted.sagadyssey.social.character.CharacterRegistryData.get(server), com.jgeted.sagadyssey.social.life.NpcLifeData.get(server),
                com.jgeted.sagadyssey.social.combat.InjuryData.get(server), com.jgeted.sagadyssey.social.service.ServiceRelationshipData.get(server),
                new WarCivilResponsibilities(com.jgeted.sagadyssey.npc.brain.persistence.NpcBrainData.get(server), com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData.get(server),
                        com.jgeted.sagadyssey.social.needs.SupplyFlowData.get(server), com.jgeted.sagadyssey.social.production.ProductionOrderData.get(server), com.jgeted.sagadyssey.npc.brain.command.NpcDutyData.get(server),
                        com.jgeted.sagadyssey.worldsim.lod.NpcLodData.get(server), com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.get(server)));
    }
    private static void force(MinecraftServer server, BlockPos scene, boolean forced) {
        for (int x = scene.getX() >> 4; x <= (scene.getX() + 11) >> 4; x++) for (int z = scene.getZ() >> 4; z <= (scene.getZ() + 11) >> 4; z++) server.overworld().setChunkForced(x, z, forced);
    }
    private static void resume(GameTestHelper helper) {
        try {
            var server = helper.getLevel().getServer(); var root = server.getWorldPath(LevelResource.ROOT);
            var proof = JsonParser.parseString(Files.readString(root.resolve("war-m2b2-save-proof.json"))).getAsJsonObject(); var scene = BlockPos.of(proof.get("scene").getAsLong());
            helper.assertTrue(proof.get("process").getAsLong() != ProcessHandle.current().pid(), "another real JVM");
            var runtime = WarServerEvents.existing(server).orElseThrow(); var plans = List.copyOf(new WarKitCodec().read(Files.readAllBytes(root.resolve("data/" + WarKitAdapter.FILE_NAME))));
            helper.assertTrue(plans.size() == 3, "three persisted original kit plans"); var first = plans.getFirst().request();
            runtime.bindAuthority((actor, army) -> actor.equals(first.actorId()) && army.equals(first.armyId()) ? WarCommandService.Permission.ALLOWED : WarCommandService.Permission.DENIED);
            var original = sources(server); var nativePorts = runtime.bindNativeMobilization(original); var military = runtime.mobilization().orElseThrow(); runtime.activate(); force(server, scene, true);
            var loaded = new ArrayList<com.jgeted.sagadyssey.npc.entity.NpcBase>(); int[] hungerBefore = {0};
            helper.startSequence().thenWaitUntil(() -> {
                loaded.clear(); for (var plan : plans) { var body = ItemLocation.personal(plan.request().characterId(), plan.body()).body(server, original.characters());
                    helper.assertTrue(body.isPresent(), "load original saved body"); loaded.add(body.orElseThrow()); }
                for (var npc : loaded) helper.assertTrue(!npc.isNoAi() && npc.getMainHandItem().is(Items.IRON_SWORD) && npc.getPersonalInventory().count(s -> s.is(Items.BREAD)) == 1, "real body and inventory survive JVM restart");
                helper.assertTrue(nativePorts.reconcile(military.service().orElseThrow().records()).eligible(), "whole native recovery agrees");
            }).thenExecute(() -> force(server, scene, false)).thenWaitUntil(() -> {
                helper.assertTrue(!server.overworld().hasChunkAt(scene) && loaded.stream().allMatch(npc -> server.overworld().getEntity(npc.getUUID()) == null), "actual scene chunk and bodies unload");
            }).thenExecute(() -> hungerBefore[0] = original.life().needs(plans.getFirst().request().characterId()).hunger()).thenIdle(220).thenExecute(() -> {
                helper.assertTrue(original.life().needs(plans.getFirst().request().characterId()).hunger() >= hungerBefore[0] + 2, "unloaded original needs accrue baseline without free food"); force(server, scene, true);
            }).thenWaitUntil(() -> {
                loaded.clear(); for (var plan : plans) { var body = ItemLocation.personal(plan.request().characterId(), plan.body()).body(server, original.characters()); helper.assertTrue(body.isPresent(), "reload exact original body UUID"); loaded.add(body.orElseThrow()); }
            }).thenExecute(() -> {
                for (var plan : plans) { helper.assertTrue(military.service().orElseThrow().find(plan.request().id()).orElseThrow().stage() == WarMobilization.Stage.ACTIVE, "restart/unload do not create a new enlistment"); military.demobilize(plan.request().id(), first.actorId()); }
            }).thenWaitUntil(() -> {
                for (var plan : plans) helper.assertTrue(military.service().orElseThrow().find(plan.request().id()).orElseThrow().stage() == WarMobilization.Stage.RELEASED, "recovered original service can demobilize");
            }).thenExecute(() -> {
                for (var npc : loaded) helper.assertTrue(!WorkCommitmentRuntime.blocksCivil(npc) && !ServiceProvisionRuntime.ownsNeeds(npc) && npc.getMainHandItem().isEmpty()
                        && npc.getPersonalInventory().count(s -> s.is(Items.IRON_PICKAXE)) == 1 && npc.getPersonalInventory().count(s -> s.is(Items.BREAD)) == 1, "no cloned gear, tool, or meal after recovery");
                var treasury = nativePorts.provisions().find(first.id()).orElseThrow().terms().treasury().resolve(server, original.characters()).orElseThrow();
                treasury.container().setItem(0, new ItemStack(Items.EMERALD, 64)); treasury.container().setChanged(); RightsStateData.get(server).bumpContext(treasury.id());
            }).thenWaitUntil(() -> {
                for (var plan : plans) helper.assertTrue(nativePorts.provisions().find(plan.request().id()).orElseThrow().arrears(server.overworld().getGameTime()).signum() == 0, "retired payroll is paid with real currency after restart");
            }).thenExecute(() -> {
                var treasury = nativePorts.provisions().find(first.id()).orElseThrow().terms().treasury().resolve(server, original.characters()).orElseThrow();
                helper.assertTrue(loaded.stream().mapToInt(npc -> npc.getPersonalInventory().count(s -> s.is(Items.EMERALD))).sum() + treasury.container().countItem(Items.EMERALD) == 64, "currency conserved across JVM and chunk reload");
                var armory = plans.getFirst().armory().resolve(server, original.characters()).orElseThrow();
                helper.assertTrue(armory.container().countItem(Items.IRON_SWORD) == 3 && armory.container().countItem(Items.IRON_CHESTPLATE) == 3, "all actual borrowed equipment returned");
                helper.assertTrue(nativePorts.reconcile(military.service().orElseThrow().records()).eligible(), "final shared/native reconciliation");
                capture = new JsonObject(); capture.addProperty("scene", scene.asLong()); capture.addProperty("people", 3); capture.addProperty("stage", "RELEASED"); capture.addProperty("priorProcess", proof.get("process").getAsLong());
                server.overworld().getDataStorage().saveAndJoin();
            }).thenSucceed();
        } catch (Exception exception) { throw new IllegalStateException("native resume failed", exception); }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void stopped(ServerStoppedEvent event) {
        if (!enabled() || capture == null || !(event.getServer() instanceof GameTestServer)) return;
        try {
             var root = event.getServer().getWorldPath(LevelResource.ROOT);
            var core = WarSavedData.readDisk(root.resolve("data"), new WarStateCodec(WarStateCodec.Limits.defaults())).orElseThrow(); if (!core.writable()) throw new IllegalStateException("war core did not save");
            var level = NbtIo.readCompressed(root.resolve("level.dat"), NbtAccounter.create(64 * 1024 * 1024L)).getCompoundOrEmpty("Data");
            capture.addProperty("gameTime", level.getLongOr("Time", 0L)); capture.addProperty("dayTime", level.getLongOr("DayTime", 0L)); capture.addProperty("process", ProcessHandle.current().pid());
            Files.writeString(root.resolve("war-m2b2-" + phase() + "-proof.json"), capture.toString(), StandardOpenOption.CREATE_NEW);
        } catch (Exception exception) { throw new IllegalStateException("native restart disk proof failed", exception); }
    }
}
