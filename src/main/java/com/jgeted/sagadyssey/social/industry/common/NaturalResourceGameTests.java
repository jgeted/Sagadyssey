package com.jgeted.sagadyssey.social.industry.common;

import net.minecraft.world.level.gamerules.GameRules;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.config.SagadysseyConfig;
import com.jgeted.sagadyssey.core.structure.NaturalVillageLayout;
import com.jgeted.sagadyssey.core.structure.NaturalVillageR1GameTests;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.social.growth.GrowthLayout;
import com.jgeted.sagadyssey.social.growth.SettlementDevelopmentLayout;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.social.production.ProductionContainerIds;
import com.jgeted.sagadyssey.social.production.ProductionOrderData;
import com.jgeted.sagadyssey.social.production.ProductionOrderStatus;
import com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.neoforged.neoforge.event.EventHooks;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@GameTestHolder("sagadyssey_playability_fix")
@PrefixGameTestTemplate(false)
public final class NaturalResourceGameTests {
    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        String phase = System.getProperty("sagadyssey.playability.phase", "");
        if (!phase.equals("river-angling") && !phase.equals("ocean-angling")
                && !phase.equals("natural-sheep") && !phase.endsWith("rod-renewal")) return List.of();
        return List.of(new TestFunction("playability_fix", phase, "sagadyssey_playability_fix:empty",
                phase.endsWith("rod-renewal") ? 12000 : 4200, 0, true, h -> run(h, phase)));
    }

    private static void run(GameTestHelper h, String phase) {
        boolean fishing = !phase.equals("natural-sheep");
        boolean renewal = phase.endsWith("rod-renewal");
        boolean foundingStations = phase.equals("founding-rod-renewal");
        var level = h.getLevel();
        var server = level.getServer();
        var village = NaturalVillageR1GameTests.fixture(h, 0,
                foundingStations ? SettlementDevelopmentLayout.INDUSTRY_TEMPLATE : GrowthLayout.COORDINATED);
        level.setDayTime(6000);
        level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
        server.tickRateManager().requestGameToSprint(renewal ? 12200 : 4500);
        UUID[] need = {null};
        Sheep[] original = {null};
        BlockPos water = NaturalVillageLayout.at(village, new BlockPos(17, 0, 18));
        BlockPos shore = NaturalVillageLayout.at(village, new BlockPos(16, 1, 18));
        h.onEachTick(() -> {
            var worker = NaturalVillageR1GameTests.actor(level, village, 2);
            if (worker == null) return;
            var owner = SubjectRef.character(worker.getCharacterId());
            var outputPos = NaturalVillageLayout.at(village, NaturalVillageLayout.WOOD);
            var outputId = ProductionContainerIds.resolve(server, village.dimension(), outputPos);
            var flows = SupplyFlowData.get(server);
            var output = flows.point(outputId);
            if (output == null) return;
            if (need[0] == null) {
                if (fishing && !worker.onGround()) return;
                SagadysseyConfig.ENABLE_SETTLEMENT_INDUSTRIES.set(true);
                h.assertTrue(!SettlementIndustryAccess.fixture(worker, "sagadyssey.industry.native"),
                        "exercise the ordinary admission path");
                h.assertTrue(worker.getMainHandItem().isEmpty(), "initial equipment slot available");
                var tool = new ItemStack(fishing ? Items.FISHING_ROD : Items.SHEARS);
                if (renewal) tool.setDamageValue(tool.getMaxDamage() - 1);
                worker.setItemSlot(EquipmentSlot.MAINHAND, tool);
                if (fishing) {
                    // Keep the source from flowing over the walkable bank during the observation.
                    level.setBlockAndUpdate(water.below(2), Blocks.STONE.defaultBlockState());
                    for (var direction : Direction.Plane.HORIZONTAL) {
                        level.setBlockAndUpdate(water.relative(direction), Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(water.relative(direction).below(), Blocks.STONE.defaultBlockState());
                    }
                    level.setBlockAndUpdate(shore, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(shore.above(), Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());
                    level.setBlockAndUpdate(water.below(), Blocks.WATER.defaultBlockState());
                    level.setBlockAndUpdate(water.above(), Blocks.AIR.defaultBlockState());
                    var biome = phase.equals("ocean-angling") ? Biomes.OCEAN : Biomes.RIVER;
                    var waterBiome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(biome);
                    // The biome zoomer samples neighboring quart cells, not only the containing cell.
                    for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                        var sample = water.offset(x * 4, y * 4, z * 4);
                        if (level.isOutsideBuildHeight(sample)) continue;
                        var section = level.getChunk(sample).getSection(level.getSectionIndex(sample.getY()));
                        var biomes = (PalettedContainer<Holder<Biome>>) section.getBiomes();
                        biomes.set((sample.getX() & 15) >> 2, (sample.getY() & 15) >> 2, (sample.getZ() & 15) >> 2, waterBiome);
                    }
                    h.assertTrue(NaturalIndustrySources.fishingWater(level, water), "actual water biome and two source blocks");
                    h.assertTrue(FieldworkRoutes.walkable(worker, shore), "dry bank is walkable");
                    var path = worker.getNavigation().createPath(shore, 0);
                    h.assertTrue(path != null && path.canReach(), "worker can reach dry bank on foot: "
                            + worker.blockPosition() + " to " + shore + (path == null ? " (no path)" : " via " + path.getEndNode()));
                    h.assertTrue(EntityResourceTask.toolAllowed(worker, NaturalIndustrySources.ANGLING,
                            level.getGameTime()), "worker owns a usable rod");
                } else {
                    var pos = NaturalVillageLayout.at(village, new BlockPos(19, 1, 15));
                    level.setBlockAndUpdate(pos.below(), Blocks.GRASS_BLOCK.defaultBlockState());
                    var sheep = EntityType.SHEEP.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                    sheep.snapTo(pos.getX()+.5, pos.getY(), pos.getZ()+.5, 0, 0);
                    EventHooks.finalizeMobSpawn(sheep, level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.NATURAL, null);
                    sheep.setColor(DyeColor.WHITE);
                    sheep.setNoAi(true);
                    h.assertTrue(level.addFreshEntity(sheep), "test sets up an actual sheep using the normal natural spawn event");
                    h.assertTrue(sheep.getPersistentData().getBooleanOr(NaturalIndustrySources.NATURAL_SPAWN, false), "natural origin recorded by event");
                    original[0] = sheep;
                }
                if (renewal) {
                    var table = NaturalVillageLayout.at(village, foundingStations
                            ? NaturalVillageLayout.INDUSTRY_BENCH : new BlockPos(12, 1, 17));
                    var id = ProductionContainerIds.resolve(server, village.dimension(), table);
                    var rights = RightsStateData.get(server);
                    if (foundingStations) {
                        var furnace = NaturalVillageLayout.at(village, NaturalVillageLayout.INDUSTRY_FURNACE);
                        var furnaceId = ProductionContainerIds.resolve(server, village.dimension(), furnace);
                        h.assertTrue(level.getBlockState(table).is(Blocks.CRAFTING_TABLE)
                                && level.getBlockState(furnace).is(Blocks.FURNACE)
                                && flows.known(owner).containsAll(List.of(id,furnaceId,outputId))
                                && FurnaceWorkAdapter.ownsStation(server,village.dimension(),table,owner)
                                && FurnaceWorkAdapter.ownsStation(server,village.dimension(),furnace,owner),
                                "template places both stations and normal initialization gives the founding worker access");
                    } else {
                        level.setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());
                        rights.ensureContext(id, village.dimension()+":"+table.asLong(), owner, owner, AccessPolicy.PRIVATE);
                        flows.register(new FieldworkStorage(village.dimension(), table, id, owner,
                                AccessPolicy.PRIVATE, TransferIntent.DELIVERY, "rod-renewal-initial-bench"));
                        flows.reveal(owner, id);
                    }
                    var box = SupplyFlowService.container(server, output);
                    int slots = foundingStations ? 4 : 2;
                    var free = java.util.stream.IntStream.range(0, box.getContainerSize())
                            .filter(slot -> box.getItem(slot).isEmpty()).limit(slots).toArray();
                    h.assertTrue(free.length == slots, "free slots for the finite starting materials");
                    box.setItem(free[0], new ItemStack(Items.STICK, 3));
                    box.setItem(free[1], new ItemStack(Items.STRING, 2));
                    if (foundingStations) {
                        box.setItem(free[2], new ItemStack(Items.POTATO));
                        box.setItem(free[3], new ItemStack(Items.COAL));
                    }
                    rights.bumpContext(outputId);
                }
                if (!foundingStations) flows.reveal(owner, outputId);
                long now = level.getGameTime();
                need[0] = SupplyFlowService.request(server, owner, "natural-resource-fixture",
                        output, ItemFingerprint.of(new ItemStack(fishing ? Items.COD : Items.WHITE_WOOL)),
                        renewal ? 2 : 1, now, now+(renewal ? 11600 : 4000)).id();
                return;
            }
            var data = EntityResourceActionData.existing(server);
            if (data == null) return;
            if (renewal) {
                var actions = data.actions().stream().filter(a -> a.read("Need", UUIDUtil.CODEC).orElseThrow().equals(need[0])
                        && a.getStringOr("State", "").equals("DONE")).toList();
                if (actions.size() < 2) return;
                var completed = ProductionOrderData.get(server).ordersForWorker(worker.getCharacterId()).stream()
                        .filter(o -> o.recipeId().equals(WorkshopTask.FISHING_ROD)
                                && o.status() == ProductionOrderStatus.COMPLETED).toList();
                h.assertTrue(completed.size() == 1, "the original worker actually made exactly one replacement rod");
                h.assertTrue(actions.stream().allMatch(a -> a.read("DeliveryReceipt", UUIDUtil.CODEC).isPresent()
                        && MinecraftApi.listOfType(a, "Drops", Tag.TAG_COMPOUND).getCompoundOrEmpty(0).getBooleanOr("Picked", false))
                        && actions.stream().map(a -> a.read("DeliveryReceipt", UUIDUtil.CODEC).orElseThrow()).distinct().count() == actions.size(),
                        "separate catches have separate pickup and delivery records");
                h.assertTrue(actions.stream().anyMatch(a -> a.getIntOr("ToolDamageBefore", 0) == toolLastUse()
                            && a.getIntOr("ToolDamageAfter", 0) == 0)
                        && actions.stream().anyMatch(a -> a.getIntOr("ToolDamageBefore", 0) == 0
                            && a.getIntOr("ToolDamageAfter", 0) == 1)
                        && worker.getMainHandItem().is(Items.FISHING_ROD) && worker.getMainHandItem().getDamageValue() >= 1,
                        "original rod wore out and the original worker used the manufactured rod");
                var box = SupplyFlowService.container(server, output);
                h.assertTrue(SupplyFlowService.count(box, ItemFingerprint.of(new ItemStack(Items.STRING))) == 0,
                        "both units of initial string were physically consumed");
                if (foundingStations && ProductionOrderData.get(server).ordersForWorker(worker.getCharacterId()).stream()
                        .noneMatch(o -> FurnaceWorkAdapter.completedDelivery(server,o)
                                && o.output().fingerprint().equals(ItemFingerprint.of(new ItemStack(Items.BAKED_POTATO))))) return;
                Sagadyssey.LOGGER.info("ROD_RENEWAL_ACCEPT person={} order={} catches={}",
                        worker.getCharacterId(), completed.getFirst().id(), actions.size());
                h.succeed();
                return;
            }
            for (var action : data.actions()) {
                if (!action.read("Need", UUIDUtil.CODEC).orElseThrow().equals(need[0]) || !action.getStringOr("State", "").equals("DONE")) continue;
                var source = data.source(action.read("Source", UUIDUtil.CODEC).orElseThrow());
                h.assertTrue(source.getStringOr("Kind", "").equals(fishing ? NaturalIndustrySources.ANGLING : NaturalIndustrySources.WILD_SHEEP),
                        "correct natural source kind");
                if (fishing) {
                    h.assertTrue(BlockPos.of(source.getLongOr("Water", 0L)).equals(water), "used the observed water column");
                } else {
                    h.assertTrue(source.read("Target", UUIDUtil.CODEC).orElseThrow().equals(original[0].getUUID())
                            && original[0].isAlive() && original[0].isSheared()
                            && !original[0].isPersistenceRequired(), "same wild sheep remains alive and unclaimed");
                }
                var row = MinecraftApi.listOfType(action, "Drops", Tag.TAG_COMPOUND).getCompoundOrEmpty(0);
                var fingerprint = ItemFingerprint.load(row.getCompoundOrEmpty("Item"));
                h.assertTrue(SupplyFlowService.count(SupplyFlowService.container(server, output), fingerprint)
                        >= row.getIntOr("Quantity", 0), "real native output arrived in original stock");
                h.assertTrue(row.getBooleanOr("Picked", false) && action.read("DeliveryReceipt", UUIDUtil.CODEC).isPresent()
                        && action.getIntOr("ToolDamageAfter", 0) - action.getIntOr("ToolDamageBefore", 0) == 1,
                        "actual pickup, delivery and one point of durability consumed");
                Sagadyssey.LOGGER.info("NATURAL_RESOURCE_ACCEPT phase={} kind={} person={} drop={} toolWear=1",
                        phase, source.getStringOr("Kind", ""), worker.getCharacterId(), fingerprint);
                h.succeed();
                return;
            }
        });
    }

    private static int toolLastUse() {
        return new ItemStack(Items.FISHING_ROD).getMaxDamage() - 1;
    }
}
