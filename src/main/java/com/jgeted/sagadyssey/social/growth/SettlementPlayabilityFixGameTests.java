package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.core.config.SagadysseyConfig;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.social.industry.common.FurnaceWorkAdapter;
import com.jgeted.sagadyssey.social.industry.common.SettlementIndustryAccess;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.TagParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Controlled real-engine regressions; not a player-input or natural livelihood acceptance. */
@GameTestHolder("sagadyssey_playability_fix")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class SettlementPlayabilityFixGameTests {
    private static final String EMPTY="sagadyssey_playability_fix:empty";

    @net.neoforged.bus.api.SubscribeEvent
    public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        if (!(event.getServer() instanceof GameTestServer)) return;
        var level=event.getServer().overworld();
        try {
            if (System.getProperty("sagadyssey.playability.phase", "all").equals("housing-permanent-resume")) {
                var server = event.getServer();
                var disk = net.minecraft.nbt.NbtIo.readCompressed(
                        server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("level.dat"),
                        net.minecraft.nbt.NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
                if (!MinecraftApi.containsTag(disk, "Time", net.minecraft.nbt.Tag.TAG_LONG))
                    throw new IllegalStateException("stopped world time required");
                Sagadyssey.LOGGER.info("HOUSING_GAMETEST_CLOCK bootstrap={} saved={}",
                        level.getGameTime(), disk.getLongOr("Time", 0L));
                server.getWorldData().overworldData().setGameTime(disk.getLongOr("Time", 0L));
                server.getWorldData().overworldData().setDayTime(disk.getLongOr("DayTime", 0L));
                com.jgeted.sagadyssey.core.MinecraftApi.loadRules(level, disk.getCompoundOrEmpty("game_rules"));
            }
            level.getStructureManager().getOrCreate(Identifier.parse(EMPTY)).load(
                    level.holderLookup(Registries.BLOCK),
                    TagParser.parseCompoundFully("{size:[16,8,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
        } catch(Exception ex) { throw new IllegalStateException(ex); }
    }

    @BeforeBatch(batch="playability_fix")
    public static void setup(ServerLevel level) {
        if (System.getProperty("sagadyssey.playability.phase","all").equals("housing-permanent-resume")) return;
        level.getGameRules().set(GameRules.SPAWN_MOBS, false,level.getServer());
        level.getGameRules().set(GameRules.ADVANCE_TIME, false,level.getServer());
        level.setDayTime(6000);
        level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
    }

    @GameTestGenerator public static Collection<TestFunction> tests() {
        String phase=System.getProperty("sagadyssey.playability.phase","all");
        var tests=new ArrayList<TestFunction>();
        if(phase.equals("all")||phase.equals("placement")) tests.add(test("reported_seed_rejects_overlapping_legacy_start",SettlementPlayabilityFixGameTests::placement));
        if(phase.equals("all")||phase.equals("door")) tests.add(test("manual_door_open_and_reopen_keep_fresh_grace",h->door(h,false)));
        if(phase.equals("all")||phase.equals("occupied")) tests.add(test("occupied_door_is_not_closed_on_living_entity",h->door(h,true)));
        if(phase.equals("all")||phase.equals("housing")) {
            tests.add(test("vacant_construction_door_closes",SettlementPlayabilityFixGameTests::constructionDoor));
            tests.add(test("temporary_bed_retries_only_assignment",SettlementPlayabilityFixGameTests::temporaryBed));
        }
        if(phase.equals("housing-cycle")) tests.add(new TestFunction("playability_fix",
                "original_worker_builds_and_sleeps_in_temporary_bed", EMPTY, 72000, 0, true,
                SettlementPlayabilityFixGameTests::housingCycle));
        if(phase.equals("housing-permanent-resume")) tests.add(new TestFunction("playability_fix",
                "original_worker_sleeps_in_finished_home_after_restart", EMPTY, 2400, 0, true,
                SettlementPlayabilityFixGameTests::housingPermanentResume));
        if(phase.equals("industries-runtime")) tests.add(new TestFunction("playability_fix",
                "registered_resident_uses_known_owned_compatible_furnace", EMPTY, 6000, 0, true,
                h -> industryRuntime(h,false)));
        if(phase.equals("industries-kitchen")) tests.add(new TestFunction("playability_fix",
                "registered_resident_opens_small_kitchen_batch_from_actual_stock", EMPTY, 6000, 0, true,
                h -> industryRuntime(h,true)));
        return tests;
    }

    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> body) {
        return new TestFunction("playability_fix",name,EMPTY,1200,0,true,body);
    }

    private static void placement(GameTestHelper h) {
        var l=h.getLevel();var sets=l.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
        var legacy=(RandomSpreadStructurePlacement)sets.getValue(Identifier.parse("sagadyssey:vilg_1")).placement();
        var growth=(RandomSpreadStructurePlacement)sets.getValue(Identifier.parse("sagadyssey:vilg_2")).placement();
        var biome=new FixedBiomeSource(l.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.BIRCH_FOREST));
        long reportedSeed=4485389787415629757L;
        var state=ChunkGeneratorStructureState.createForNormal(l.getChunkSource().randomState(),reportedSeed,biome,sets);
        var rawLegacy=legacy.getPotentialStructureChunk(reportedSeed,-402,-41);
        var rawGrowth=growth.getPotentialStructureChunk(reportedSeed,-402,-41);
        h.assertTrue(rawLegacy.x==-402&&rawLegacy.z==-41&&rawGrowth.equals(rawLegacy),"regression seed retains the original colliding random-spread candidates");
        h.assertTrue(growth.isStructureChunk(state,-402,-41),"growth candidate is preserved at reported coordinates");
        h.assertTrue(!legacy.isStructureChunk(state,-402,-41),"legacy structure is excluded at the overlapping growth candidate");
        int retainedLegacy=0,retainedGrowth=0;
        for(long seed:new long[]{reportedSeed,0L,1L,-1L}) {
            var sampled=ChunkGeneratorStructureState.createForNormal(l.getChunkSource().randomState(),seed,biome,sets);
            for(int rx=-12;rx<=12;rx++)for(int rz=-12;rz<=12;rz++) {
                var a=legacy.getPotentialStructureChunk(seed,rx*24,rz*24);
                if(legacy.isStructureChunk(sampled,a.x,a.z)) {
                    retainedLegacy++;
                    for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)
                        h.assertTrue(!growth.isStructureChunk(sampled,a.x+dx,a.z+dz),"accepted legacy start cannot enter the growth exclusion radius");
                }
                var b=growth.getPotentialStructureChunk(seed,rx*24,rz*24);
                if(growth.isStructureChunk(sampled,b.x,b.z))retainedGrowth++;
            }
        }
        h.assertTrue(retainedLegacy>0&&retainedGrowth>0,"neither settlement family is disabled");
        Sagadyssey.LOGGER.info("PLAYABILITY_PLACEMENT_PASS seed={} chunk=-402,-41 growthRetained=true legacyExcluded=true sampledSeeds=4 retainedLegacy={} retainedGrowth={} controlledBiome=birch_forest",reportedSeed,retainedLegacy,retainedGrowth);
        h.succeed();
    }

    private static void door(GameTestHelper h,boolean occupied) {
        var l=h.getLevel();var b=NaturalVillageR1GameTests.fixture(h,occupied?1024:0,GrowthLayout.COORDINATED);
        boolean[] started={false};long[] begin={0};net.minecraft.world.entity.decoration.ArmorStand[] blocker={null};
        h.onEachTick(()-> {
            var data=SettlementSafetyData.existing(l.getServer());var row=data==null?null:data.site(b.buildingKey());
            if(row==null)return;
            var position=NaturalVillageLayout.at(b,new BlockPos(12,1,21));
            var actor=NaturalVillageR1GameTests.actor(l,b,0);
            if(!started[0]) {
                for(int i=0;i<3;i++) {
                    var n=NaturalVillageR1GameTests.actor(l,b,i);n.setNoAi(true);n.getNavigation().stop();
                }
                row.putLong("LastThreat",l.getGameTime()-1000);
                var state=l.getBlockState(position);h.assertTrue(state.is(Blocks.OAK_DOOR),"fixture has the real registered external oak door");
                ((DoorBlock)state.getBlock()).setOpen(null,l,state,position,false);
                row.putLong("DoorTick",-1);SettlementSafetyRuntime.maintain(actor);
                row.putLong("DoorOpen:"+position.asLong(),l.getGameTime()-1000);
                ((DoorBlock)state.getBlock()).setOpen(null,l,l.getBlockState(position),position,true);
                if(occupied) {
                    blocker[0]=EntityType.ARMOR_STAND.create(l, net.minecraft.world.entity.EntitySpawnReason.COMMAND);blocker[0].setNoGravity(true);
                    blocker[0].snapTo(position.getX()+.5,position.getY(),position.getZ()+.5,0,0);l.addFreshEntity(blocker[0]);
                }
                begin[0]=l.getGameTime();started[0]=true;
            }
            long elapsed=l.getGameTime()-begin[0];
            if(!occupied&&elapsed==20) {
                ((DoorBlock)l.getBlockState(position).getBlock()).setOpen(null,l,l.getBlockState(position),position,false);
                row.putLong("DoorTick",-1);SettlementSafetyRuntime.maintain(actor);
            }
            if(!occupied&&elapsed==21)
                ((DoorBlock)l.getBlockState(position).getBlock()).setOpen(null,l,l.getBlockState(position),position,true);
            if(occupied&&elapsed==55)blocker[0].discard();
            row.putLong("DoorTick",-1);SettlementSafetyRuntime.maintain(actor);
            boolean open=l.getBlockState(position).getValue(DoorBlock.OPEN);
            if(occupied&&elapsed<55)h.assertTrue(open,"controller cannot close on a living entity occupying the doorway");
            if(!occupied&&(elapsed<20||elapsed>=21&&elapsed<61))h.assertTrue(open,"a newly opened or reopened door must not inherit an expired NPC timer");
            if(elapsed>=65) {
                h.assertTrue(!open,"unoccupied door still closes after its hold period");
                Sagadyssey.LOGGER.info("PLAYABILITY_DOOR_PASS occupied={} elapsed={} npcActorsFrozen=true directBlockOpen=true playerMouseInput=false",occupied,elapsed);
                h.succeed();
            }
        });
    }

    private static void constructionDoor(GameTestHelper h) {
        var level = h.getLevel();
        var village = NaturalVillageR1GameTests.fixture(h, 2048, GrowthLayout.TEMPLATE);
        var door = NaturalVillageLayout.at(village, new BlockPos(16, 1, 12));
        var opened = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true);
        level.setBlock(door, opened, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        level.setBlock(door.above(), opened.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER),
                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        var row = new CompoundTag();
        row.putInt("Placed", 56);
        row.putString("HouseStage", "DONE");
        var growth = SettlementGrowthData.get(level.getServer());
        long start = level.getGameTime();
        growth.maintainEntrance(level.getServer(), level, village, row, start);
        h.assertTrue(level.getBlockState(door).getValue(DoorBlock.OPEN), "freshly opened construction door receives its grace period");
        h.runAfterDelay(39, () -> {
            growth.maintainEntrance(level.getServer(), level, village, row, level.getGameTime());
            h.assertTrue(level.getBlockState(door).getValue(DoorBlock.OPEN), "vacant door stays open inside the grace period");
        });
        h.runAfterDelay(41, () -> {
            growth.maintainEntrance(level.getServer(), level, village, row, level.getGameTime());
            h.assertTrue(!level.getBlockState(door).getValue(DoorBlock.OPEN), "vacant construction door closes when no resident is requesting entry");
            h.succeed();
        });
    }

    private static void temporaryBed(GameTestHelper h) {
        var level = h.getLevel();
        var server = level.getServer();
        var village = NaturalVillageR1GameTests.fixture(h, 3072, GrowthLayout.COORDINATED);
        h.onEachTick(() -> {
            var lives = NpcLifeData.get(server);
            var actor = NaturalVillageR1GameTests.actor(level, village, 0);
            var worker = lives.record(NaturalVillageLifeData.person(village, 2));
            if (actor == null || worker == null) return;
            long now = level.getGameTime();
            var bed = SettlementBedRelocation.temporary(village);
            var head = bed.positions().getLast();
            var original = lives.record(actor.getCharacterId()).bedPosition();
            var task = new CompoundTag();
            task.putInt("Placed", 1);
            task.putString("State", "WORKING");
            var growth = SettlementGrowthData.get(server);
            h.assertTrue(original != null, "original assigned bed");
            for (int i = 0; i < bed.positions().size(); i++)
                level.setBlock(bed.positions().get(i), bed.states().get(i), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            h.assertTrue(lives.assignBed(actor.getCharacterId(), village.dimension(), head, "test collision", now), "inject a conflicting assignment");
            SettlementCoordinatorData.temporaryBed(actor, growth, village, task, now);
            h.assertTrue(!task.getStringOr("State", "").equals("DONE") && !task.getBooleanOr("MaterialCarried", false), "conflict neither finalizes nor starts a second placement");
            h.assertTrue(lives.assignBed(actor.getCharacterId(), village.dimension(), original, "original bed", now), "restore the original assignment");
            var foot = bed.positions().getFirst();
            level.setBlock(foot, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            SettlementCoordinatorData.temporaryBed(actor, growth, village, task, now);
            h.assertTrue(lives.record(worker.npcId()).bedPosition() == null, "missing physical bed part blocks assignment");
            level.setBlock(foot, bed.states().getFirst(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            SettlementCoordinatorData.temporaryBed(actor, growth, village, task, now);
            h.assertTrue(task.getStringOr("State", "").equals("DONE") && head.equals(lives.record(worker.npcId()).bedPosition()), "complete only the original pending assignment");
            h.assertTrue(task.getIntOr("Placed", 0) == 1 && !task.getBooleanOr("MaterialCarried", false) && !task.read("LastTransfer", UUIDUtil.CODEC).isPresent(), "no second material transfer or placement");
            h.succeed();
        });
    }

    private static void housingCycle(GameTestHelper h) {
        var level = h.getLevel();
        var server = level.getServer();
        var village = NaturalVillageR1GameTests.fixture(h, 0, GrowthLayout.COORDINATED);
        var bed = SettlementBedRelocation.temporary(village);
        var head = bed.positions().getLast();
        long started = level.getGameTime();
        boolean[] nightScheduled = {false};
        boolean[] sleptInTemporaryBed = {false};
        long[] sleepWindowStarted = {-1L};
        server.tickRateManager().requestGameToSprint(73000);
        h.onEachTick(() -> {
            var worker = NaturalVillageR1GameTests.actor(level, village, 2);
            var coordinator = SettlementCoordinatorData.existing(server);
            if (worker == null || coordinator == null) return;
            var site = coordinator.site(village.buildingKey());
            if (site == null) return;
            var task = SettlementCoordinatorData.job(site, "TEMP_BED");
            if (!task.getStringOr("State", "").equals("DONE")) return;
            if (!nightScheduled[0]) {
                h.assertTrue(task.getIntOr("Placed", 0) == 1 && !task.read("MaterialTransfer", UUIDUtil.CODEC).isPresent()
                        && task.read("LastTransfer", UUIDUtil.CODEC).isPresent()
                        && SettlementBedRelocation.originalBedPresent(level, bed),
                        "completed original material transfer and physical placement");
                // The construction finish tick varies with the fixture's orientation and pathing.
                // Start the sleep window while the temporary bed still exists.
                long time = level.getDayTime();
                long night = time - Math.floorMod(time, 24000L) + 13000L;
                if (night < time) night += 24000L;
                level.setDayTime(night);
                level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                sleepWindowStarted[0] = level.getGameTime();
                nightScheduled[0] = true;
            }
            if (!sleptInTemporaryBed[0] && worker.isSleeping() && worker.getSleepingPos().filter(head::equals).isPresent()) {
                h.assertTrue(worker.getCharacterId().equals(NaturalVillageLifeData.person(village, 2)),
                        "original worker is using the bed");
                sleptInTemporaryBed[0] = true;
                Sagadyssey.LOGGER.info("HOUSING_CYCLE_TEMPORARY_SLEEP worker={} elapsed={}",
                        worker.getCharacterId(), level.getGameTime() - started);
            }
            if (sleepWindowStarted[0] >= 0 && (sleptInTemporaryBed[0] || level.getGameTime() - sleepWindowStarted[0] >= 2500)) {
                var life = NpcLifeData.get(server).record(worker.getCharacterId());
                var plan = com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.data(server).active(worker.getUUID());
                Sagadyssey.LOGGER.info("HOUSING_TEMPORARY_WINDOW slept={} assigned={} expected={} block={} command={} active={}",
                        sleptInTemporaryBed[0], life == null ? null : life.bedPosition(), head,
                        level.getBlockState(head), worker.getCommand(), plan.map(value -> value.currentStep().detail()).orElse("none"));
                level.getGameRules().set(GameRules.ADVANCE_TIME, true, server);
                sleepWindowStarted[0] = -1L;
            }
            var house = SettlementCoordinatorData.job(site, "HOUSE");
            if (!house.getStringOr("State", "").equals("DONE")) return;
            h.assertTrue(sleptInTemporaryBed[0] && house.getIntOr("Placed", 0) == 57
                    && SettlementCoordinatorData.done(site, "RELOCATE"),
                    "original worker slept before the same bed was relocated to the completed home");
            Sagadyssey.LOGGER.info("HOUSING_CYCLE_ACCEPT worker={} elapsed={} houseCompleted={}",
                    worker.getCharacterId(), level.getGameTime() - started, house.getLongOr("Completed", 0L));
            h.succeed();
        });
    }

    private static void housingPermanentResume(GameTestHelper h) {
        var level = h.getLevel();
        var server = level.getServer();
        var sites = SettlementCoordinatorData.get(server).sites();
        h.assertTrue(sites.size() == 1, "requires the stopped single-site housing-cycle world");
        var site = sites.getFirst();
        var village = SettlementCoordinatorData.building(site);
        // Restore the same original fixture load boundary. Region tickets are
        // runtime state and were absent from the stopped world's chunks.dat.
        NaturalVillageR1GameTests.visiting(level, village, true);
        var house = SettlementCoordinatorData.job(site, "HOUSE");
        var temporary = SettlementCoordinatorData.job(site, "TEMP_BED");
        h.assertTrue(house.getStringOr("State", "").equals("DONE") && house.getIntOr("Placed", 0) == 57
                && temporary.getStringOr("State", "").equals("DONE")
                && SettlementCoordinatorData.done(site, "RELOCATE"), "restore the completed original construction and relocation facts");
        var person = NaturalVillageLifeData.person(village, 2);
        var head = NaturalVillageLayout.at(village, GrowthLayout.BED_HEAD);
        var record = NpcLifeData.get(server).record(person);
        h.assertTrue(record != null && head.equals(record.bedPosition()), "original worker has the persisted permanent assignment");
        h.assertTrue(level.getBlockState(head).is(Blocks.WHITE_BED), "original finished bed exists");
        long start = level.getGameTime();
        // Isolate the first night check after restarting. No task, material or body is edited.
        level.setDayTime(level.getDayTime() / 24000L * 24000L + 13000L);
        server.tickRateManager().requestGameToSprint(3000);
        h.onEachTick(() -> {
            var worker = NaturalVillageR1GameTests.actor(level, village, 2);
            if (worker == null || !worker.isSleeping() || worker.getSleepingPos().filter(head::equals).isEmpty()) return;
            h.assertTrue(worker.getCharacterId().equals(person), "same original person and body");
            Sagadyssey.LOGGER.info("PERMANENT_HOUSING_ACCEPT worker={} elapsed={} houseCompleted={} controlledNight=true",
                    person, level.getGameTime() - start, house.getLongOr("Completed", 0L));
            h.succeed();
        });
    }

    private static void industryRuntime(GameTestHelper h, boolean automatic) {
        var level = h.getLevel();
        var server = level.getServer();
        var village = NaturalVillageR1GameTests.fixture(h, 0, GrowthLayout.COORDINATED);
        server.tickRateManager().requestGameToSprint(6500);
        UUID[] needId = {null};
        BlockPos[] legal = {null};
        boolean[] prepared = {false};
        int quantity = automatic ? 1 : 2;
        h.onEachTick(() -> {
            var worker = NaturalVillageR1GameTests.actor(level, village, 2);
            if (worker == null || !SettlementCoordinatorData.active(server, village.buildingKey())) return;
            var owner = SubjectRef.character(worker.getCharacterId());
            var store = NaturalVillageLayout.at(village, NaturalVillageLayout.WOOD);
            var flows = SupplyFlowData.get(server);
            var storageId = ProductionContainerIds.resolve(server, village.dimension(), store);
            var point = flows.point(storageId);
            var product = ItemFingerprint.of(new ItemStack(Items.BAKED_POTATO));
            if (!prepared[0]) {
                h.assertTrue(!SettlementIndustryAccess.fixture(worker, "sagadyssey.industry.native"),
                        "normal admission branch only");
                SagadysseyConfig.ENABLE_SETTLEMENT_INDUSTRIES.set(false);
                h.assertTrue(!SettlementIndustryAccess.resident(worker), "configuration must opt in");
                SagadysseyConfig.ENABLE_SETTLEMENT_INDUSTRIES.set(true);
                h.assertTrue(SettlementIndustryAccess.resident(worker), "existing local founder qualifies");
                h.assertTrue(point != null && point.owner().equals(owner), "existing private storage authority");
                var box = SupplyFlowService.container(server, point);
                h.assertTrue(box != null, "original physical storage loaded");
                var slots = new ArrayList<Integer>();
                for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).isEmpty()) slots.add(i);
                h.assertTrue(slots.size() >= 2, "two unused input slots without overwriting the worker's harvest");
                h.assertTrue(SupplyFlowService.count(box, ItemFingerprint.of(new ItemStack(Items.POTATO))) == 0
                        && SupplyFlowService.count(box, ItemFingerprint.of(new ItemStack(Items.COAL))) == 0,
                        "no preexisting furnace inputs");
                box.setItem(slots.get(0), new ItemStack(Items.POTATO, quantity));
                box.setItem(slots.get(1), new ItemStack(Items.COAL));
                var rights = RightsStateData.get(server);
                rights.bumpContext(storageId);
                var stations = new ArrayList<BlockPos>();
                for (var cell : BlockPos.betweenClosed(store.offset(-4, 0, -4), store.offset(4, 0, 4))) {
                    if (cell.distSqr(store) <= 16 && level.isEmptyBlock(cell) && level.isEmptyBlock(cell.above())
                            && level.getBlockState(cell.below()).isFaceSturdy(level, cell.below(), net.minecraft.core.Direction.UP)) {
                        stations.add(cell.immutable());
                    }
                }
                stations.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(store)).thenComparingLong(BlockPos::asLong));
                h.assertTrue(stations.size() >= 3, "test preparation requires three nearby station sites");
                for (int i = 0; i < 3; i++) {
                    var pos = stations.get(i);
                    level.setBlockAndUpdate(pos, (i == 1 ? Blocks.BLAST_FURNACE : Blocks.FURNACE).defaultBlockState());
                    var id = ProductionContainerIds.resolve(server, village.dimension(), pos);
                    var title = i == 0 ? SubjectRef.institution(village.settlementId()) : owner;
                    rights.ensureContext(id, village.dimension() + ":" + pos.asLong(), title, title, AccessPolicy.PRIVATE);
                    flows.register(new FieldworkStorage(village.dimension(), pos, id, title, AccessPolicy.PRIVATE,
                            TransferIntent.DELIVERY, "industry-runtime-fixture-station"));
                    flows.reveal(owner, id);
                    if (i == 2) legal[0] = pos;
                }
                if (!automatic) {
                    long now = level.getGameTime();
                    var need = SupplyFlowService.request(server, owner, "industry-runtime-fixture", point,
                            product, quantity, now, now + 5600);
                    needId[0] = need.id();
                }
                prepared[0] = true;
                return;
            }
            if (automatic && needId[0] == null) {
                var requests = NeedRequestData.get(server).requests().stream()
                        .filter(r -> r.key().requester().equals(owner.id())
                                && r.key().purpose().equals("CHARACTER:local-kitchen")).toList();
                if (requests.isEmpty()) return;
                h.assertTrue(requests.size() == 1 && requests.getFirst().originalRequested() == quantity,
                        "ordinary scheduler requests only the physically available batch");
                needId[0] = requests.getFirst().id();
            }
            var id = flows.production(needId[0]);
            if (id == null) return;
            var order = ProductionOrderData.get(server).byId(id).orElseThrow();
            h.assertTrue(legal[0].equals(order.workstationPosition()), "unowned and wrong-recipe stations are skipped");
            if (order.status() != ProductionOrderStatus.COMPLETED) return;
            var box = SupplyFlowService.container(server, point);
            h.assertTrue(FurnaceWorkAdapter.completedDelivery(server, order), "four original transfer receipts confirmed");
            h.assertTrue(SupplyFlowService.count(box, product) == quantity
                    && SupplyFlowService.count(box, ItemFingerprint.of(new ItemStack(Items.POTATO))) == 0
                    && SupplyFlowService.count(box, ItemFingerprint.of(new ItemStack(Items.COAL))) == 0,
                    "exact native input and product balance");
            Sagadyssey.LOGGER.info("SETTLEMENT_INDUSTRY_RUNTIME_ACCEPT person={} order={} station={} produced={} automatic={}",
                    worker.getCharacterId(), order.id(), legal[0], quantity, automatic);
            h.succeed();
        });
    }
}
