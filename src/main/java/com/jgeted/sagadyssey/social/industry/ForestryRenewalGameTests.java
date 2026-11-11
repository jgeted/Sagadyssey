package com.jgeted.sagadyssey.social.industry;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import static com.jgeted.sagadyssey.social.industry.IndustryCompletionGameTests.*;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.industry.forestry.ForestrySeedPolicy;
import com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;

import java.util.*;

/** Finite birch startup; native random growth, real produced seeds, normal work/recipe/transfer paths. */
@GameTestHolder("sagadyssey_industries_completion")
@PrefixGameTestTemplate(false)
public final class ForestryRenewalGameTests {
    @GameTestGenerator public static Collection<TestFunction> tests() {
        if (!Boolean.getBoolean("sagadyssey.industry.native")
                || !System.getProperty("sagadyssey.completion.phase", "").equals("forest-birch")) return List.of();
        return List.of(new TestFunction("forestry_renewal", "internal.birch_native_renewal",
                "sagadyssey_industries_completion:empty", 120000, 0, true, ForestryRenewalGameTests::birch));
    }

    private static void birch(GameTestHelper h) {
        floor(h, 3);
        var l = h.getLevel(); var s = l.getServer(); long start = l.getGameTime();
        var n = npc(h, 3, 6, NpcProfession.WORKER);
        family(h, "one original birch forester", List.of(n)); bed(h, n, 4, 18);
        var person = n.getCharacterId(); var body = n.getUUID();
        var owner = SubjectRef.character(person);
        var stock = point(h, at(15, 0, 6), owner, TransferIntent.DELIVERY);
        var table = at(16, 0, 6); l.setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());
        h.assertTrue(NpcLifeData.get(s).assignPrivateStorage(s, person, stock.dimension(), stock.position(), start), "original owner storage");
        FieldworkData.get(s).bindTestStorage(body, stock);
        var root = at(7, 0, 6);
        for (int x = 3; x <= 11; x++) for (int z = 2; z <= 10; z++)
            l.setBlockAndUpdate(at(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState());
        for (int y = 0; y < 3; y++) l.setBlockAndUpdate(root.above(y), Blocks.BIRCH_LOG.defaultBlockState());
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            l.setBlockAndUpdate(root.offset(x, 3, z), Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1));
        n.setWorkZone(at(3, 0, 2), at(11, 10, 10));
        n.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        n.getPersonalInventory().setItem(0, new ItemStack(Items.BIRCH_SAPLING));
        n.getPersonalInventory().setItem(1, new ItemStack(Items.DIRT, 32));

        // Native registries, not only a string mapper, define which family can actually renew.
        for (var pair : List.of(new Object[]{Items.OAK_SAPLING, Blocks.OAK_LOG},
                new Object[]{Items.BIRCH_SAPLING, Blocks.BIRCH_LOG}, new Object[]{Items.SPRUCE_SAPLING, Blocks.SPRUCE_LOG},
                new Object[]{Items.JUNGLE_SAPLING, Blocks.JUNGLE_LOG}, new Object[]{Items.ACACIA_SAPLING, Blocks.ACACIA_LOG},
                new Object[]{Items.CHERRY_SAPLING, Blocks.CHERRY_LOG})) {
            var seed = new ItemStack((Item) pair[0]); var log = (Block) pair[1];
            h.assertTrue(ForestrySeedPolicy.isRenewalSeed(seed)
                    && ForestrySeedPolicy.matchesCut(seed, NbtUtils.writeBlockState(log.defaultBlockState()).toString()), "native single-seed family recognized " + pair[0]);
        }
        h.assertTrue(!ForestrySeedPolicy.isRenewalSeed(new ItemStack(Items.DARK_OAK_SAPLING)), "four-seed tree not advertised as one-seed renewal");
        h.assertTrue(!ForestrySeedPolicy.matchesCut(new ItemStack(Items.BIRCH_SAPLING), NbtUtils.writeBlockState(Blocks.OAK_LOG.defaultBlockState()).toString()), "different real species cannot reuse stump provenance");
        h.assertTrue(!ForestrySeedPolicy.matchesCut(new ItemStack(Items.BIRCH_SAPLING), "corrupt"), "invalid receipt is not plant authorization");

