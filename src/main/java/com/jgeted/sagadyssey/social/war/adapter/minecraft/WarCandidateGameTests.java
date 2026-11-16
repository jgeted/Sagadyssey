package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.brain.command.NpcDutyData;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBrainData;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.identity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.social.production.ProductionOrderData;
import com.jgeted.sagadyssey.social.service.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.*;
import com.jgeted.sagadyssey.social.work.*;
import com.jgeted.sagadyssey.worldsim.lod.NpcLodData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import java.util.*;

/** 原生共享数据与真实NPC验证；不代表粮饷和全部M2动员闭环已通过。 */
@GameTestHolder("sagadyssey_war_m2b")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = "sagadyssey")
public final class WarCandidateGameTests {
    private static final Identifier EMPTY = Identifier.parse("sagadyssey_war_m2b:empty");
    private static boolean enabled() { return Boolean.getBoolean("sagadyssey.war.m2b.test"); }
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (!enabled() || !(event.getServer() instanceof GameTestServer)) return;
        try {
            var level = event.getServer().overworld(); level.getStructureManager().getOrCreate(EMPTY).load(level.holderLookup(Registries.BLOCK),
                    TagParser.parseCompoundFully("{size:[12,4,12],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
            if (Boolean.getBoolean("sagadyssey.war.m2b2.test")) level.getStructureManager().getOrCreate(Identifier.parse("sagadyssey_war_m2b2:empty"))
                    .load(level.holderLookup(Registries.BLOCK), TagParser.parseCompoundFully("{size:[12,4,12],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
        } catch (Exception exception) { throw new IllegalStateException("war M2b template failed", exception); }
    }
    @GameTestGenerator public static Collection<TestFunction> tests() {
        if (!enabled()) return List.of();
        return List.of(test("ten_real_people_read_only", WarCandidateGameTests::readOnly), test("consent_and_stale_body", WarCandidateGameTests::identity),
                test("cargo_and_injury_retain_facts", WarCandidateGameTests::responsibilities), test("ten_shared_labor_lifecycles", WarCandidateGameTests::labor),
                test("real_consumption_is_idempotent", WarCandidateGameTests::consumption), test("real_payment_is_idempotent", WarCandidateGameTests::payment),
                test("actual_equipment_issue_and_return", WarCandidateGameTests::equipment));
    }
    private static TestFunction test(String name, java.util.function.Consumer<GameTestHelper> body) { return new TestFunction("war_m2b", name, EMPTY.toString(), 600, 0, true, body); }
    record Fixture(WarCandidateInspector inspector, WarCandidateInspector.Sources sources, List<NbtSavedData> ledgers,
                           List<NpcBase> npcs, List<WarMilitiaRequest> requests) {}
    static Fixture fixture(GameTestHelper helper, int count) {
        return fixture(helper, count, UUID.randomUUID());
    }
    static Fixture fixture(GameTestHelper helper, int count, UUID actor) {
        var server = helper.getLevel().getServer(); var lookup = server.registryAccess(); long now = server.overworld().getGameTime();
        var characters = CharacterRegistryData.get(server); UUID army = UUID.randomUUID(), unit = UUID.randomUUID();
        characters.ensureNpcCharacter(actor, "领主人物夹具", NpcSocialClass.COMMONER, NpcOffice.NONE, "neutral", now);
        var life = NpcLifeData.get(server); var npcs = new ArrayList<NpcBase>(); var requests = new ArrayList<WarMilitiaRequest>();
        var serviceTag = ServiceRelationshipData.get(server).save(new CompoundTag(), lookup); var relations = MinecraftApi.listOfType(serviceTag, "Relationships", Tag.TAG_COMPOUND);
        for (int i = 0; i < count; i++) {
            var npc = helper.spawn(NpcEntityTypes.NPC_BASE.get(), new BlockPos(1 + i, 1, 1)); npc.setNoGravity(true); npc.setOnGround(true);
            npc.setProfession(i % 2 == 0 ? NpcProfession.BLACKSMITH : NpcProfession.WORKER); npc.setCommand(NpcCommand.WORK);
            for (var slot : EquipmentSlot.values()) npc.setItemSlot(slot, ItemStack.EMPTY); npc.getPersonalInventory().clearContent(); npc.getTransportInventory().clearContent();
            if (i % 2 == 0) npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            characters.ensureNpcCharacter(npc.getCharacterId(), "民兵候选", NpcSocialClass.COMMONER, NpcOffice.NONE, "neutral", now);
            characters.bindNpcBody(npc.getCharacterId(), helper.getLevel().dimension().identifier().toString(), npc.getUUID(), now);
            life.onRecruited(npc, actor, now); life.setHunger(npc.getCharacterId(), 300 + i, now); npcs.add(npc);
            // 测试准备阶段明确写入原生危险职责同意；生产预检本身从不授予此权限。
            relations.add(new ServiceRelationship(UUID.randomUUID(), actor, npc.getCharacterId(), ServiceBasis.TEMPORARY_DELEGATION,
                    Set.of(AuthorityScope.ASSIGN_ROLE, AuthorityScope.DEFEND_PRINCIPAL, AuthorityScope.MANAGE_ISSUED_EQUIPMENT), List.of(),
                    new ServiceLimits(null, helper.getLevel().dimension().identifier().toString(), true, false), ServiceStatus.ACTIVE, now, null, 0, "隔离测试显式同意").save());
            requests.add(new WarMilitiaRequest(UUID.randomUUID(), actor, army, unit, npc.getCharacterId(), InitialWarDefinitions.TROOPS.getFirst(), 1,
                    WarMilitiaRequest.Kind.MILITIA, WarMilitiaRequest.Role.COMBATANT, new WarMilitiaRequest.Wage(WarKey.parse("minecraft:emerald"), 1, 24_000), now));
        }
        var services = ServiceRelationshipData.FACTORY.deserializer().apply(serviceTag, lookup); server.overworld().getDataStorage().set(ServiceRelationshipData.FACTORY.type(ServiceRelationshipData.DATA_NAME), services);
        var injuries = InjuryData.get(server); var brain = NpcBrainData.get(server); var fieldwork = FieldworkData.get(server); var supply = SupplyFlowData.get(server);
        var production = ProductionOrderData.get(server); var duties = NpcDutyData.get(server); var lod = NpcLodData.get(server); var development = SettlementDevelopmentData.get(server);
        var sources = new WarCandidateInspector.Sources(characters, life, injuries, services, new WarCivilResponsibilities(brain, fieldwork, supply, production, duties, lod, development));
        return new Fixture(new WarCandidateInspector(server, sources, InitialWarDefinitions.LOADOUTS), sources,
                List.of(characters, life, injuries, services, brain, fieldwork, supply, production, duties, lod, development), List.copyOf(npcs), List.copyOf(requests));
    }
    private static void readOnly(GameTestHelper helper) {
        var f = fixture(helper, 10); var lookup = helper.getLevel().registryAccess(); var before = f.ledgers().stream().map(d -> d.save(new CompoundTag(), lookup)).toList();
        f.ledgers().forEach(d -> d.setDirty(false)); var inventories = f.npcs().stream().map(n -> n.getPersonalInventory().revision()).toList();
        var reports = f.inspector().inspect(f.requests(), helper.getLevel().getGameTime());
        helper.assertTrue(reports.size() == 10 && reports.stream().allMatch(WarCandidateReport::observedFactsClear), "ten original people produce complete observation");
        helper.assertTrue(reports.stream().filter(r -> r.equipmentGaps().contains(WarEquipmentSnapshot.Mismatch.PRIMARY_WEAPON)).count() == 5, "equipment shortages are separate from labor qualification");
        for (int i = 0; i < f.ledgers().size(); i++) helper.assertTrue(!f.ledgers().get(i).isDirty() && before.get(i).equals(f.ledgers().get(i).save(new CompoundTag(), lookup)), "native ledger unchanged " + i);
        for (int i = 0; i < 10; i++) helper.assertTrue(f.npcs().get(i).getPersonalInventory().revision() == inventories.get(i) && !f.npcs().get(i).isNoAi(), "inventory and original AI retained");
        helper.succeed();
    }
    private static void identity(GameTestHelper helper) {
        var f = fixture(helper, 2); var first = f.requests().getFirst(); var second = f.requests().get(1); long now = helper.getLevel().getGameTime();
        f.sources().characters().bindNpcBody(second.characterId(), helper.getLevel().dimension().identifier().toString(), UUID.randomUUID(), now);
        var stale = f.sources().characters().bodyOf(second.characterId());
        var wrongActor = new WarMilitiaRequest(first.id(), second.characterId(), first.armyId(), first.unitId(), first.characterId(), first.loadout(), 1, first.kind(), first.role(), first.wage(), now);
        var denied = f.inspector().inspect(List.of(wrongActor), now).getFirst();
        helper.assertTrue(denied.person().isEmpty() && denied.equipment().isEmpty(), "native consent denial exposes no private observation");
        var unavailable = f.inspector().inspect(List.of(second), now).getFirst();
        helper.assertTrue(unavailable.person().isEmpty() && f.sources().characters().bodyOf(second.characterId()).equals(stale), "stale body is not repaired by finding another loaded entity");
        helper.succeed();
    }
    private static void responsibilities(GameTestHelper helper) {
        var f = fixture(helper, 1); var npc = f.npcs().getFirst(); var request = f.requests().getFirst(); long now = helper.getLevel().getGameTime();
        npc.getTransportInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        var wound = InjuryService.recordHit(f.sources().injuries(), UUID.randomUUID(), request.characterId(), null, WoundType.CUT, 1, 0, now, null);
        f.sources().injuries().setDirty(false); var report = f.inspector().inspect(f.requests(), now).getFirst();
        helper.assertTrue(report.problems().stream().anyMatch(p -> p.code().equals("CARRIED_CARGO_REQUIRES_HANDOFF")), "actual civilian cargo blocks immediate handoff");
        helper.assertTrue(!f.sources().injuries().observeTriage(request.characterId()).incapacitated()
                && report.problems().stream().noneMatch(p -> p.code().equals("HEALTH_PREVENTS_SERVICE")), "minor injury follows original health rules without declaring all wounds unfit");
        helper.assertTrue(npc.getTransportInventory().getItem(0).getCount() == 64 && f.sources().injuries().wound(wound.id()).equals(wound) && !f.sources().injuries().isDirty(), "cargo and wound evidence preserved");
        helper.succeed();
    }
    private static void labor(GameTestHelper helper) {
        var f = fixture(helper, 10); WorkCommitmentService work;
        try { work = WorkCommitmentRuntime.activate(helper.getLevel().getServer()); } catch (Exception e) { throw new IllegalStateException(e); }
        var adapter = new WarLaborAdapter(work, f.inspector()); Map<UUID, Map<WarMobilization.Stage, WarMobilization.Receipt>> receipts = new HashMap<>();
        f.requests().forEach(r -> receipts.put(r.id(), new EnumMap<>(WarMobilization.Stage.class)));
        helper.startSequence().thenWaitUntil(() -> step(helper, adapter, f, WarMobilization.Stage.CLAIM, receipts))
                .thenExecute(() -> {
                    for (var npc : f.npcs()) helper.assertTrue(WorkCommitmentRuntime.blocksCivil(npc)
                            && !com.jgeted.sagadyssey.social.life.NpcSchedulePlanner.allowsWork(npc)
                            && FieldworkPlanProvider.choose(npc, helper.getLevel().getGameTime()).isEmpty(), "original civilian selectors respect preparing ownership");
                }).thenWaitUntil(() -> step(helper, adapter, f, WarMobilization.Stage.ACTIVATE, receipts))
                .thenWaitUntil(() -> step(helper, adapter, f, WarMobilization.Stage.RELEASE_LABOR, receipts))
                .thenExecute(() -> {
                    for (int i = 0; i < f.npcs().size(); i++) {
                        var npc = f.npcs().get(i); helper.assertTrue(!WorkCommitmentRuntime.blocksCivil(npc) && !npc.isNoAi()
                                && npc.getProfession() == (i % 2 == 0 ? NpcProfession.BLACKSMITH : NpcProfession.WORKER), "release preserves original body profession and execution mode");
                        helper.assertTrue(work.find(f.requests().get(i).id()).orElseThrow().receipts().size() == 3, "shared durable claim activation and release receipts retained");
                    }
                }).thenSucceed();
    }
    private static void step(GameTestHelper helper, WarLaborAdapter adapter, Fixture fixture, WarMobilization.Stage stage,
                             Map<UUID, Map<WarMobilization.Stage, WarMobilization.Receipt>> receipts) {
        boolean complete = true;
        for (var request : fixture.requests()) {
            if (receipts.get(request.id()).containsKey(stage)) continue;
            var effect = new Effect(request, stage, WarMobilization.operationId(request.id(), stage), WarMobilization.cancellationId(request.id(), stage), receipts.get(request.id()), helper.getLevel().getGameTime());
            var reply = adapter.lookup(effect); if (reply.status() == Status.NOT_APPLIED) reply = adapter.apply(effect);
            helper.assertTrue(reply.status() == Status.APPLIED || reply.status() == Status.PENDING || reply.status() == Status.UNAVAILABLE, "real labor adapter outcome " + reply);
            if (reply.status() == Status.APPLIED) receipts.get(request.id()).put(stage, reply.receipt().orElseThrow()); else complete = false;
        }
        helper.assertTrue(complete, "waiting for exact shared work checkpoint " + stage);
    }
    private static PhysicalItemService items(GameTestHelper helper) {
        try { return PhysicalItemRuntime.activate(helper.getLevel().getServer()); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static WitnessedItemEndpoint endpoint(GameTestHelper helper, NpcBase npc) {
        var endpoint = WitnessedItemEndpoint.personal(npc); var actor = SubjectRef.character(npc.getCharacterId());
        RightsStateData.get(helper.getLevel().getServer()).ensureContext(endpoint.id(), "npc:" + npc.getUUID(), actor, actor, AccessPolicy.PRIVATE); return endpoint;
    }
    private static void consumption(GameTestHelper helper) {
        var f = fixture(helper, 1); var npc = f.npcs().getFirst(); npc.getPersonalInventory().setItem(0, new ItemStack(Items.BREAD, 5));
        var endpoint = endpoint(helper, npc); var service = items(helper); var rights = RightsStateData.get(helper.getLevel().getServer());
        var request = new PhysicalItemOperation.Consumption(UUID.randomUUID(), endpoint.id(), SubjectRef.character(npc.getCharacterId()), ItemFingerprint.of(new ItemStack(Items.BREAD)),
                1, "", "gametest-meal:" + npc.getCharacterId(), rights.context(endpoint.id()).revision(), helper.getLevel().getGameTime());
        var intent = PhysicalItemOperation.Intent.consume(request); var endpoints = Map.of(endpoint.id(), endpoint);
        helper.startSequence().thenWaitUntil(() -> applied(helper, service.submit(intent, endpoints, helper.getLevel().getGameTime())))
                .thenExecute(() -> {
                    applied(helper, service.submit(intent, endpoints, helper.getLevel().getGameTime()));
                    helper.assertTrue(npc.getPersonalInventory().getItem(0).getCount() == 4, "replayed consumption subtracts real bread exactly once");
                    helper.assertTrue(endpoint.witness(intent.id()).isPresent() && rights.itemOperationWitness(intent.id()) != null, "physical owner and rights have matching domain evidence");
                    var different = new PhysicalItemOperation.Consumption(request.operationId(), request.container(), request.actor(), request.item(), 2, request.authority(), request.purpose(), request.expectedRevision(), request.requestedAt());
                    helper.assertTrue(service.submit(PhysicalItemOperation.Intent.consume(different), endpoints, helper.getLevel().getGameTime()).status() == PhysicalItemService.Status.CONFLICT
                            && npc.getPersonalInventory().getItem(0).getCount() == 4, "same ID with changed consumption cannot apply");
                }).thenSucceed();
    }
    private static void payment(GameTestHelper helper) {
        var f = fixture(helper, 2); var payer = f.npcs().getFirst(); var payee = f.npcs().get(1);
        payer.getPersonalInventory().setItem(0, new ItemStack(Items.EMERALD, 5)); payee.getPersonalInventory().clearContent();
        var source = endpoint(helper, payer); var destination = endpoint(helper, payee); var rights = RightsStateData.get(helper.getLevel().getServer()); var service = items(helper);
        var request = new TransferRequest(UUID.randomUUID(), SubjectRef.character(payer.getCharacterId()), TransferIntent.PAYMENT,
                List.of(new TransferLeg(source.id(), destination.id(), ItemFingerprint.of(new ItemStack(Items.EMERALD)), 2)),
                List.of(new ExpectedRevision(source.id(), rights.context(source.id()).revision()), new ExpectedRevision(destination.id(), rights.context(destination.id()).revision())), "gametest-physical-wage", helper.getLevel().getGameTime());
        var intent = PhysicalItemOperation.Intent.transfer(request); var endpoints = Map.of(source.id(), source, destination.id(), destination);
        helper.startSequence().thenWaitUntil(() -> applied(helper, service.submit(intent, endpoints, helper.getLevel().getGameTime())))
                .thenExecute(() -> {
                    applied(helper, service.submit(intent, endpoints, helper.getLevel().getGameTime()));
                    helper.assertTrue(payer.getPersonalInventory().count(s -> s.is(Items.EMERALD)) == 3 && payee.getPersonalInventory().count(s -> s.is(Items.EMERALD)) == 2,
                            "real emeralds moved exactly once; no abstract wallet is credited");
                }).thenSucceed();
    }
    private static void applied(GameTestHelper helper, PhysicalItemService.Reply reply) {
        helper.assertTrue(reply.status() == PhysicalItemService.Status.APPLIED, "waiting for physical item proof: " + reply.status() + " " + reply.reason());
    }
    private static void equipment(GameTestHelper helper) {
        var f = fixture(helper, 2); var stock = f.npcs().getFirst(); var soldier = f.npcs().get(1);
        stock.getPersonalInventory().setItem(0, new ItemStack(Items.IRON_SWORD)); stock.getPersonalInventory().setItem(1, new ItemStack(Items.IRON_CHESTPLATE));
        var source = endpoint(helper, stock); var target = WitnessedItemEndpoint.equipment(soldier); var rights = RightsStateData.get(helper.getLevel().getServer());
        var custodian = SubjectRef.character(soldier.getCharacterId()); rights.ensureContext(target.id(), "npc_equipment:" + soldier.getUUID(), custodian, custodian, AccessPolicy.PRIVATE);
        var sword = ItemFingerprint.of(new ItemStack(Items.IRON_SWORD)); var armor = ItemFingerprint.of(new ItemStack(Items.IRON_CHESTPLATE)); var service = items(helper);
        var issue = PhysicalItemOperation.Intent.transfer(new TransferRequest(UUID.randomUUID(), SubjectRef.character(stock.getCharacterId()), TransferIntent.ISSUE,
                List.of(new TransferLeg(source.id(), target.id(), sword, 1), new TransferLeg(source.id(), target.id(), armor, 1)),
                List.of(new ExpectedRevision(source.id(), rights.context(source.id()).revision()), new ExpectedRevision(target.id(), rights.context(target.id()).revision())), "gametest-issued-kit", helper.getLevel().getGameTime()));
        var endpoints = Map.of(source.id(), source, target.id(), target); PhysicalItemOperation.Intent[] returning = {null};
        helper.startSequence().thenWaitUntil(() -> applied(helper, service.submit(issue, endpoints, helper.getLevel().getGameTime())))
                .thenExecute(() -> {
                    helper.assertTrue(soldier.getMainHandItem().is(Items.IRON_SWORD) && soldier.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "real items enter actual hand and chest, not wrong empty armor slots");
                    helper.assertTrue(rights.claimAt(target.id(), 4, sword).beneficialOwner().equals(SubjectRef.character(stock.getCharacterId())), "issue preserves original title while soldier receives custody");
                    returning[0] = PhysicalItemOperation.Intent.transfer(new TransferRequest(UUID.randomUUID(), custodian, TransferIntent.RETURN,
                            List.of(new TransferLeg(target.id(), source.id(), sword, 1), new TransferLeg(target.id(), source.id(), armor, 1)),
                            List.of(new ExpectedRevision(source.id(), rights.context(source.id()).revision()), new ExpectedRevision(target.id(), rights.context(target.id()).revision())), "gametest-returned-kit", helper.getLevel().getGameTime()));
                }).thenWaitUntil(() -> applied(helper, service.submit(returning[0], endpoints, helper.getLevel().getGameTime())))
                .thenExecute(() -> {
                    applied(helper, service.submit(issue, endpoints, helper.getLevel().getGameTime()));
                    applied(helper, service.submit(returning[0], endpoints, helper.getLevel().getGameTime()));
                    helper.assertTrue(soldier.getMainHandItem().isEmpty() && soldier.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                            && stock.getPersonalInventory().count(s -> s.is(Items.IRON_SWORD)) == 1 && stock.getPersonalInventory().count(s -> s.is(Items.IRON_CHESTPLATE)) == 1, "return and original issue replay do not create equipment");
                }).thenSucceed();
    }
}
