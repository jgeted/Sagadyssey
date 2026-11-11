package com.jgeted.sagadyssey.social.industry;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.config.SagadysseyConfig;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.social.growth.SettlementDevelopmentLayout;
import com.jgeted.sagadyssey.social.industry.common.FurnaceWorkAdapter;
import com.jgeted.sagadyssey.social.industry.common.SettlementIndustryAccess;
import com.jgeted.sagadyssey.social.needs.NeedRequestData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.social.production.ProductionContainerIds;
import com.jgeted.sagadyssey.social.production.ProductionOrderData;
import com.jgeted.sagadyssey.social.production.ProductionOrderStatus;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Normal qualified local worker supplies a household from finite private raw inputs in two real batches. */
@GameTestHolder("sagadyssey_playability_fix")
@PrefixGameTestTemplate(false)
public final class NaturalSupplierGameTests {
    private static final ItemFingerprint RAW = new ItemFingerprint("minecraft:raw_iron", 0);
    private static final ItemFingerprint IRON = new ItemFingerprint("minecraft:iron_ingot", 0);
    private static final ItemFingerprint COAL = new ItemFingerprint("minecraft:coal", 0);

    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        return System.getProperty("sagadyssey.playability.phase", "").equals("natural-supplier-iron")
                ? List.of(new TestFunction("playability_fix", "natural_supplier_iron", "sagadyssey_playability_fix:empty",
                        16000, 0, true, NaturalSupplierGameTests::run)) : List.of();
    }

    private static void run(GameTestHelper h) {
        var level = h.getLevel(); var server = level.getServer();
        var village = NaturalVillageR1GameTests.fixture(h, 0, SettlementDevelopmentLayout.INDUSTRY_TEMPLATE);
        level.setDayTime(1000);
        server.tickRateManager().requestGameToSprint(16200);
        UUID[] demand = {null}; UUID[] originalPeople = new UUID[3];
        long[] started = {0}; int[] initialCoal = {0};
        var stop = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("industry-stop-request.json");
        h.onEachTick(() -> {
            if (java.nio.file.Files.exists(stop)) { h.fail("frozen wall-clock budget reached; retain world and stop normally"); return; }
            var worker = NaturalVillageR1GameTests.actor(level, village, 2);
            if (worker == null) return;
            var flows = SupplyFlowData.get(server);
            var stock = flows.pointOrNull(NaturalVillageEconomyData.storeId(village, 2));
            var pantry = flows.pointOrNull(NaturalVillageEconomyData.storeId(village, 1));
            if (stock == null || pantry == null) return;
            var family = SubjectRef.household(NaturalVillageLifeData.home(village));
            var owner = SubjectRef.character(worker.getCharacterId());
            var furnacePos = NaturalVillageLayout.at(village, NaturalVillageLayout.INDUSTRY_FURNACE);
            if (demand[0] == null) {
                if (!com.jgeted.sagadyssey.social.growth.SettlementCoordinatorData.active(server, village.buildingKey())) return;
                h.assertTrue(level.getGameTime() <= 1600, "initialization <=1600 included in 16000 total ticks");
                SagadysseyConfig.ENABLE_SETTLEMENT_INDUSTRIES.set(true);
                h.assertTrue(SettlementIndustryAccess.resident(worker)
                        && !SettlementIndustryAccess.fixture(worker, "sagadyssey.industry.native"), "ordinary admission without native fixture bypass");
                h.assertTrue(stock.owner().equals(owner) && pantry.owner().equals(family)
                        && flows.known(family).contains(stock.containerId()), "original distinct private and household stock with established knowledge");
                h.assertTrue(new RightsService(RightsStateData.get(server)).can(family, AssetRight.TRANSFER,
                        stock.containerId(), 0, IRON, level.getGameTime()).status() == RightsVerdict.Status.ALLOW,
                        "original founding consent already allows household supply, test grants no rights");
                h.assertTrue(FurnaceWorkAdapter.ownsStation(server, village.dimension(), furnacePos, owner),
                        "normal v9 initialization registered the real private furnace");
                var raw = SupplyFlowService.container(server, stock);
                var slots = java.util.stream.IntStream.range(0, raw.getContainerSize()).filter(i -> raw.getItem(i).isEmpty()).limit(2).toArray();
                h.assertTrue(slots.length == 2, "finite inputs fit without overwriting existing inventory");
                for (int i = 0; i < 4; i++) {
                    var box = com.jgeted.sagadyssey.social.politics.NaturalPolityData.inventory(server, village, i);
                    h.assertTrue(SupplyFlowService.count(box, RAW) == 0 && SupplyFlowService.count(box, IRON) == 0,
                            "no pre-existing raw iron or iron ingots");
                    initialCoal[0] += SupplyFlowService.count(box, COAL);
                }
                raw.setItem(slots[0], new ItemStack(Items.RAW_IRON, 6));
                raw.setItem(slots[1], new ItemStack(Items.COAL, 2));
                initialCoal[0] += 2;
                RightsStateData.get(server).bumpContext(stock.containerId());
                for (int i = 0; i < 3; i++) originalPeople[i] = NaturalVillageR1GameTests.actor(level, village, i).getCharacterId();
                started[0] = level.getGameTime();
                demand[0] = SupplyFlowService.request(server, family, "supplier-validation:finite-iron-input-stock",
                        pantry, IRON, 6, started[0], started[0] + 15000).id();
                Sagadyssey.LOGGER.info("NATURAL_SUPPLIER_INITIAL tick={} worker={} need={} raw=6 addedCoal=2 totalCoal={} privateStock={} householdStock={} furnace={} ordinaryGate=true",
                        started[0], worker.getCharacterId(), demand[0], initialCoal[0], stock.containerId(), pantry.containerId(), furnacePos);
                return;
            }
            var need = NeedRequestData.get(server).request(demand[0]);
            if (need.delivered() != 6) return;
            var completed = ProductionOrderData.get(server).ordersForWorker(worker.getCharacterId()).stream()
                    .filter(FurnaceWorkAdapter::recognized).filter(o -> o.output().fingerprint().equals(IRON)
                            && o.status() == ProductionOrderStatus.COMPLETED).toList();
            if (completed.size() < 2) return;
            h.assertTrue(completed.size() == 2 && completed.stream().allMatch(o -> o.output().quantity() == 3
                    && FurnaceWorkAdapter.completedDelivery(server, o)), "two separate actual three-iron batches and all original native transfers");
            h.assertTrue(completed.stream().map(o -> o.batchId()).distinct().count() == 2, "distinct batches, not duplicate completion");
            int rawTotal = 0, ironTotal = 0, coalTotal = 0;
            for (int i = 0; i < 4; i++) {
                var box = com.jgeted.sagadyssey.social.politics.NaturalPolityData.inventory(server, village, i);
                rawTotal += SupplyFlowService.count(box, RAW); ironTotal += SupplyFlowService.count(box, IRON);
                coalTotal += SupplyFlowService.count(box, COAL);
            }
            for (int i = 0; i < 3; i++) {
                var npc = NaturalVillageR1GameTests.actor(level, village, i);
                h.assertTrue(npc != null && npc.isAlive() && npc.getCharacterId().equals(originalPeople[i]), "original resident identity retained");
                for (Container bag : List.of(npc.getPersonalInventory(), npc.getTransportInventory())) {
                    rawTotal += SupplyFlowService.count(bag, RAW); ironTotal += SupplyFlowService.count(bag, IRON);
                    coalTotal += SupplyFlowService.count(bag, COAL);
                }
            }
            var furnace = (Container)level.getBlockEntity(furnacePos);
            rawTotal += SupplyFlowService.count(furnace, RAW); ironTotal += SupplyFlowService.count(furnace, IRON);
            coalTotal += SupplyFlowService.count(furnace, COAL);
            h.assertTrue(rawTotal == 0 && ironTotal == 6 && SupplyFlowService.count(SupplyFlowService.container(server, pantry), IRON) == 6,
                    "six finite initial raw iron became six physical household ingots, no initial or later ingots");
            h.assertTrue(coalTotal >= 0 && coalTotal < initialCoal[0], "real native fuel consumed without refund; unrelated legal uses remain permitted");
            var routes = flows.routes().stream().filter(r -> r.need().equals(demand[0])).toList();
            h.assertTrue(need.reserved() == 0 && need.inTransit() == 0 && need.remaining() == 0
                    && routes.stream().allMatch(r -> (r.stage() == SupplyFlowData.Stage.DONE || r.stage() == SupplyFlowData.Stage.CANCELLED) && r.pending() == null)
                    && routes.stream().filter(r -> r.stage() == SupplyFlowData.Stage.DONE).mapToInt(SupplyFlowData.Route::quantity).sum() == 6,
                    "original household obligation fulfilled by actual normal transport, no remaining custody");
            h.assertTrue(RightsStateData.get(server).context(stock.containerId()).defaultBeneficialOwner().equals(owner)
                    && RightsStateData.get(server).context(pantry.containerId()).defaultBeneficialOwner().equals(family)
                    && FurnaceWorkAdapter.ownsStation(server,village.dimension(),furnacePos,owner), "supplier stock and furnace remain private");
            h.assertTrue(routes.stream().allMatch(r -> RightsStateData.get(server).reservationsForToken(r.token(), level.getGameTime()).isEmpty()),
                    "settled household deliveries retain no material reservations");
            Sagadyssey.LOGGER.info("NATURAL_SUPPLIER_ACCEPT tick={} elapsed={} worker={} originalNeed={} batches=2 rawConsumed=6 actualIngots=6 householdDelivered=6 routes={} remainingCoal={} initialCoal={} permissionsUnchanged=true",
                    level.getGameTime(),level.getGameTime()-started[0],worker.getCharacterId(),demand[0],routes.size(),coalTotal,initialCoal[0]);
            h.succeed();
        });
    }
}