        var flows = SupplyFlowData.get(s); flows.reveal(owner, stock.containerId());
        // Actual storage buffers, no fixture completion callback or later inventory injection.
        flows.policy(new SupplyFlowData.Policy("birch-workshop-planks", owner, stock.containerId(), fp("birch_planks"), 24, "workshop"));
        flows.policy(new SupplyFlowData.Policy("birch-workshop-sticks", owner, stock.containerId(), fp("stick"), 4, "workshop"));
        Sagadyssey.LOGGER.info("FORESTRY_BIRCH_INITIAL tick={} person={} body={} rawLogs=3 seed=1 dirt=32 bread=8 axe=1 emptyStock={} randomTick=3 daylight=false", start, person, body, stock.containerId());
        sprint(h, 120500);
        h.onEachTick(() -> {
            if (l.getGameTime() % 20 == 0 && java.nio.file.Files.exists(s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("industry-stop-request.json")))
                h.fail("frozen wall budget stop; preserve original birch world");
        });
        h.succeedWhen(() -> {
            h.assertTrue(n.isAlive() && n.getCharacterId().equals(person) && n.getUUID().equals(body), "same living original worker");
            var work = FieldworkData.get(s); var receipts = work.receiptsFor(body);
            long plants = receipts.stream().filter(r -> r.action().operation() == FieldworkAction.Operation.PLANT && r.action().target().equals(root)).count();
            long cuts = receipts.stream().filter(r -> r.action().operation() == FieldworkAction.Operation.CHOP && r.action().target().equals(root)).count();
            h.assertTrue(plants >= 2 && cuts >= 3, "two native grown trees really harvested; initial stump does not count as regrowth");
            int producedSeeds = positive(receipts, "birch_sapling"), logs = positive(receipts, "birch_log");
            h.assertTrue(producedSeeds >= 1, "second planting backed by real harvested seed; only one seed initially");
            var orders = ProductionOrderData.get(s).ordersForWorker(person).stream().filter(o -> o.status() == ProductionOrderStatus.COMPLETED).toList();
            var planks = orders.stream().filter(o -> o.recipeId().equals(WorkshopTask.PLANKS) && o.output().fingerprint().equals(fp("birch_planks"))).toList();
            var sticks = orders.stream().filter(o -> o.recipeId().equals(WorkshopTask.STICKS) && o.output().fingerprint().equals(fp("stick"))).toList();
            h.assertTrue(planks.size() >= 2, "native demand really turns replenished birch into at least two native plank batches");
            h.assertTrue(orders.stream().allMatch(o -> o.assignedWorkerId().equals(person)), "same worker time belongs to real completed orders");
            h.assertTrue(!FieldworkDepositTaskExecutor.hasDeliverableCargo(n, work), "actual production deposit completed, retained seed separately allowed");
            int consumed = planks.stream().flatMap(o -> o.inputs().stream()).filter(m -> m.fingerprint().equals(fp("birch_log"))).mapToInt(ProductionMaterial::quantity).sum();
            h.assertTrue(count(box(h, stock), Items.BIRCH_LOG) + n.getPersonalInventory().count(i -> i.is(Items.BIRCH_LOG)) + consumed == logs, "real logs in stock/bag/actual recipes balance");
            int nativeSticks = positive(receipts, "stick"), madeSticks = sticks.stream().mapToInt(o -> o.output().quantity()).sum();
            h.assertTrue(count(box(h, stock), Items.STICK) + n.getPersonalInventory().count(i -> i.is(Items.STICK)) == nativeSticks + madeSticks, "native leaf sticks distinct from manufactured sticks");
            int planksConsumed = sticks.stream().flatMap(o -> o.inputs().stream()).filter(m -> m.fingerprint().equals(fp("birch_planks"))).mapToInt(ProductionMaterial::quantity).sum();
            h.assertTrue(count(box(h, stock), Items.BIRCH_PLANKS) + n.getPersonalInventory().count(i -> i.is(Items.BIRCH_PLANKS)) + planksConsumed == planks.stream().mapToInt(o -> o.output().quantity()).sum(), "native plank output and any downstream stick inputs balanced");
            h.assertTrue(n.getMainHandItem().is(Items.IRON_AXE) && n.getMainHandItem().getDamageValue() > 0, "real original axe worn");
            Sagadyssey.LOGGER.info("FORESTRY_BIRCH_ACCEPT tick={} elapsed={} person={} plants={} cuts={} seeds={} producedLogs={} consumedLogs={} plankOrders={} nativeSticks={} madeSticks={} originalAxeWear={}",
                    l.getGameTime(), l.getGameTime()-start, person, plants, cuts, producedSeeds, logs, consumed, planks.size(), nativeSticks, madeSticks, n.getMainHandItem().getDamageValue());
            save(h, new CompoundTag());
        });
    }

    private static int positive(List<FieldworkActionReceipt> receipts, String item) {
        return receipts.stream().flatMap(r -> r.inventoryDeltas().stream())
                .filter(d -> d.fingerprint().equals("minecraft:" + item + "#0") && d.quantity() > 0)
                .mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();
    }
}
