package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData;
import com.jgeted.sagadyssey.core.structure.NaturalVillageR1GameTests;
import com.jgeted.sagadyssey.core.structure.NaturalVillageBuilding;
import com.jgeted.sagadyssey.core.structure.NaturalVillageLayout;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkGameTests;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.NeedRequestData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** 集中复用受影响的真实劳动用例，另核对正式启用的权限与旧账保全。 */
@GameTestHolder("sagadyssey_redesign")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid = "sagadyssey")
public final class SettlementCoordinatorGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        if (!(event.getServer() instanceof net.minecraft.gametest.framework.GameTestServer)) return;
        var level = event.getServer().overworld();
        try {
            for (String name : List.of("fieldwork_empty", "empty")) {
                level.getStructureManager().getOrCreate(net.minecraft.resources.Identifier
                        .fromNamespaceAndPath("sagadyssey_redesign", name)).load(
                        level.holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                        net.minecraft.nbt.TagParser.parseCompoundFully("{size:[96,10,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
            }
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    @BeforeBatch(batch = "redesign_shared")
    public static void setup(ServerLevel level) {
        FieldworkGameTests.templates(level);
        level.getServer().tickRateManager().requestGameToSprint(20000);
    }

    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        var tests = new ArrayList<TestFunction>();
        if (Boolean.getBoolean("sagadyssey.redesign.guardOnly")) {
            return List.of(new TestFunction("redesign_shared", "parcel_guards_and_return_path",
                    "sagadyssey_redesign:empty", 1600, 0, true, helper -> activation(helper, true)));
        }
        tests.add(test("existing_harvest_replant_deposit", FieldworkGameTests::farmerHarvestReplantsAndDelivers, 800));
        tests.add(test("existing_eighty_block_navigation", FieldworkGameTests::workerDiscoversAndWalksToEightyBlockTarget, 1500));
        tests.add(test("existing_full_destination_recovery", FieldworkGameTests::fullDestinationKeepsCargoAndRecovers, 900));
        tests.add(test("existing_shared_block_no_duplication", FieldworkGameTests::twoWorkersCannotDuplicateOneBlock, 1200));
        tests.add(test("existing_logging_receipts", FieldworkGameTests::loggingIsOneBlockPerReceipt, 1200));
        tests.add(test("existing_scaffold_conservation", FieldworkGameTests::scaffoldBuildAndRecoveryConserveOriginalMaterial, 1600));
        tests.add(test("existing_borrowed_tool_title", FieldworkGameTests::borrowedToolRequiresUseGrantAndRetainsOwner, 1200));
        tests.add(test("existing_inventory_capacity", FieldworkGameTests::fullPersonalBagDoesNotDestroyBlockOrWearTool, 900));
        tests.add(new TestFunction("redesign_shared", "activation_authority_and_original_ledgers",
                "sagadyssey_redesign:empty", 1600, 0, true, SettlementCoordinatorGameTests::activation));
        return tests;
    }

    private static TestFunction test(String name, Consumer<GameTestHelper> action, int limit) {
        return new TestFunction("redesign_shared", name, "sagadyssey_redesign:fieldwork_empty", limit, 0, true, action);
    }

    private static Map<String, String> ledgers(MinecraftServer server) {
        var lookup = server.registryAccess();
        return Map.of("people", CharacterRegistryData.get(server).save(new CompoundTag(), lookup).toString(),
                "homes", HouseholdData.get(server).save(new CompoundTag(), lookup).toString(),
                "life", NpcLifeData.get(server).save(new CompoundTag(), lookup).toString(),
                "rights", RightsStateData.get(server).save(new CompoundTag(), lookup).toString(),
                "needs", NeedRequestData.get(server).save(new CompoundTag(), lookup).toString(),
                "routes", SupplyFlowData.get(server).save(new CompoundTag(), lookup).toString());
    }

    /** Controlled geometry/knowledge inputs only; assertions call the real permission and return-path checks. */
    private static void parcelGuards(GameTestHelper helper, NaturalVillageBuilding village, CompoundTag initial) {
        var level = helper.getLevel();
        var worker = NaturalVillageR1GameTests.actor(level, village, 2);
        var point = NaturalVillageLayout.at(village, new BlockPos(37, 0, 19));
        for (var cell : BlockPos.betweenClosed(point.offset(-2, 0, -2), point.offset(2, 3, 2))) {
            level.getChunkAt(cell);
            level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        }
        level.setBlock(point, Blocks.STONE.defaultBlockState(), 3);
        var row = initial.copy();
        var observation = new CompoundTag();
        observation.store("Observer", UUIDUtil.CODEC, worker.getCharacterId());
        observation.putString("State", NbtUtils.writeBlockState(level.getBlockState(point)).toString());
        row.getCompoundOrEmpty("ObservedResources").put(Long.toString(point.asLong()), observation);
        helper.assertTrue(SettlementResourceSurvey.allowed(worker, row, point), "authorized, observed natural point is eligible");
        var unlicensed = row.copy(); unlicensed.remove("Parcel");
        helper.assertTrue(!SettlementResourceSurvey.allowed(worker, unlicensed, point), "observation alone does not grant land permission");
        helper.assertTrue(!SettlementResourceSurvey.inParcel(row, NaturalVillageLayout.at(village, new BlockPos(48, 0, 19)))
                && !SettlementResourceSurvey.inParcel(row, NaturalVillageLayout.at(village, new BlockPos(37, -4, 19)))
                && !SettlementResourceSurvey.inParcel(row, NaturalVillageLayout.at(village, new BlockPos(37, 6, 19))), "horizontal and vertical limits remain bounded");
        var edited = row.copy(); edited.getCompoundOrEmpty("ProtectedEdits").putLong(Long.toString(point.asLong()), level.getGameTime());
        helper.assertTrue(!SettlementResourceSurvey.allowed(worker, edited, point), "later protected player edit revokes the old observation");
        level.setBlock(point, Blocks.COBBLESTONE.defaultBlockState(), 3);
        helper.assertTrue(!SettlementResourceSurvey.allowed(worker, row, point), "artificial replacement is not the previously observed ore");
        level.setBlock(point, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(point.above(), Blocks.CHEST.defaultBlockState(), 3);
        helper.assertTrue(!SettlementResourceSurvey.allowed(worker, row, point), "do not undermine a container or facility");
        level.setBlock(point.above(), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(point.east(), Blocks.WATER.defaultBlockState(), 3);
        helper.assertTrue(!SettlementResourceSurvey.allowed(worker, row, point), "water hazard invalidates extraction");
        level.setBlock(point.east(), Blocks.AIR.defaultBlockState(), 3);
        worker.setProfession(NpcProfession.FARMER);
        helper.assertTrue(!SettlementResourceSurvey.allowed(worker, row, point), "unqualified profession cannot inherit mining permission");
        worker.setProfession(NpcProfession.WORKER);
        helper.assertTrue(SettlementResourceSurvey.allowed(worker, row, point), "removing only the controlled blockers restores eligibility");
        var bridge = point.above(5);
        for (var cell : BlockPos.betweenClosed(bridge.offset(-2, -1, -2), bridge.offset(4, 3, 2))) level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        for (int i=0;i<3;i++) level.setBlock(bridge.east(i), Blocks.STONE.defaultBlockState(), 3);
        helper.assertTrue(SettlementSurveyNavigation.connected(worker, bridge.above(), bridge.east(2).above(), null), "existing three-block return bridge is connected");
        helper.assertTrue(!SettlementSurveyNavigation.connected(worker, bridge.above(), bridge.east(2).above(), bridge.east()), "mining the sole return support is rejected before world mutation");
        SettlementRegistryData.get(level.getServer()).registerScenarioBuilding(level.getServer(), village.dimension(), point,
                "sagadyssey:guard_test_other_building", "house", "small", UUID.randomUUID(), level.getGameTime());
        helper.assertTrue(!SettlementResourceSurvey.allowed(worker, row, point), "another registered building protects its surrounding ground");
    }

    private static void activation(GameTestHelper helper) {
        activation(helper, false);
    }

    private static void activation(GameTestHelper helper, boolean guards) {
        var village = NaturalVillageR1GameTests.fixture(helper, 1024, GrowthLayout.TEMPLATE);
        boolean[] configured = {false}, completed = {false};
        helper.onEachTick(() -> {
            if (completed[0]) return;
            var server = helper.getLevel().getServer();
            var life = NaturalVillageLifeData.get(server).village(village.buildingKey());
            if (life == null || !life.getBooleanOr("Ready", false)) return;
            if (!configured[0]) {
                for (int i = 0; i < 3; i++) NaturalVillageR1GameTests.actor(helper.getLevel(), village, i).setCommand(NpcCommand.STAY);
                configured[0] = true;
            }
            var growth = SettlementGrowthData.existing(server);
            if (growth == null || growth.site(village.buildingKey()) == null) return;
            var data = SettlementCoordinatorData.get(server);
            var before = ledgers(server);
            var original = growth.site(village.buildingKey());
            long now = helper.getLevel().getGameTime();
            helper.assertTrue(data.activate(server, village.buildingKey(), UUID.randomUUID(), now).equals("UNAUTHORIZED"),
                    "unrelated person cannot activate a resource parcel or policy");
            helper.assertTrue(data.site(village.buildingKey()) == null && ledgers(server).equals(before),
                    "denied activation leaves original ledgers and site untouched");
            var official = HouseholdData.get(server).byId(NaturalVillageLifeData.home(village)).headCharacterId();
            helper.assertTrue(data.activate(server, village.buildingKey(), official, now).equals("ACTIVATED"), "formal authorized activation");
            var activated = data.site(village.buildingKey());
            helper.assertTrue(ledgers(server).equals(before) && growth.site(village.buildingKey()).equals(original),
                    "activation preserves original people, rights, needs, routes and construction");
            for (int i = 0; i < 20; i++) {
                helper.assertTrue(data.activate(server, village.buildingKey(), official, now).equals("ALREADY_ACTIVE"), "repeat is idempotent");
                helper.assertTrue(data.site(village.buildingKey()).equals(activated), "no duplicate task identities or grants");
            }
            helper.assertTrue(ledgers(server).equals(before), "repeated activation leaves original obligations unchanged");
            completed[0] = true;
            if (guards) parcelGuards(helper, village, activated);
            helper.succeed();
        });
    }
}
