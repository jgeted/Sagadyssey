package com.jgeted.sagadyssey.npc.brain.fieldwork;

import com.jgeted.sagadyssey.knowledge.projection.NpcProjectionService;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.execution.PlanCancellationReason;
import com.jgeted.sagadyssey.npc.brain.persistence.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

/** 可由独立 GameTestServer 一次运行的真实实体/方块/库存流程，不会自动运行在玩家世界。 */
@GameTestHolder("sagadyssey")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid = "sagadyssey")
public final class FieldworkGameTests {
    private static CompoundTag priorCheckpoint;
    @net.neoforged.bus.api.SubscribeEvent
    public static void installTestTemplate(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        if (!(event.getServer() instanceof GameTestServer)) return;
        // 结构必须先于测试场地生成存在；仅在独立测试服务器安装。
        installTemplate(event.getServer().overworld());
        priorCheckpoint = null;
        if (Boolean.getBoolean("sagadyssey.phase4.requireRestart")) {
            var path = event.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("phase4-restart-checkpoint.nbt");
            try {
                priorCheckpoint = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
                // GameTestServer 每次用新的世界时钟启动；正常游戏则从存档恢复 Time。
                // 在装载旧实体前恢复检查点时钟，不能让计划的时间倒退。
                long resumeTime = priorCheckpoint.getLong("GameTime");
                var savedBrains = NpcBrainData.get(event.getServer()).save(new CompoundTag(), event.getServer().registryAccess()).getList("Brains", Tag.TAG_COMPOUND);
                for (Tag entry : savedBrains) {
                    CompoundTag brain = (CompoundTag) entry;
                    resumeTime = Math.max(resumeTime, brain.getCompound("Active").getLong("UpdatedAt"));
                    for (Tag history : brain.getList("History", Tag.TAG_COMPOUND)) resumeTime = Math.max(resumeTime, ((CompoundTag) history).getLong("UpdatedAt"));
                }
                event.getServer().getWorldData().overworldData().setGameTime(Math.max(event.getServer().overworld().getGameTime(), resumeTime + 1));
                for (String key : List.of("NpcPosition", "BarrelPosition")) {
                    BlockPos p = BlockPos.of(priorCheckpoint.getLong(key));
                    event.getServer().overworld().setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
                    event.getServer().overworld().getChunkAt(p);
                }
            } catch (Exception exception) { throw new IllegalStateException("required previous-process checkpoint is missing or invalid", exception); }
        }
    }
    private static void installTemplate(ServerLevel level) {
        try {
            level.getStructureManager().getOrCreate(ResourceLocation.fromNamespaceAndPath("sagadyssey", "fieldwork_empty"))
                    .load(level.holderLookup(Registries.BLOCK), TagParser.parseTag("{size:[96,10,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    @BeforeBatch(batch = "phase4")
    public static void templates(ServerLevel level) {
        installTemplate(level);
        level.setDayTime(1_000L);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, level.getServer());
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 800)
    public static void farmerHarvestReplantsAndDelivers(GameTestHelper h) {
        floor(h, 12);
        h.setBlock(5, 0, 5, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
        h.setBlock(5, 1, 5, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        NpcBase npc = npc(h, NpcProfession.FARMER, new BlockPos(2, 1, 5), new BlockPos(5, 1, 5), new BlockPos(5, 1, 5));
        npc.setFarmMode(FarmMode.CROP);
        npc.getPersonalInventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 16));
        Container barrel = storage(h, npc, new BlockPos(8, 1, 5));
        h.succeedWhen(() -> {
            h.assertTrue(count(barrel, Items.WHEAT) == 1, "exactly one harvested wheat delivered");
            h.assertTrue(h.getBlockState(new BlockPos(5, 1, 5)).is(Blocks.WHEAT)
                    && h.getBlockState(new BlockPos(5, 1, 5)).getValue(CropBlock.AGE) == 0, "crop replanted as its own persisted step");
            var receipts = FieldworkData.get(h.getLevel().getServer()).receiptsFor(npc.getUUID());
            h.assertTrue(receipts.stream().filter(r -> r.action().operation() == FieldworkAction.Operation.HARVEST).count() == 1, "no duplicate harvest");
            h.assertTrue(receipts.stream().filter(r -> r.action().operation() == FieldworkAction.Operation.PLANT).count() == 1, "one seed consumption");
            h.assertTrue(NpcBrainRuntime.reservations(h.getLevel().getServer()).snapshot().stream().noneMatch(r -> r.npcId().equals(npc.getUUID())), "finished job releases all reservations");
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 600)
    public static void workerMinesAndRestsInsteadOfStandingForever(GameTestHelper h) {
        floor(h, 12); h.setBlock(5, 1, 5, Blocks.STONE);
        NpcBase npc = worker(h, new BlockPos(5, 1, 5)); Container barrel = storage(h, npc, new BlockPos(8, 1, 5));
        h.succeedWhen(() -> {
            h.assertTrue(count(barrel, Items.COBBLESTONE) == 1, "one cobblestone delivered even below legacy four-item threshold");
            h.assertTrue(npc.getMainHandItem().getDamageValue() == 1, "one real tool wear");
            h.assertTrue(NpcBrainRuntime.data(h.getLevel().getServer()).active(npc.getUUID())
                    .filter(p -> p.currentStep().executorId().equals(FieldworkPlanFactory.REST)).isPresent(), "empty area has bounded explained rest plan");
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 1_500)
    public static void workerDiscoversAndWalksToEightyBlockTarget(GameTestHelper h) {
        floor(h, 90); h.setBlock(82, 1, 5, Blocks.IRON_ORE);
        NpcBase npc = npc(h, NpcProfession.WORKER, new BlockPos(2, 1, 5), new BlockPos(82, 1, 5), new BlockPos(82, 1, 5));
        npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        Container barrel = storage(h, npc, new BlockPos(86, 1, 5));
        h.succeedWhen(() -> {
            h.assertTrue(count(barrel, Items.RAW_IRON) == 1, "far target discovered, walked to and delivered");
            h.assertTrue(npc.getX() > h.absolutePos(new BlockPos(75, 1, 5)).getX(), "NPC walked across the work area");
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 900)
    public static void missingToolRecoversAfterSupply(GameTestHelper h) {
        floor(h, 12); h.setBlock(5, 1, 5, Blocks.STONE);
        NpcBase npc = worker(h, new BlockPos(5, 1, 5)); npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        Container barrel = storage(h, npc, new BlockPos(8, 1, 5));
        h.runAtTickTime(100, () -> {
            h.assertBlockPresent(Blocks.STONE, new BlockPos(5, 1, 5));
            h.assertTrue(count(barrel, Items.COBBLESTONE) == 0, "no output without tool");
            npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        });
        h.succeedWhen(() -> h.assertTrue(count(barrel, Items.COBBLESTONE) == 1, "same area becomes productive after tool supply"));
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 900)
    public static void fullDestinationKeepsCargoAndRecovers(GameTestHelper h) {
        floor(h, 12); h.setBlock(5, 1, 5, Blocks.STONE);
        NpcBase npc = worker(h, new BlockPos(5, 1, 5)); Container barrel = storage(h, npc, new BlockPos(8, 1, 5));
        for (int i = 0; i < barrel.getContainerSize(); i++) barrel.setItem(i, new ItemStack(Items.DIRT, 64));
        h.runAtTickTime(180, () -> {
            h.assertTrue(npc.getPersonalInventory().count(s -> s.is(Items.COBBLESTONE)) == 1, "output remains physically carried");
            h.assertTrue(npc.getMainHandItem().getDamageValue() == 1, "no repeat production while destination full");
            barrel.setItem(0, ItemStack.EMPTY);
        });
        h.succeedWhen(() -> {
            h.assertTrue(count(barrel, Items.COBBLESTONE) == 1, "original cargo deposited after space cleared");
            h.assertTrue(npc.getMainHandItem().getDamageValue() == 1, "still one mining action");
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 700)
    public static void twoWorkersCannotDuplicateOneBlock(GameTestHelper h) {
        floor(h, 12); h.setBlock(5, 1, 5, Blocks.STONE);
        NpcBase a = worker(h, new BlockPos(5, 1, 5)), b = npc(h, NpcProfession.WORKER,
                new BlockPos(2, 1, 7), new BlockPos(5, 1, 5), new BlockPos(5, 1, 5));
        b.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        Container aa = storage(h, a, new BlockPos(8, 1, 5)), bb = storage(h, b, new BlockPos(8, 1, 8));
        a.setCommand(NpcCommand.IDLE); b.setCommand(NpcCommand.IDLE);
        h.runAtTickTime(10, () -> {
            var target = FieldworkDiscovery.inspect(a, h.absolutePos(new BlockPos(5, 1, 5)));
            long now = h.getLevel().getGameTime(); String dimension = h.getLevel().dimension().location().toString();
            NpcBrainRuntime.data(h.getLevel().getServer()).save(FieldworkPlanFactory.work(a.getUUID(), target, FieldworkRoutes.plan(a, target.position(), false), dimension, now));
            NpcBrainRuntime.data(h.getLevel().getServer()).save(FieldworkPlanFactory.work(b.getUUID(), target, FieldworkRoutes.plan(b, target.position(), false), dimension, now));
            a.setCommand(NpcCommand.WORK); b.setCommand(NpcCommand.WORK);
        });
        h.succeedWhen(() -> {
            h.assertTrue(count(aa, Items.COBBLESTONE) + count(bb, Items.COBBLESTONE) == 1, "only one output for one target");
            h.assertTrue(a.getMainHandItem().getDamageValue() + b.getMainHandItem().getDamageValue() == 1, "only winner wears tool");
            h.assertTrue(NpcBrainRuntime.reservations(h.getLevel().getServer()).snapshot().stream()
                    .noneMatch(r -> r.npcId().equals(a.getUUID()) || r.npcId().equals(b.getUUID())), "both terminal reservations cleaned");
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 900)
    public static void loggingIsOneBlockPerReceipt(GameTestHelper h) {
        floor(h, 12); for (int y = 1; y <= 3; y++) h.setBlock(5, y, 5, Blocks.OAK_LOG);
        NpcBase npc = npc(h, NpcProfession.WORKER, new BlockPos(2, 1, 5), new BlockPos(5, 1, 5), new BlockPos(5, 3, 5));
        npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE)); Container barrel = storage(h, npc, new BlockPos(8, 1, 5));
        h.succeedWhen(() -> {
            h.assertTrue(count(barrel, Items.OAK_LOG) == 3, "three real logs conserved");
            var chops = FieldworkData.get(h.getLevel().getServer()).receiptsFor(npc.getUUID()).stream()
                    .filter(r -> r.action().operation() == FieldworkAction.Operation.CHOP).toList();
            h.assertTrue(chops.size() == 3 && chops.stream().map(r -> r.action().target()).distinct().count() == 3, "three auditable atomic block steps");
            h.assertTrue(npc.getMainHandItem().getDamageValue() == 3, "each chopped block wears tool once");
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 1_000)
    public static void scaffoldBuildAndRecoveryConserveOriginalMaterial(GameTestHelper h) {
        floor(h, 12); h.setBlock(5, 8, 5, Blocks.OAK_LOG);
        NpcBase npc = npc(h, NpcProfession.WORKER, new BlockPos(2, 1, 5), new BlockPos(1, 1, 1), new BlockPos(10, 9, 10));
        npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE)); npc.getPersonalInventory().setItem(0, new ItemStack(Items.DIRT, 6));
        Container barrel = storage(h, npc, new BlockPos(10, 1, 5));
        h.succeedWhen(() -> {
            h.assertTrue(count(barrel, Items.OAK_LOG) == 1, "high log harvested with real staircase");
            var data = FieldworkData.get(h.getLevel().getServer());
            h.assertTrue(data.outstandingScaffolds(npc.getUUID()).isEmpty(), "no scaffold obligations remain");
            h.assertTrue(npc.getPersonalInventory().count(s -> s.is(Items.DIRT)) == 6 && count(barrel, Items.DIRT) == 0, "starting scaffolds returned, not mistaken for new output");
            h.assertTrue(data.receiptsFor(npc.getUUID()).stream().anyMatch(r -> r.action().operation() == FieldworkAction.Operation.PLACE_SCAFFOLD), "actual placement receipts exist");
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 1_000)
    public static void canceledWorkRecoversRecordedScaffoldOutsideNewZone(GameTestHelper h) {
        floor(h, 12); h.setBlock(5, 8, 5, Blocks.OAK_LOG);
        NpcBase npc = npc(h, NpcProfession.WORKER, new BlockPos(2, 1, 5), new BlockPos(1, 1, 1), new BlockPos(10, 9, 10));
        npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE)); npc.getPersonalInventory().setItem(0, new ItemStack(Items.DIRT, 6));
        boolean[] interrupted = {false};
        h.onEachTick(() -> {
            if (!interrupted[0] && FieldworkData.get(h.getLevel().getServer()).outstandingScaffolds(npc.getUUID()).size() >= 2) {
                interrupted[0] = true;
                NpcBrainRuntime.cancel(npc, PlanCancellationReason.MANUAL, "game-test interrupt");
                npc.setWorkZone(h.absolutePos(new BlockPos(20, 1, 1)), h.absolutePos(new BlockPos(20, 1, 1)));
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(interrupted[0], "task was interrupted after real placement");
            h.assertTrue(FieldworkData.get(h.getLevel().getServer()).outstandingScaffolds(npc.getUUID()).isEmpty(), "cleanup survives plan cancellation and zone change");
            h.assertTrue(npc.getPersonalInventory().count(s -> s.is(Items.DIRT)) == 6, "all material returned");
            h.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(5, 8, 5));
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4_reload", timeoutTicks = 1_500)
    public static void savedRunningTravelAndEntityReloadWithoutDuplicateProduction(GameTestHelper h) {
        floor(h, 90); h.setBlock(82, 1, 5, Blocks.STONE);
        NpcBase first = npc(h, NpcProfession.WORKER, new BlockPos(2, 1, 5), new BlockPos(82, 1, 5), new BlockPos(82, 1, 5));
        first.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        Container barrel = storage(h, first, new BlockPos(86, 1, 5)); NpcBase[] current = {first};
        h.runAtTickTime(50, () -> current[0] = diskReload(h, current[0]));
        h.runAtTickTime(900, () -> current[0] = diskReload(h, current[0]));
        h.runAtTickTime(1_050, () -> {
            h.assertTrue(count(barrel, Items.COBBLESTONE) == 1, "saved/reloaded world still contains exactly one output");
            h.assertTrue(current[0].getMainHandItem().getDamageValue() == 1, "saved entity tool wear remains one");
            h.assertTrue(FieldworkData.get(h.getLevel().getServer()).receiptsFor(current[0].getUUID()).stream()
                    .filter(r -> r.action().operation() == FieldworkAction.Operation.MINE).count() == 1, "durable action receipt not repeated");
            current[0].setCommand(NpcCommand.STAY);
            // GameTestServer 会在下一批擦除整片场景。作业验收结束后，仅把检查点
            // 实体迁到场景外的测试停放区；成品通过统一转移搬走，绝不复制库存。
            BlockPos parked = h.absolutePos(new BlockPos(2_048, 1, 2_048));
            BlockPos parkedBarrel = parked.east(2);
            var level = h.getLevel();
            level.setChunkForced(parked.getX() >> 4, parked.getZ() >> 4, true);
            level.getChunkAt(parked);
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) level.setBlockAndUpdate(parked.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(parkedBarrel, Blocks.BARREL.defaultBlockState());
            var workData = FieldworkData.get(level.getServer());
            var oldStorage = workData.testStorage(current[0].getUUID()).orElseThrow();
            var actor = SubjectRef.character(current[0].getCharacterId());
            UUID parkedId = ContainerIds.block("fieldwork_test", level.dimension().location().toString(), parkedBarrel);
            var rights = RightsStateData.get(level.getServer());
            rights.ensureContext(parkedId, level.dimension().location() + ":" + parkedBarrel.asLong(), actor, actor, AccessPolicy.PRIVATE);
            Container destination = (Container) level.getBlockEntity(parkedBarrel);
            var move = new TransferRequest(UUID.randomUUID(), actor, TransferIntent.DELIVERY,
                    List.of(new TransferLeg(oldStorage.containerId(), parkedId, ItemFingerprint.of(new ItemStack(Items.COBBLESTONE)), 1)),
                    List.of(new ExpectedRevision(oldStorage.containerId(), rights.context(oldStorage.containerId()).revision()),
                            new ExpectedRevision(parkedId, rights.context(parkedId).revision())), "fieldwork_test", level.getGameTime());
            var moved = new TransferService(rights, TransferLedgerData.get(level.getServer())).submit(move,
                    Map.of(oldStorage.containerId(), new TransferEndpoint(oldStorage.containerId(), barrel),
                            parkedId, new TransferEndpoint(parkedId, destination)), level.getGameTime());
            h.assertTrue(moved.successful() && count(barrel, Items.COBBLESTONE) == 0 && count(destination, Items.COBBLESTONE) == 1,
                    "checkpoint staging moves, never copies, the one real product");
            workData.bindTestStorage(current[0].getUUID(), new FieldworkStorage(level.dimension().location().toString(), parkedBarrel,
                    parkedId, actor, AccessPolicy.PRIVATE, TransferIntent.DELIVERY, "fieldwork_test"));
            current[0].getNavigation().stop(); current[0].teleportTo(parked.getX() + .5D, parked.getY(), parked.getZ() + .5D);
            CompoundTag marker = new CompoundTag();
            marker.putUUID("NpcId", current[0].getUUID()); marker.putLong("NpcPosition", current[0].blockPosition().asLong());
            marker.putLong("GameTime", level.getGameTime());
            marker.putLong("BarrelPosition", parkedBarrel.asLong());
            try {
                NbtIo.writeCompressed(marker, h.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("phase4-restart-checkpoint.nbt"));
            } catch (Exception exception) { throw new IllegalStateException(exception); }
            h.succeed();
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 100)
    public static void playerProjectionDoesNotRevealFieldworkTruth(GameTestHelper h) {
        floor(h, 12); NpcBase npc = worker(h, new BlockPos(5, 1, 5));
        npc.reportWorkActivity("检查工具与随身物资", null, "TESTER_SECRET_MISSING_INPUT_7264");
        var viewer = new net.minecraft.server.level.ServerPlayer(h.getLevel().getServer(), h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "phase4-viewer"),
                net.minecraft.server.level.ClientInformation.createDefault());
        viewer.setPos(npc.getX() + 1, npc.getY(), npc.getZ());
        String projection = NpcProjectionService.forViewer(viewer, npc).toString();
        h.assertTrue(!projection.contains("TESTER_SECRET") && !projection.contains("MISSING_INPUT")
                && !projection.contains("pendingCargo") && !projection.contains("expectedState"), "ordinary projection excludes truth ledgers and failure codes");
        h.succeed();
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 700)
    public static void farmerTillsAndPlantsAfterSeedSupply(GameTestHelper h) {
        floor(h, 12); h.setBlock(5, 0, 5, Blocks.DIRT); h.setBlock(4, 0, 5, Blocks.WATER);
        NpcBase npc = npc(h, NpcProfession.FARMER, new BlockPos(2, 1, 5), new BlockPos(5, 1, 5), new BlockPos(5, 1, 5));
        npc.setFarmMode(FarmMode.CROP); npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_HOE));
        h.runAtTickTime(100, () -> {
            h.assertBlockPresent(Blocks.FARMLAND, new BlockPos(5, 0, 5));
            h.assertBlockPresent(Blocks.AIR, new BlockPos(5, 1, 5));
            npc.getPersonalInventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 3));
        });
        h.succeedWhen(() -> {
            h.assertBlockPresent(Blocks.WHEAT, new BlockPos(5, 1, 5));
            h.assertTrue(npc.getPersonalInventory().count(s -> s.is(Items.WHEAT_SEEDS)) == 2, "exactly one supplied seed consumed");
            h.assertTrue(npc.getMainHandItem().getDamageValue() == 1, "one tilling wear, planting no fake wear");
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 900)
    public static void caneHarvestPreservesBaseAndSeedReserve(GameTestHelper h) {
        floor(h, 12); h.setBlock(5, 0, 5, Blocks.DIRT); h.setBlock(4, 0, 5, Blocks.WATER);
        for (int y = 1; y <= 3; y++) h.setBlock(5, y, 5, Blocks.SUGAR_CANE);
        NpcBase npc = npc(h, NpcProfession.FARMER, new BlockPos(2, 1, 5), new BlockPos(5, 1, 5), new BlockPos(5, 3, 5));
        npc.setFarmMode(FarmMode.CANE); npc.getPersonalInventory().setItem(0, new ItemStack(Items.SUGAR_CANE, 8));
        Container barrel = storage(h, npc, new BlockPos(8, 1, 5));
        h.succeedWhen(() -> {
            h.assertTrue(count(barrel, Items.SUGAR_CANE) == 2, "two upper stalk blocks delivered, not initial planting stock");
            h.assertBlockPresent(Blocks.SUGAR_CANE, new BlockPos(5, 1, 5));
            h.assertBlockPresent(Blocks.AIR, new BlockPos(5, 2, 5));
            h.assertTrue(npc.getPersonalInventory().count(s -> s.is(Items.SUGAR_CANE)) == 8, "eight seeds remain");
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 900)
    public static void cocoaHarvestReplantsSameFacing(GameTestHelper h) {
        floor(h, 12); h.setBlock(5, 1, 5, Blocks.JUNGLE_LOG);
        h.setBlock(4, 1, 5, Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, net.minecraft.core.Direction.EAST).setValue(CocoaBlock.AGE, 2));
        NpcBase npc = npc(h, NpcProfession.FARMER, new BlockPos(2, 1, 5), new BlockPos(4, 1, 5), new BlockPos(4, 1, 5));
        npc.setFarmMode(FarmMode.COCOA); npc.getPersonalInventory().setItem(0, new ItemStack(Items.COCOA_BEANS, 8));
        Container barrel = storage(h, npc, new BlockPos(8, 1, 5));
        h.succeedWhen(() -> {
            h.assertTrue(count(barrel, Items.COCOA_BEANS) == 2, "three beans harvested, one replanted, two delivered");
            var pod = h.getBlockState(new BlockPos(4, 1, 5));
            h.assertTrue(pod.is(Blocks.COCOA) && pod.getValue(CocoaBlock.AGE) == 0
                    && pod.getValue(CocoaBlock.FACING) == net.minecraft.core.Direction.EAST, "original facing retained");
            h.assertTrue(npc.getPersonalInventory().count(s -> s.is(Items.COCOA_BEANS)) == 8, "initial beans retained");
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 700)
    public static void melonHarvestConservesExactLootAndKeepsStem(GameTestHelper h) {
        floor(h, 12); h.setBlock(4, 0, 5, Blocks.FARMLAND);
        h.setBlock(4, 1, 5, Blocks.MELON_STEM.defaultBlockState().setValue(StemBlock.AGE, 7)); h.setBlock(5, 1, 5, Blocks.MELON);
        NpcBase npc = npc(h, NpcProfession.FARMER, new BlockPos(2, 1, 5), new BlockPos(5, 1, 5), new BlockPos(5, 1, 5));
        npc.setFarmMode(FarmMode.MELON); Container barrel = storage(h, npc, new BlockPos(8, 1, 5));
        h.succeedWhen(() -> {
            int delivered = count(barrel, Items.MELON_SLICE);
            h.assertTrue(delivered > 0, "real melon loot delivered");
            int recorded = FieldworkData.get(h.getLevel().getServer()).receiptsFor(npc.getUUID()).stream()
                    .flatMap(r -> r.inventoryDeltas().stream()).filter(d -> d.fingerprint().equals("minecraft:melon_slice#0")).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();
            h.assertTrue(recorded == delivered, "all randomized loot accounted for exactly");
            h.assertBlockPresent(Blocks.MELON_STEM, new BlockPos(4, 1, 5));
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 700)
    public static void fertilizerCraftAndUseAreSeparateReceipts(GameTestHelper h) {
        floor(h, 12); h.setBlock(5, 0, 5, Blocks.FARMLAND); h.setBlock(5, 1, 5, Blocks.WHEAT);
        NpcBase npc = npc(h, NpcProfession.FARMER, new BlockPos(2, 1, 5), new BlockPos(5, 1, 5), new BlockPos(5, 1, 5));
        npc.setFarmMode(FarmMode.CROP); npc.getPersonalInventory().setItem(0, new ItemStack(Items.BONE));
        h.succeedWhen(() -> {
            var receipts = FieldworkData.get(h.getLevel().getServer()).receiptsFor(npc.getUUID());
            h.assertTrue(receipts.stream().anyMatch(r -> r.action().operation() == FieldworkAction.Operation.CRAFT_FERTILIZER), "one bone crafted durably");
            h.assertTrue(receipts.stream().anyMatch(r -> r.action().operation() == FieldworkAction.Operation.FERTILIZE), "fertilizer consumed in another durable action");
            h.assertTrue(npc.getPersonalInventory().count(s -> s.is(Items.BONE)) == 0
                    && npc.getPersonalInventory().count(s -> s.is(Items.BONE_MEAL)) == 2, "one bone becomes three meals, one used");
            h.assertTrue(h.getBlockState(new BlockPos(5, 1, 5)).getValue(CropBlock.AGE) > 0, "actual crop advanced");
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 900)
    public static void fullPersonalBagDoesNotDestroyBlockOrWearTool(GameTestHelper h) {
        floor(h, 12); h.setBlock(5, 1, 5, Blocks.STONE);
        NpcBase npc = worker(h, new BlockPos(5, 1, 5)); Container barrel = storage(h, npc, new BlockPos(8, 1, 5));
        for (int slot = 0; slot < npc.getPersonalInventory().getContainerSize(); slot++) npc.getPersonalInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        h.runAtTickTime(100, () -> {
            h.assertBlockPresent(Blocks.STONE, new BlockPos(5, 1, 5));
            h.assertTrue(npc.getMainHandItem().getDamageValue() == 0, "failed output preflight did not wear tool");
            npc.getPersonalInventory().setItem(0, ItemStack.EMPTY);
        });
        h.succeedWhen(() -> h.assertTrue(count(barrel, Items.COBBLESTONE) == 1 && npc.getMainHandItem().getDamageValue() == 1,
                "one production after bag space returns"));
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 900)
    public static void borrowedToolRequiresUseGrantAndRetainsOwner(GameTestHelper h) {
        floor(h, 12); h.setBlock(5, 1, 5, Blocks.STONE); NpcBase npc = worker(h, new BlockPos(5, 1, 5));
        npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        var tool = new ItemStack(Items.IRON_PICKAXE); npc.getPersonalInventory().setItem(0, tool);
        var rights = RightsStateData.get(h.getLevel().getServer()); UUID id = ContainerIds.npcPersonal(npc.getCharacterId());
        var actor = SubjectRef.character(npc.getCharacterId()); var owner = SubjectRef.character(UUID.randomUUID());
        rights.ensureContext(id, "npc:" + npc.getCharacterId() + ":personal", actor, actor, AccessPolicy.PRIVATE);
        rights.putClaim(new AssetClaim(UUID.randomUUID(), id, 0, ItemFingerprint.of(tool), 1, owner, actor, Set.of(), Set.of(), "test-loan", 0));
        Container barrel = storage(h, npc, new BlockPos(8, 1, 5));
        h.runAtTickTime(100, () -> {
            h.assertBlockPresent(Blocks.STONE, new BlockPos(5, 1, 5));
            h.assertTrue(npc.getPersonalInventory().getItem(0).getDamageValue() == 0, "custody alone is not use permission");
            rights.grant(new RightGrant(UUID.randomUUID(), id, actor, Set.of(AssetRight.USE), "test loan", h.getLevel().getGameTime(), null, true));
        });
        h.succeedWhen(() -> {
            h.assertTrue(count(barrel, Items.COBBLESTONE) == 1, "authorized borrowed tool works");
            var changed = npc.getPersonalInventory().getItem(0);
            h.assertTrue(changed.is(Items.IRON_PICKAXE) && changed.getDamageValue() == 1, "one borrowed-tool wear");
            h.assertTrue(rights.claimAt(id, 0, ItemFingerprint.of(changed)).beneficialOwner().equals(owner), "wear does not confiscate borrowed tool");
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 1_200)
    public static void irrigationWellIsFourAtomicBlocksAndTwoRealBuckets(GameTestHelper h) {
        floor(h, 12);
        for (int x = 1; x <= 10; x++) for (int z = 1; z <= 10; z++) h.setBlock(x, 0, z, Blocks.DIRT);
        NpcBase npc = npc(h, NpcProfession.FARMER, new BlockPos(2, 1, 2), new BlockPos(1, 1, 1), new BlockPos(10, 1, 10));
        npc.setFarmMode(FarmMode.CROP); npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_HOE));
        npc.getPersonalInventory().setItem(0, new ItemStack(Items.WATER_BUCKET));
        npc.getPersonalInventory().setItem(1, new ItemStack(Items.WATER_BUCKET));
        Container barrel = storage(h, npc, new BlockPos(10, 1, 10));
        h.succeedWhen(() -> {
            for (int x = 5; x <= 6; x++) for (int z = 5; z <= 6; z++) {
                var fluid = h.getBlockState(new BlockPos(x, 0, z)).getFluidState();
                h.assertTrue(fluid.is(net.minecraft.tags.FluidTags.WATER) && fluid.isSource(), "four infinite-source cells formed by game rules");
            }
            var receipts = FieldworkData.get(h.getLevel().getServer()).receiptsFor(npc.getUUID());
            h.assertTrue(receipts.stream().filter(r -> r.action().operation() == FieldworkAction.Operation.IRRIGATE).count() == 4, "one independent receipt per well block");
            h.assertTrue(count(barrel, Items.DIRT) == 4, "all four dug soil items delivered");
            h.assertTrue(npc.getPersonalInventory().count(s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)) == 2, "two physical buckets conserved");
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4_restart", timeoutTicks = 1500)
    public static void wholeProcessRestartRetainsPhysicalGoodsRightsAndReceipts(GameTestHelper h) {
        if (!Boolean.getBoolean("sagadyssey.phase4.requireRestart")) { h.succeed(); return; }
        h.assertTrue(priorCheckpoint != null, "checkpoint must come from previous server process");
        // Each completed GameTest batch unforces chunks, including checkpoint chunks.
        // Reacquire both physical locations in this batch before waiting for entity I/O.
        for (String key : List.of("NpcPosition", "BarrelPosition")) {
            BlockPos p = BlockPos.of(priorCheckpoint.getLong(key));
            h.getLevel().setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
            h.getLevel().getChunkAt(p);
        }
        h.succeedWhen(() -> {
            var npc = h.getLevel().getEntity(priorCheckpoint.getUUID("NpcId"));
            h.assertTrue(npc instanceof NpcBase, "saved entity loaded from previous process"); NpcBase worker = (NpcBase) npc;
            var barrel = (Container) h.getLevel().getBlockEntity(BlockPos.of(priorCheckpoint.getLong("BarrelPosition")));
            h.assertTrue(barrel != null && count(barrel, Items.COBBLESTONE) == 1, "physical container retains exactly one product after process restart");
            h.assertTrue(worker.getPersonalInventory().count(s -> s.is(Items.COBBLESTONE)) == 0
                    && worker.getMainHandItem().getDamageValue() == 1, "saved entity retains delivered inventory and tool wear");
            var data = FieldworkData.get(h.getLevel().getServer());
            h.assertTrue(data.receiptsFor(worker.getUUID()).stream().filter(r -> r.action().operation() == FieldworkAction.Operation.MINE).count() == 1
                    && data.pendingCargo(worker.getUUID()).isEmpty(), "persistent production and delivery receipts agree");
            var actor = SubjectRef.character(worker.getCharacterId());
            UUID bagId = ContainerIds.npcPersonal(worker.getCharacterId());
            var toolClaim = RightsStateData.get(h.getLevel().getServer()).claimAt(bagId,
                    worker.getPersonalInventory().getContainerSize(), ItemFingerprint.of(worker.getMainHandItem()));
            h.assertTrue(toolClaim != null && toolClaim.beneficialOwner().equals(actor), "tool rights survive whole-process restart");
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 800)
    public static void emptyAreaRestsBoundedlyAndExplicitStayCancels(GameTestHelper h) {
        floor(h, 12); NpcBase npc = worker(h, new BlockPos(5, 1, 5));
        h.runAtTickTime(100, () -> npc.setCommand(NpcCommand.STAY));
        h.runAtTickTime(150, () -> {
            h.assertTrue(NpcBrainRuntime.data(h.getLevel().getServer()).active(npc.getUUID()).isEmpty(), "explicit stay cancels professional plan");
            h.assertTrue(NpcBrainRuntime.reservations(h.getLevel().getServer()).snapshot().stream().noneMatch(r -> r.npcId().equals(npc.getUUID())), "stay releases this NPC's reservations");
            npc.setCommand(NpcCommand.WORK);
        });
        h.runAtTickTime(650, () -> {
            var data = NpcBrainRuntime.data(h.getLevel().getServer());
            h.assertTrue(data.history(npc.getUUID()).stream().filter(p -> p.currentStep().executorId().equals(FieldworkPlanFactory.REST)).count() >= 2,
                    "empty area finishes multiple finite rest plans instead of infinite exploration");
            h.assertTrue(npc.getMainHandItem().getDamageValue() == 0, "no fake work or wear in empty area"); h.succeed();
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 800)
    public static void unreachableBlockIsRememberedWhileAlternativeGetsDone(GameTestHelper h) {
        floor(h, 12);
        for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) for (int y = 1; y <= 3; y++) h.setBlock(x, y, z, Blocks.BARRIER);
        h.setBlock(1, 1, 1, Blocks.OAK_LOG); h.setBlock(9, 1, 5, Blocks.STONE);
        NpcBase npc = npc(h, NpcProfession.WORKER, new BlockPos(3, 1, 5), new BlockPos(1, 1, 1), new BlockPos(10, 3, 10));
        npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE)); Container barrel = storage(h, npc, new BlockPos(11, 1, 5));
        h.succeedWhen(() -> {
            h.assertTrue(count(barrel, Items.COBBLESTONE) == 1, "reachable alternative completed");
            h.assertTrue(FieldworkData.get(h.getLevel().getServer()).unreachable(npc.getUUID(), h.getLevel().dimension().location().toString(),
                    h.absolutePos(new BlockPos(1, 1, 1)), h.getLevel().getGameTime()).isPresent(), "enclosed target has bounded memory");
            h.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(1, 1, 1));
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4", timeoutTicks = 400)
    public static void changedProfessionAndZoneStopOldTravelBeforeMutation(GameTestHelper h) {
        floor(h, 90); h.setBlock(82, 1, 5, Blocks.STONE);
        NpcBase npc = npc(h, NpcProfession.WORKER, new BlockPos(2, 1, 5), new BlockPos(82, 1, 5), new BlockPos(82, 1, 5));
        npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        h.runAtTickTime(20, () -> {
            h.assertTrue(NpcBrainRuntime.data(h.getLevel().getServer()).active(npc.getUUID()).isPresent(), "old work plan running");
            npc.setProfession(NpcProfession.FARMER); npc.setWorkZone(h.absolutePos(new BlockPos(5, 1, 5)), h.absolutePos(new BlockPos(5, 1, 5)));
        });
        h.runAtTickTime(200, () -> {
            h.assertBlockPresent(Blocks.STONE, new BlockPos(82, 1, 5));
            h.assertTrue(FieldworkData.get(h.getLevel().getServer()).receiptsFor(npc.getUUID()).isEmpty(), "old profession did not mutate its target");
            h.assertTrue(NpcBrainRuntime.data(h.getLevel().getServer()).history(npc.getUUID()).stream()
                    .anyMatch(p -> p.status() == com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus.CANCELLED), "changed profession cancels old plan");
            h.assertTrue(NpcBrainRuntime.reservations(h.getLevel().getServer()).snapshot().stream().noneMatch(r -> r.npcId().equals(npc.getUUID())), "old reservation cleared"); h.succeed();
        });
    }

    @GameTest(template = "fieldwork_empty", batch = "phase4_reload", timeoutTicks = 1_100)
    public static void savedScaffoldBuildResumesAndRecoversExactlyOnce(GameTestHelper h) {
        floor(h, 12); h.setBlock(5, 8, 5, Blocks.OAK_LOG);
        NpcBase first = npc(h, NpcProfession.WORKER, new BlockPos(2, 1, 5), new BlockPos(1, 1, 1), new BlockPos(10, 9, 10));
        first.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE)); first.getPersonalInventory().setItem(0, new ItemStack(Items.DIRT, 6));
        Container barrel = storage(h, first, new BlockPos(10, 1, 5)); NpcBase[] current = {first}; boolean[] reloaded = {false};
        h.onEachTick(() -> {
            if (!reloaded[0] && FieldworkData.get(h.getLevel().getServer()).outstandingScaffolds(current[0].getUUID()).size() >= 2) {
                reloaded[0] = true; current[0] = diskReload(h, current[0]);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(reloaded[0] && count(barrel, Items.OAK_LOG) == 1, "resumed original scaffold job delivers one log");
            var data = FieldworkData.get(h.getLevel().getServer());
            h.assertTrue(data.outstandingScaffolds(current[0].getUUID()).isEmpty()
                    && current[0].getPersonalInventory().count(s -> s.is(Items.DIRT)) == 6, "all original material recovered exactly once after disk reload");
            var receipts = data.receiptsFor(current[0].getUUID());
            h.assertTrue(receipts.stream().filter(r -> r.action().operation() == FieldworkAction.Operation.PLACE_SCAFFOLD).count() == 6
                    && receipts.stream().filter(r -> r.action().operation() == FieldworkAction.Operation.RECOVER_SCAFFOLD).count() == 6, "six placements and six recoveries, no replay");
        });
    }

    private static NpcBase diskReload(GameTestHelper h, NpcBase npc) {
        var server = h.getLevel().getServer(); var storage = server.overworld().getDataStorage();
        CompoundTag body = npc.saveWithoutId(new CompoundTag());
        try {
            var path = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("phase4-entity-" + npc.getUUID() + ".nbt");
            NbtIo.writeCompressed(body, path);
            body = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        } catch (Exception exception) { throw new IllegalStateException("actual entity disk checkpoint failed", exception); }
        boolean workDirty = FieldworkData.get(server).isDirty();
        storage.save();
        // NeoForge 的 SavedData 写盘是异步队列；正常关闭世界会先等待队列完成。
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        try {
            int version = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
            var brain = storage.readTagFromDisk(NpcBrainData.DATA_NAME, null, version).getCompound("data");
            var work = storage.readTagFromDisk(FieldworkData.DATA_NAME, null, version).getCompound("data");
            h.assertTrue(NpcBrainData.FACTORY.deserializer().apply(brain, server.registryAccess()).isPlanned(npc.getUUID()),
                    "actual disk brain retains PLANNED mode");
            int diskActions = FieldworkData.load(work, server.registryAccess()).receiptsFor(npc.getUUID()).size();
            int liveActions = FieldworkData.get(server).receiptsFor(npc.getUUID()).size();
            h.assertTrue(diskActions == liveActions, "disk checkpoint actions " + diskActions + "/" + liveActions + " dirty=" + workDirty);
            storage.set(NpcBrainData.DATA_NAME, NpcBrainData.FACTORY.deserializer().apply(brain, server.registryAccess()));
            storage.set(FieldworkData.DATA_NAME, FieldworkData.load(work, server.registryAccess()));
        } catch (Exception exception) { throw new IllegalStateException("actual disk checkpoint reload failed", exception); }
        npc.remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
        NpcBase replacement = new NpcBase(NpcEntityTypes.NPC_BASE.get(), h.getLevel()); replacement.load(body);
        h.getLevel().addFreshEntity(replacement); NpcBrainRuntime.rebuildAfterDataReload(server);
        return replacement;
    }
    private static NpcBase worker(GameTestHelper h, BlockPos target) {
        NpcBase npc = npc(h, NpcProfession.WORKER, new BlockPos(2, 1, 5), target, target);
        npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE)); return npc;
    }
    private static NpcBase npc(GameTestHelper h, NpcProfession profession, BlockPos start, BlockPos min, BlockPos max) {
        NpcBase npc = h.spawn(NpcEntityTypes.NPC_BASE.get(), start); npc.setProfession(profession);
        npc.getPersonalInventory().clearContent(); npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY); npc.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        npc.setPersistenceRequired(); npc.setWorkZone(h.absolutePos(min), h.absolutePos(max)); npc.setCommand(NpcCommand.WORK);
        NpcBrainRuntime.setMode(npc, NpcBehaviorMode.PLANNED); return npc;
    }
    private static Container storage(GameTestHelper h, NpcBase npc, BlockPos relative) {
        h.setBlock(relative, Blocks.BARREL); BlockPos position = h.absolutePos(relative); String dimension = h.getLevel().dimension().location().toString();
        UUID id = ContainerIds.block("fieldwork_test", dimension, position); SubjectRef owner = SubjectRef.character(npc.getCharacterId());
        RightsStateData.get(h.getLevel().getServer()).ensureContext(id, dimension + ':' + position.asLong(), owner, owner, AccessPolicy.PRIVATE);
        FieldworkData.get(h.getLevel().getServer()).bindTestStorage(npc.getUUID(), new FieldworkStorage(dimension, position, id,
                owner, AccessPolicy.PRIVATE, TransferIntent.DELIVERY, "fieldwork_test"));
        return (Container) h.getLevel().getBlockEntity(position);
    }
    private static void floor(GameTestHelper h, int width) {
        for (int x = 0; x < width; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
    }
    private static int count(Container container, Item item) {
        int quantity = 0; for (int i = 0; i < container.getContainerSize(); i++) if (container.getItem(i).is(item)) quantity += container.getItem(i).getCount(); return quantity;
    }
}
