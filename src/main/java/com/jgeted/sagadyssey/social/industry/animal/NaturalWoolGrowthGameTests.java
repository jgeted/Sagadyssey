package com.jgeted.sagadyssey.social.industry.animal;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.config.SagadysseyConfig;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.social.growth.SettlementCoordinatorData;
import com.jgeted.sagadyssey.social.growth.SettlementDevelopmentLayout;
import com.jgeted.sagadyssey.social.industry.common.EntityResourceActionData;
import com.jgeted.sagadyssey.social.industry.common.SettlementIndustryAccess;
import com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.social.politics.NaturalPolityData;
import com.jgeted.sagadyssey.social.production.ProductionOrderData;
import com.jgeted.sagadyssey.social.production.ProductionOrderStatus;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.EventHooks;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Two real uses compete for the same sheep's wool; every intervention is initialization-only. */
@GameTestHolder("sagadyssey_playability_fix")
@PrefixGameTestTemplate(false)
public final class NaturalWoolGrowthGameTests {
    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        return System.getProperty("sagadyssey.playability.phase", "").equals("natural-wool-growth")
                ? List.of(new TestFunction("playability_fix", "natural_wool_growth", "sagadyssey_playability_fix:empty",
                        48000, 0, true, NaturalWoolGrowthGameTests::run)) : List.of();
    }

    private static void run(GameTestHelper h) {
        var level = h.getLevel(); var server = level.getServer();
        var village = NaturalVillageR1GameTests.fixture(h, 0, SettlementDevelopmentLayout.INDUSTRY_TEMPLATE);
        level.setDayTime(1000);
        level.getGameRules().set(GameRules.MOB_GRIEFING, true, server);
        server.tickRateManager().requestGameToSprint(48200);
        UUID[] privateBed = {null}; Sheep[] sheep = {null};
        boolean[] observedShorn = {false}, observedRegrowth = {false};
        long[] readyAt = {0};
        var stop = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("industry-stop-request.json");
        h.onEachTick(() -> {
            if (java.nio.file.Files.exists(stop)) { h.fail("frozen wall-clock budget reached; retain world and stop normally"); return; }
            if (privateBed[0] == null && level.getGameTime() >= 1600) { h.fail("initialization did not become ready within frozen 1600 ticks"); return; }
            var worker = NaturalVillageR1GameTests.actor(level, village, 2);
            var coordinator = SettlementCoordinatorData.existing(server);
            var site = coordinator == null ? null : coordinator.site(village.buildingKey());
            if (worker == null || site == null) return;
            var output = SupplyFlowData.get(server).pointOrNull(NaturalVillageEconomyData.storeId(village, 2));
            if (output == null) return;
            if (privateBed[0] == null) {
                var development = com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.existing(server);
                if (development == null || development.site(village.buildingKey()) == null) return;
                h.assertTrue(level.getGameTime() < 1600, "bounded initialization, included in total 48000 ticks");
                h.assertTrue(!site.getCompoundOrEmpty("Jobs").getCompoundOrEmpty("BED").getStringOr("State", "").equals("DONE"),
                        "test begins before original bed manufacture");
                SagadysseyConfig.ENABLE_SETTLEMENT_INDUSTRIES.set(true);
                h.assertTrue(SettlementIndustryAccess.resident(worker)
                        && !SettlementIndustryAccess.fixture(worker, "sagadyssey.industry.native"), "normal industry admission");
                // Initial scenario: no wool anywhere, original public housing need remains genuine.
                int removedWool = 0;
                for (int i = 0; i < 4; i++) {
                    var store = NaturalPolityData.inventory(server, village, i);
                    for (int slot = 0; slot < store.getContainerSize(); slot++) if (store.getItem(slot).is(Items.WHITE_WOOL)) {
                        removedWool += store.getItem(slot).getCount(); store.setItem(slot, ItemStack.EMPTY);
                    }
                    RightsStateData.get(server).bumpContext(NaturalVillageEconomyData.storeId(village, i));
                }
                var stock = SupplyFlowService.container(server, output);
                int slot = java.util.stream.IntStream.range(0, stock.getContainerSize()).filter(i -> stock.getItem(i).isEmpty()).findFirst().orElseThrow();
                stock.setItem(slot, new ItemStack(Items.OAK_PLANKS, 3));
                RightsStateData.get(server).bumpContext(output.containerId());
                worker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.SHEARS));
                var center = NaturalVillageLayout.at(village, new BlockPos(20, 1, 25));
                var first = NaturalVillageLayout.at(village, new BlockPos(14, 1, 15));
                var last = NaturalVillageLayout.at(village, new BlockPos(28, 4, 38));
                worker.setWorkZone(new BlockPos(Math.min(first.getX(), last.getX()), first.getY(), Math.min(first.getZ(), last.getZ())),
                        new BlockPos(Math.max(first.getX(), last.getX()), last.getY(), Math.max(first.getZ(), last.getZ())));
                // Finite pasture, no ongoing grass replacement or forced regrowth.
                for (var cell : BlockPos.betweenClosed(new BlockPos(18,0,22), new BlockPos(24,0,28))) {
                    var at = NaturalVillageLayout.at(village, cell);
                    level.setBlockAndUpdate(at, Blocks.GRASS_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(at.above(), Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(at.above(2), Blocks.AIR.defaultBlockState());
                }
                sheep[0] = EntityType.SHEEP.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                sheep[0].snapTo(center.getX()+.5, center.getY(), center.getZ()+.5, 0, 0);
                EventHooks.finalizeMobSpawn(sheep[0], level, level.getCurrentDifficultyAt(center), EntitySpawnReason.NATURAL, null);
                sheep[0].setColor(DyeColor.WHITE);
                // Initial bounded test habitat: normal sheep AI and grass eating remain enabled.
                // This deliberately does not claim unrestricted wild roaming was verified.
                sheep[0].setHomeTo(center, 6);
                h.assertTrue(level.addFreshEntity(sheep[0]) && sheep[0].getPersistentData().getBooleanOr("SagadysseyNaturalSheep", false),
                        "one original sheep enters through the natural spawn event, not an owned fixture source");
                h.assertTrue(worker.isWorkAllowedAt(center) && sheep[0].isAlive() && !sheep[0].isNoAi(),
                        "live normal-AI sheep is inside the explicitly configured work area");
                h.assertTrue(com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes.walkable(worker, center)
                        && com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes.plan(worker, center, false) != null,
                        "existing authorized gate route and walkable pasture; completion still requires the actual walk");
                // Separate lawful private workshop order provides a second actual use, not fake sheep demand.
                privateBed[0] = WorkshopTask.requestBed(worker, "natural-wool-private-bed", output,
                        NaturalVillageLayout.at(village, NaturalVillageLayout.INDUSTRY_BENCH), level.getGameTime()).id();
                readyAt[0] = level.getGameTime();
                Sagadyssey.LOGGER.info("NATURAL_WOOL_INITIAL tick={} person={} sheep={} removedTemplateWool={} addedPrivatePlanks=3 shears=1 privateOrder={} actualHousing=original-bed pasture=49 mobGriefing=true initialRoamRadius=6 configuredWorkZone=14,1,15..28,4,38",
                        readyAt[0], worker.getCharacterId(), sheep[0].getUUID(), removedWool, privateBed[0]);
                return;
            }
            if (sheep[0].isSheared()) observedShorn[0] = true;
            else if (observedShorn[0]) observedRegrowth[0] = true;
            var data = EntityResourceActionData.existing(server);
            if (data == null) return;
            var actions = data.actions().stream().filter(a -> {
                var source = data.source(a.read("Source", UUIDUtil.CODEC).orElseThrow());
                return source != null && source.getStringOr("Kind", "").equals("SHEAR_NATURAL")
                        && source.read("Target", UUIDUtil.CODEC).orElseThrow().equals(sheep[0].getUUID());
            }).toList();
            h.assertTrue(actions.stream().noneMatch(a -> a.getStringOr("State", "").equals("REVIEW")), "unknown native effect is a hard failure, not renewed shearing");
            var privateOrder = ProductionOrderData.get(server).byId(privateBed[0]).orElseThrow();
            var jobs = site.getCompoundOrEmpty("Jobs");
            if (privateOrder.status() != ProductionOrderStatus.COMPLETED || !jobs.getCompoundOrEmpty("TEMP_BED").getStringOr("State", "").equals("DONE")) return;
            var complete = actions.stream().filter(a -> a.getStringOr("State", "").equals("DONE")).toList();
            if (complete.size() < 2 || actions.stream().anyMatch(a ->
                    !java.util.Set.of("DONE", "CANCELLED").contains(a.getStringOr("State", "")))) return;
            h.assertTrue(observedRegrowth[0] && sheep[0].isAlive() && !sheep[0].isPersistenceRequired(),
                    "same wild sheep regrew wool through real eating; no replacement animal or title change");
            var life = NpcLifeData.get(server).record(worker.getCharacterId());
            h.assertTrue(life.bedPosition() != null && level.getBlockState(life.bedPosition()).is(Blocks.WHITE_BED),
                    "original growth bed physically placed and assigned to original worker");
            h.assertTrue(SupplyFlowService.count(SupplyFlowService.container(server, output), ItemFingerprint.of(new ItemStack(Items.WHITE_BED))) == 1,
                    "second private bed physically manufactured in lawful stock");
            int produced = complete.stream().flatMap(a -> MinecraftApi.listOfType(a, "Drops", Tag.TAG_COMPOUND).stream())
                    .map(x -> (net.minecraft.nbt.CompoundTag)x).mapToInt(x -> x.getIntOr("Quantity", 0)).sum();
            int physical = 0;
            for (int i = 0; i < 4; i++) physical += SupplyFlowService.count(NaturalPolityData.inventory(server, village, i), ItemFingerprint.of(new ItemStack(Items.WHITE_WOOL)));
            for (int i = 0; i < 3; i++) {
                var npc = NaturalVillageR1GameTests.actor(level, village, i);
                h.assertTrue(npc != null && npc.isAlive(), "original residents remain alive");
                physical += npc.getPersonalInventory().count(stack -> stack.is(Items.WHITE_WOOL));
                physical += SupplyFlowService.count(npc.getTransportInventory(), ItemFingerprint.of(new ItemStack(Items.WHITE_WOOL)));
            }
            h.assertTrue(produced == physical + 6, "all real native wool equals physical locations plus two beds' six wool");
            h.assertTrue(complete.stream().allMatch(a -> a.read("DeliveryReceipt", UUIDUtil.CODEC).isPresent())
                    && complete.stream().map(a -> a.read("DeliveryReceipt", UUIDUtil.CODEC).orElseThrow()).distinct().count() == complete.size(), "distinct original transfer receipts");
            Sagadyssey.LOGGER.info("NATURAL_WOOL_GROWTH_ACCEPT tick={} originalPerson={} originalSheep={} shears={} regrowth=true produced={} physical={} consumed=6 placedBed={} privateBedOrder={}",
                    level.getGameTime(),worker.getCharacterId(),sheep[0].getUUID(),complete.size(),produced,physical,life.bedPosition(),privateBed[0]);
            h.succeed();
        });
    }
}
