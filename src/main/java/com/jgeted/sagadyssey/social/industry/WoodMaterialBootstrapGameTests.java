package com.jgeted.sagadyssey.social.industry;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import static com.jgeted.sagadyssey.social.industry.IndustryCompletionGameTests.*;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Existing product demand must obtain its small intermediate inputs from actual owned logs. */
@GameTestHolder("sagadyssey_industries_completion") @PrefixGameTestTemplate(false)
public final class WoodMaterialBootstrapGameTests {
    @GameTestGenerator public static Collection<TestFunction> tests() {
        if (!Boolean.getBoolean("sagadyssey.industry.native")
                || !System.getProperty("sagadyssey.completion.phase", "").equals("wood-material-bootstrap")) return List.of();
        return List.of(new TestFunction("wood_material_bootstrap", "internal.original_logs_to_rod_then_bed",
                "sagadyssey_industries_completion:empty", 12000, 0, true, WoodMaterialBootstrapGameTests::run));
    }

    private static void run(GameTestHelper h) {
        floor(h, 0); var level = h.getLevel(); var server = level.getServer(); long start = level.getGameTime();
        var worker = npc(h, 3, 6, NpcProfession.WORKER); family(h, "private intermediate material bootstrap", List.of(worker));
        bed(h, worker, 4, 18);
        var person = worker.getCharacterId(); var body = worker.getUUID(); var owner = SubjectRef.character(person);
        var stock = point(h, at(15, 0, 6), owner, TransferIntent.DELIVERY);
        level.setBlockAndUpdate(at(16, 0, 6), Blocks.CRAFTING_TABLE.defaultBlockState());
        h.assertTrue(NpcLifeData.get(server).assignPrivateStorage(server, person, stock.dimension(), stock.position(), start), "original private stock assigned");
        box(h, stock).setItem(0, new ItemStack(Items.BIRCH_LOG, 6));
        box(h, stock).setItem(1, new ItemStack(Items.STRING, 2));
        box(h, stock).setItem(2, new ItemStack(Items.WHITE_WOOL, 3));
        SupplyFlowData.get(server).reveal(owner, stock.containerId());
        var rodNeed = SupplyFlowService.request(server, owner, "material-bootstrap:rod", stock, fp("fishing_rod"), 1, start, start + 12000);
        UUID[] bedNeed = {null}; long[] rodComplete = {-1}, stableSince = {-1};
        Set<String> products = Set.of(WorkshopTask.PLANKS, WorkshopTask.STICKS, WorkshopTask.FISHING_ROD, WorkshopTask.BED);
        Sagadyssey.LOGGER.info("WOOD_MATERIAL_BOOTSTRAP_INITIAL tick={} person={} body={} stock={} rodNeed={} logs=6 string=2 whiteWool=3 bread=8 planks=0 sticks=0 rods=0 bedItems=0 deadline=12000 duplicateWindow=300", start, person, body, stock.containerId(), rodNeed.id());
        sprint(h, 12500);
        h.onEachTick(() -> {
            long now = level.getGameTime();
            if (now % 20 == 0 && java.nio.file.Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("industry-stop-request.json")))
                h.fail("frozen wall budget stop; preserve original material bootstrap world");
            h.assertTrue(worker.isAlive() && person.equals(worker.getCharacterId()) && body.equals(worker.getUUID()), "original person and entity remain alive");
            if (bedNeed[0] == null && NeedRequestData.get(server).request(rodNeed.id()).status() == NeedRequestStatus.DELIVERED) {
                h.assertTrue(count(box(h, stock), Items.FISHING_ROD) == 1, "original formal rod demand has one actual produced rod");
                rodComplete[0] = now;
                bedNeed[0] = SupplyFlowService.request(server, owner, "material-bootstrap:bed", stock, fp("white_bed"), 1, now, start + 12000).id();
                Sagadyssey.LOGGER.info("WOOD_MATERIAL_BOOTSTRAP_BED_REQUEST tick={} rodNeed={} bedNeed={} physicalRod=1 noIntermediateTestRequests=true", now, rodNeed.id(), bedNeed[0]);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(bedNeed[0] != null, "normal rod completed before next formal bed request");
            var needs = NeedRequestData.get(server);
            var rod = needs.request(rodNeed.id()); var bed = needs.request(bedNeed[0]);
            for (var need : List.of(rod, bed))
                h.assertTrue(need.status() == NeedRequestStatus.DELIVERED && need.requested() == 1 && need.delivered() == 1
                        && need.reserved() == 0 && need.inTransit() == 0, "original final-product need fulfilled once, not a fake intermediate delivery");
            var orders = ProductionOrderData.get(server).ordersForWorker(person);
            var planks = orders.stream().filter(o -> o.recipeId().equals(WorkshopTask.PLANKS)).toList();
            var sticks = orders.stream().filter(o -> o.recipeId().equals(WorkshopTask.STICKS)).toList();
            var rods = orders.stream().filter(o -> o.recipeId().equals(WorkshopTask.FISHING_ROD)).toList();
            var beds = orders.stream().filter(o -> o.recipeId().equals(WorkshopTask.BED)).toList();
            h.assertTrue(planks.size() == 1 && sticks.size() == 1 && rods.size() == 1 && beds.size() == 1
                    && orders.size() == 4, "exactly one original plank, stick, rod and bed batch; no surplus-opening policy");
            int labor = 0;
            for (var order : orders) {
                h.assertTrue(products.contains(order.recipeId()) && order.status() == ProductionOrderStatus.COMPLETED
                        && person.equals(order.assignedWorkerId()) && owner.equals(order.outputOwner())
                        && stock.containerId().equals(order.targetContainerId()), "completed original worker, owner and private destination");
                var batch = ProductionBatchData.get(server).batch(order.batchId());
                int work = order.recipeId().equals(WorkshopTask.BED) ? 400 : 200;
                h.assertTrue(batch != null && batch.status() == ProductionBatchStatus.COMPLETED && batch.workerId().equals(person)
                        && batch.outputOwner().equals(owner) && batch.containerId().equals(stock.containerId())
                        && batch.inputs().equals(order.inputs()) && batch.output().equals(order.output())
                        && batch.requiredWorkTicks() == work && ProductionLaborData.get(server).worked(order.id()) == work,
                        "real original material batch with all required actual labor");
                labor += work;
                if (order.recipeId().equals(WorkshopTask.PLANKS)) h.assertTrue(order.inputs().equals(List.of(new ProductionMaterial(fp("birch_log"), 3)))
                        && order.output().equals(new ProductionMaterial(fp("birch_planks"), 12)), "concrete birch inputs and native board output");
                if (order.recipeId().equals(WorkshopTask.STICKS)) h.assertTrue(order.inputs().equals(List.of(new ProductionMaterial(fp("birch_planks"), 2)))
                        && order.output().equals(new ProductionMaterial(fp("stick"), 4)), "real two-board intermediate batch");
                h.assertTrue(RightsStateData.get(server).reservationsForToken(order.token(), level.getGameTime()).isEmpty()
                        && RightsStateData.get(server).reservationsForToken("production:" + order.batchId(), level.getGameTime()).isEmpty(),
                        "completed material reservations released, not duplicated");
            }
            h.assertTrue(labor == 1000, "one plank200, stick200, rod200 and bed400, no duplicate labor");
            var b = box(h, stock); int plankBatches = planks.size();
            h.assertTrue(count(b, Items.BIRCH_LOG) == 3 && count(b, Items.BIRCH_PLANKS) == 7
                    && count(b, Items.STICK) == 1 && count(b, Items.STRING) == 0 && count(b, Items.WHITE_WOOL) == 0
                    && count(b, Items.FISHING_ROD) == 1 && count(b, Items.WHITE_BED) == 1,
                    "exact original resources consumed: two boards to four sticks, three sticks plus two string to rod, three boards plus three wool to bed");
            for (var item : List.of(Items.BIRCH_LOG, Items.BIRCH_PLANKS, Items.STICK, Items.STRING, Items.WHITE_WOOL, Items.FISHING_ROD, Items.WHITE_BED)) {
                h.assertTrue(worker.getPersonalInventory().count(s -> s.is(item)) == 0 && count(worker.getTransportInventory(), item) == 0
                        && !worker.getMainHandItem().is(item) && !worker.getOffhandItem().is(item), "no hidden duplicate original material or product outside private stock");
            }
            h.assertTrue(RightsStateData.get(server).context(stock.containerId()).defaultBeneficialOwner().equals(owner), "original private ownership unchanged");
            receipt(h, needs, rods.getFirst().id(), rodNeed.id()); receipt(h, needs, beds.getFirst().id(), bedNeed[0]);
            if (stableSince[0] < 0) stableSince[0] = level.getGameTime();
            h.assertTrue(level.getGameTime() - stableSince[0] >= 300, "300 further normal ticks keep counts, identities and original receipts unchanged");
            Sagadyssey.LOGGER.info("WOOD_MATERIAL_BOOTSTRAP_ACCEPT tick={} elapsed={} person={} rodNeed={} bedNeed={} rodComplete={} plankBatches={} sticksBatches=1 rods=1 beds=1 actualLabor={} logs={} boards={} sticks=1 string=0 wool=0 unchangedPrivateOwner=true duplicateWindow=300 noRuntimeReplenishment=true",
                    level.getGameTime(), level.getGameTime() - start, person, rodNeed.id(), bedNeed[0], rodComplete[0], plankBatches, labor, count(b, Items.BIRCH_LOG), count(b, Items.BIRCH_PLANKS));
            var mark = new CompoundTag(); mark.store("Person", UUIDUtil.CODEC, person); mark.store("Body", UUIDUtil.CODEC, body); mark.store("RodNeed", UUIDUtil.CODEC, rodNeed.id()); mark.store("BedNeed", UUIDUtil.CODEC, bedNeed[0]);
            mark.putInt("PlankBatches", plankBatches); mark.putInt("ActualLabor", labor); mark.putLong("Start", start); save(h, mark);
        });
    }

    private static void receipt(GameTestHelper h, NeedRequestData data, UUID order, UUID need) {
        var journal = MinecraftApi.listOfType(data.save(new CompoundTag(), null), "Receipts", Tag.TAG_COMPOUND);
        for (String stage : List.of("reserve", "dispatch", "delivery")) {
            UUID id = SupplyFlowService.receipt(order, "produced-" + stage); int matches = 0;
            for (var raw : journal) { var row = (CompoundTag) raw; if (row.read("Id", UUIDUtil.CODEC).orElseThrow().equals(id)) {
                matches++; h.assertTrue(row.read("Request", UUIDUtil.CODEC).orElseThrow().equals(need) && row.getIntOr("Quantity", 0) == 1, "one real final product receipt for its unchanged original request");
                h.assertTrue(row.getStringOr("Action", "").equals(stage.equals("delivery") ? "DELIVER" : stage.toUpperCase(Locale.ROOT)), "correct final-product receipt action");
            }}
            h.assertTrue(matches == 1, "one original final-product " + stage + " receipt");
        }
    }
}
