package com.jgeted.sagadyssey.core.structure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData;
import com.jgeted.sagadyssey.npc.brain.life.LifeActivityData;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBrainData;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.social.needs.NeedRequestData;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import com.jgeted.sagadyssey.npc.brain.life.LifePlanFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.TreeMap;

/**
 * DRAFT, not yet compiled. Ordinary dedicated-server observation only, excluded from
 * the production jar by ** / *GameTests*.class (without the spaces).
 * No fixture, chunk forcing, inventory, life/rights creation, business command,
 * movement, game-time, crop or gamerule mutation exists in this observer.
 * At the frozen boundaries it requests normal server shutdown. External real client
 * supplies loading and the ordinary member commands; raw evidence still needs audit.
 */
@EventBusSubscriber(modid = "sagadyssey")
public final class NaturalFarmLongGameTests {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static Run run;
    private static boolean enabled() { return "LONG_OBSERVATION".equals(System.getProperty("sagadyssey.naturalFarm.world", "")); }

    @SubscribeEvent public static void preparing(ServerAboutToStartEvent e) {
        if (!enabled()) return;
        try { run = new Run(e.getServer()); run.prepare(); }
        catch (Exception ex) { throw new IllegalStateException("Long-observation admission rejected", ex); }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void started(ServerStartedEvent e) {
        if (run == null || run.server != e.getServer()) return;
        try { run.emit("SERVER_STARTED", run.environment()); }
        catch (Exception ex) { run.failure(ex); }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e) {
        if (run == null || run.server != e.getServer() || run.stopping) return;
        try { run.tick(); } catch (Exception ex) { run.failure(ex); }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void spawn(FinalizeSpawnEvent e) {
        if (run == null || e.getEntity().level() != run.server.overworld()
                || e.getSpawnType() != MobSpawnType.NATURAL
                || e.getEntity().getType().getCategory() != MobCategory.MONSTER) return;
        if (e.getEntity().blockPosition().distSqr(run.origin) <= 160 * 160)
            run.naturalSpawnIntents.add(e.getEntity().getUUID());
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void joined(EntityJoinLevelEvent e) {
        if (run == null || e.getLevel() != run.server.overworld() || e.loadedFromDisk()
                || !run.naturalSpawnIntents.remove(e.getEntity().getUUID())) return;
        if (!e.isCanceled()) run.pendingNaturalJoins.add(e.getEntity().getUUID());
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void playerAte(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Finish e) {
        if (run == null || run.stopping || run.state.readyAt < 0 || !(e.getEntity() instanceof ServerPlayer player)
                || player.server != run.server || player.getAbilities().instabuild || !e.getItem().is(Items.CARROT)) return;
        // An actual finish event is separate from NPC output consumption and from a wage transfer.
        run.state.playerCarrotsConsumed++;
        try { run.emit("ACTUAL_PLAYER_CARROT_CONSUMPTION", Map.of("account", player.getUUID().toString(), "quantity", 1)); }
        catch (Exception ex) { run.failure(ex); }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void stopping(ServerStoppingEvent e) {
        if (run == null || run.server != e.getServer()) return;
        try {
            if (run.reason.isEmpty()) run.reason = "UNEXPECTED_STOP_REQUIRES_REVIEW";
            run.emit("NORMAL_STOPPING", run.snapshot());
        } catch (Exception ex) { run.reason = "STOP_SNAPSHOT_FAILED: " + ex; }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void stopped(ServerStoppedEvent e) {
        if (run == null || run.server != e.getServer()) return;
        try {
            var row = new LinkedHashMap<String, Object>();
            row.put("state", run.state); row.put("reason", run.reason); row.put("seed", run.seed);
            row.put("episode", run.episode); row.put("serverStoppedEvent", true);
            row.put("world", run.world.toString()); row.put("utc", Instant.now().toString());
            row.put("finalAudit", run.finalAudit);
            row.put("note", "External diagnostic checkpoint; no game data is rewritten on resume");
            Files.writeString(run.directory.resolve("episode-" + run.episode + "-stopped.json"),
                    JSON.toJson(row), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (Exception ex) { throw new IllegalStateException("Cannot retain full-process stopping evidence", ex); }
    }

    /** External diagnostic counters, not a new game SavedData or authority. */
    private static final class State {
        long t0 = -1, readyAt = -1, lastObserved = -1, firstDelivery = -1, coldComplete = -1;
        long netCrop, naturalMonsterJoins, snapshots, eligibleGateSamples, ineligibleGateSamples;
        long stableNetCrop = Long.MIN_VALUE, stableMeals = -1;
        String firstCarryingRoute = "";
        long[] lastMeal = {-1, -1, -1}; int[] meals = {0, 0, 0}, workRest = {0, 0, 0};
        String[] lastWorkRest = {"", "", ""};
        Set<String> receipts = new LinkedHashSet<>(), routeStages = new LinkedHashSet<>(), outputMeals = new LinkedHashSet<>();
        Set<Integer> afterDeliveryMeals = new LinkedHashSet<>();
        Map<String, Integer> crops = new LinkedHashMap<>(), harvests = new LinkedHashMap<>(), replants = new LinkedHashMap<>();
        Map<String, Long> pendingHarvest = new LinkedHashMap<>();
        Map<String, Long> openingItems = new TreeMap<>(), configurationItems = new TreeMap<>(), itemNet = new TreeMap<>();
        Map<String, Long> externalItems = new TreeMap<>(), lastItems = new TreeMap<>(), firstNeed = new TreeMap<>();
        Map<String, Long> reservationFirstSeen = new TreeMap<>(), eligibleNeedTicks = new TreeMap<>();
        Map<String, String> lastDrops = new TreeMap<>(), lastLocations = new TreeMap<>();
        Set<String> initialBodies = new LinkedHashSet<>(), transferIds = new LinkedHashSet<>(), verifiedMeals = new LinkedHashSet<>();
        Set<String> reviewReasons = new LinkedHashSet<>(), failedChecks = new LinkedHashSet<>(), knownVillageContainers = new LinkedHashSet<>();
        long playerCarrotsConsumed, villageCarrotNetOut, stableVillageNetOut, stablePlayerMeals;
        long playerPaidCarrots, playerDeliveredOak, memberPaidAt = -1, memberReplayObservedAt = -1;
        String memberAccount = "", memberPerson = "", memberTransaction = "";
        boolean openingCaptured, configurationCaptured;
        boolean harvested, replanted, raw, carrying, delivered;
        Set<String> itemActions = new LinkedHashSet<>();
        Map<String,String> transferProofs = new TreeMap<>();
        Map<String,Long> routeDelivered = new TreeMap<>();
        Set<String> realCarryingRoutes = new LinkedHashSet<>();
        long[] severeSince = {-1,-1,-1};
        Set<String> remoteReceiptKeys = new LinkedHashSet<>();
        long remoteNet, remoteMeals;
    }
    private static final class Run {
        final MinecraftServer server;
        final int sample = Integer.parseInt(System.getProperty("sagadyssey.naturalFarm.sample", "0"));
        final boolean resume = Boolean.getBoolean("sagadyssey.naturalFarm.resume");
        final int episode = resume ? 1 : 0;
        final long seed = sample == 1 ? 7019 : 7021;
        final BlockPos origin = sample == 1 ? new BlockPos(-1088, 86, -752) : new BlockPos(16, 69, 800);
        final Rotation rotation = sample == 1 ? Rotation.COUNTERCLOCKWISE_90 : Rotation.CLOCKWISE_180;
        final Set<UUID> naturalSpawnIntents = new LinkedHashSet<>();
        final Set<UUID> pendingNaturalJoins = new LinkedHashSet<>();
        State state = new State(); NaturalVillageBuilding building;
        Path world, directory, events, metrics;
        String reason = ""; boolean stopping;
        Map<String, Object> finalAudit = Map.of("status", "NOT_AT_FINAL_BOUNDARY");
        long resumeStarted = -1; boolean resumeBoundary = resume;
        final Map<String, ItemEntity> lastDropObjects = new LinkedHashMap<>();
        final long preparedAtNanos = System.nanoTime();
        Run(MinecraftServer server) { this.server = server; }
        void prepare() throws Exception {
            require(server instanceof DedicatedServer, "An ordinary DedicatedServer is required");
            require(sample == 1 || sample == 2, "Exactly the two frozen samples are allowed");
            require(!resume || sample == 2, "Only sample 2 has the frozen full-process restart");
            Path root = Path.of(System.getProperty("sagadyssey.naturalFarm.evidenceRoot", "")).toAbsolutePath().normalize();
            require(root.getFileName().toString().equals("natural-long-final"), "Separate final evidence root required");
            directory = root.resolve("seed-" + seed);
            world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            require(world.equals(directory.resolve("world")), "World path differs from frozen sample");
            require(server.getWorldData().worldGenOptions().seed() == seed, "Frozen seed mismatch");
            require(server.getWorldData().worldGenOptions().generateStructures()
                    && !server.getWorldData().isFlatWorld() && !server.getWorldData().isDebugWorld(), "Normal native generation required");
            require(!Boolean.getBoolean("sagadyssey.society.discovery") && !Boolean.getBoolean("sagadyssey.p2.discovery"), "Legacy discovery disabled");
            Files.createDirectories(directory);
            if (resume) {
                var old = JSON.fromJson(Files.readString(directory.resolve("episode-0-stopped.json")), JsonObject.class);
                require(old.get("serverStoppedEvent").getAsBoolean()
                        && "FROZEN_RESTART_BOUNDARY".equals(old.get("reason").getAsString()), "Original normal stop checkpoint required");
                state = JSON.fromJson(old.get("state"), State.class);
                require(state.t0 >= 0 && state.lastObserved >= state.t0 + 120000, "Original clock/counters missing");
                require(server.getWorldData().overworldData().getGameTime() == state.lastObserved,
                        "Disk gameTime differs from stopped observation; do not silently repair clock");
            } else {
                require(!server.getWorldData().overworldData().isInitialized()
                        && server.getWorldData().overworldData().getGameTime() == 0, "Fresh world required; no 1200-tick precheck prefix");
                require(!Files.exists(directory.resolve("episode-0-stopped.json")), "Do not reuse an earlier attempt");
            }
            events = directory.resolve("episode-" + episode + "-events.jsonl"); Files.createFile(events);
            metrics = directory.resolve("episode-" + episode + "-metrics.csv");
            Files.writeString(metrics, "gameTime,elapsed,tickNanos,usedHeap,maxHeap,configuredTickRate\n", StandardOpenOption.CREATE_NEW);
            emit("ADMISSION", Map.of("sample", sample, "seed", seed, "origin", origin.toShortString(),
                    "rotation", rotation.name(), "episode", episode, "originalT0", state.t0,
                    "mode", "READ_ONLY_OBSERVER_WITH_REAL_CLIENT", "automaticPass", false));
        }
        void tick() throws Exception {
            ServerLevel level = server.overworld(); long now = level.getGameTime();
            require(now >= state.lastObserved, "Game time moved backwards");
            require(state.lastObserved < 0 || now <= state.lastObserved + 1, "Game time skipped ticks; no hidden deadline extension");
            state.lastObserved = now;
            if (resume && resumeStarted < 0) resumeStarted = now;
            for (var id : List.copyOf(pendingNaturalJoins)) {
                var monster = level.getEntity(id);
                if (monster == null) continue;
                pendingNaturalJoins.remove(id); state.naturalMonsterJoins++;
                emit("NATURAL_MONSTER_PRESENT_AFTER_JOIN", Map.of("uuid", id.toString(),
                        "type", monster.getType().toString(), "position", monster.position().toString(),
                        "dayTime", level.getDayTime(), "loadedFromDisk", false));
            }
            var intents = NaturalVillageLifeData.existing(server);
            if (intents != null) {
                require(intents.villages().size() <= 4, "More than four natural registered regions");
                for (var row : intents.villages()) {
                    var b = NaturalVillageLifeData.decode(row);
                    if (!b.buildingOrigin().equals(origin)) continue;
                    require(b.templateId().equals(NaturalVillageLayout.FARM_V3.toString()) && b.rotation() == rotation,
                            "Fixed native selected farm differs; do not select another");
                    building = b;
                    var registered = SettlementRegistryData.get(server).building(b.buildingKey());
                    require(registered != null, "Initialization intent without original registration");
                    if (state.t0 < 0) {
                        state.t0 = registered.registeredGameTime();
                        emit("ORIGINAL_T0", Map.of("registered", state.t0, "firstObserved", now,
                                "building", b.toString(), "players", players()));
                        require(now - state.t0 <= 1, "Observer missed target registration prefix; no reset to Ready");
                        require(server.getPlayerList().getPlayerCount() == 1, "Real client must precede target registration");
                        openingEvidence();
                    }
                    require(state.t0 == registered.registeredGameTime(), "Original registration identity/time changed");
                    if (row.getBoolean("Ready") && state.readyAt < 0) {
                        state.readyAt = now; configurationEvidence(); emit("FIRST_READY", snapshot());
                    }
                }
            }
            if (state.t0 < 0) {
                require(now <= 6000 && System.nanoTime() - preparedAtNanos <= 600_000_000_000L,
                        "PRE_T0_CLIENT_OR_GENERATION_BLOCKED: 6000 ticks / 600 wall seconds; no sample passed");
                if (now % 200 == 0) emit("WAIT_REAL_CLIENT_AND_NATIVE_REGISTRATION", environment());
                return;
            }
            long elapsed = now - state.t0;
            var rules = level.getGameRules();
            require(level.getDifficulty() == net.minecraft.world.Difficulty.NORMAL
                    && rules.getInt(GameRules.RULE_RANDOMTICKING) == 3 && rules.getBoolean(GameRules.RULE_DAYLIGHT)
                    && rules.getBoolean(GameRules.RULE_WEATHER_CYCLE) && rules.getBoolean(GameRules.RULE_DOMOBSPAWNING),
                    "Frozen ordinary difficulty/daylight/weather/mob/random-tick conditions changed");
            if (state.readyAt >= 0) {
                observeExistingRemoteReceipts();
                boolean allOriginals = true;
                for (int i = 0; i < 3; i++) allOriginals &= level.getEntity(NaturalVillageLifeData.body(building, i)) instanceof NpcBase;
                if (resumeBoundary) {
                    if (!allOriginals || server.getPlayerList().getPlayerCount() != 1) {
                        require(now - resumeStarted <= 1200, "DECLARED_RESTART_REJOIN_1200_TICK_LIMIT; original T0 clock continues");
                        emit("DECLARED_RESTART_REJOIN_BOUNDARY", Map.of("waitTicks", now - resumeStarted,
                                "allOriginalsLoaded", allOriginals, "rawEconomy", nbt(NaturalVillageEconomyData.get(server).save(new CompoundTag(), server.registryAccess()))));
                        return;
                    }
                    resumeBoundary = false; emit("ORIGINALS_REJOINED_AFTER_DECLARED_PROCESS_RESTART", Map.of("waitTicks", now - resumeStarted));
                }
                require(allOriginals, "ORIGINAL_UNLOAD_BOUNDARY: formal near observation stopped; no remote time silently counted");
                require(server.getPlayerList().getPlayerCount() == 1, "REAL_PLAYER_LOADING_BOUNDARY: missing/extra player during near observation");
                observeActorsAndWork(); auditTransfers(); observeInventory(); observeObligations(); observeMemberPayment();
            }
            if (elapsed % 20 == 0) {
                var gates = loadingGates(); boolean all = (boolean) gates.get("allCropChunksEligible");
                if (all) state.eligibleGateSamples++; else state.ineligibleGateSamples++;
                // Gap is recorded explicitly. Resume loading is not fabricated into continuous NEAR.
                if (!all || elapsed % 200 == 0) emit("REAL_LOADING_GATES", gates);
                if (state.readyAt >= 0) observeCropAges();
                long[] times = server.getTickTimesNanos();
                long nanos = times[Math.floorMod(server.getTickCount(), times.length)];
                var runtime = Runtime.getRuntime();
                Files.writeString(metrics, now + "," + elapsed + "," + nanos + ","
                        + (runtime.totalMemory() - runtime.freeMemory()) + "," + runtime.maxMemory() + ","
                        + server.tickRateManager().tickrate() + "\n", StandardOpenOption.APPEND);
            }
            if (elapsed % 200 == 0) emit("STATE", snapshot());
            boolean cold = state.harvested && state.replanted && state.raw && state.carrying
                    && state.delivered && state.afterDeliveryMeals.size() == 3;
            if (cold && state.coldComplete < 0) {
                state.coldComplete = now; emit("FIRST_COMPLETE_CHAIN", Map.of("elapsedFromRegistration", elapsed,
                        "elapsedFromReady", now - state.readyAt, "counters", state));
            }
            if (elapsed >= 48000 && state.coldComplete < 0) throw new IllegalStateException("FIRST_CHAIN_T0_48000_DEADLINE");
            if (elapsed >= 48000 && state.stableNetCrop == Long.MIN_VALUE) {
                state.stableNetCrop = state.netCrop+state.remoteNet; state.stableMeals = Arrays.stream(state.meals).sum()+state.remoteMeals;
                state.stableVillageNetOut = state.villageCarrotNetOut; state.stablePlayerMeals = state.playerCarrotsConsumed;
                emit("STABLE_PERIOD_START", snapshot());
            }
            if (sample == 2 && !resume && elapsed >= 120000) stop("FROZEN_RESTART_BOUNDARY");
            else if (elapsed >= 240000) {
                finalAudit = finalAudit(); emit("FINAL_ASSERTIONS", finalAudit);
                String outcome = (String) finalAudit.get("status");
                stop(outcome.equals("FAILED") ? "FINAL_AUDIT_FAILED" : outcome.equals("REQUIRES_REVIEW")
                        ? "OBSERVATION_WINDOW_COMPLETE_REQUIRES_REVIEW" : "OBSERVATION_WINDOW_COMPLETE_REQUIRES_AUDIT");
            }
        }
        void observeActorsAndWork() throws Exception {
            var level = server.overworld(); long now = level.getGameTime();
            var life = NpcLifeData.get(server);
            var characters = level.getDataStorage().get(CharacterRegistryData.FACTORY, CharacterRegistryData.DATA_NAME);
            for (int i = 0; i < 3; i++) {
                UUID body = NaturalVillageLifeData.body(building, i), person = NaturalVillageLifeData.person(building, i);
                var character = characters == null ? null : characters.byId(person);
                require(character != null && character.alive(), "Original authoritative character missing/dead: " + i
                        + (character == null ? "" : ", death=" + character.deathGameTime() + ", cause=" + character.deathCause()));
                var entity = level.getEntity(body);
                if (!(entity instanceof NpcBase npc)) {
                    if (now % 20 == 0) emit("ORIGINAL_ENTITY_NOT_LOADED", Map.of("index", i, "body", body.toString(), "person", person.toString()));
                    continue; // Read raw authority in snapshot; unloading is never rewritten to death.
                }
                require(npc.isAlive() && npc.getCharacterId().equals(person), "Original person died or changed identity: " + i);
                var need = life.needs(person);
                require(need != null, "Original life needs must already exist");
                if (need.hunger() >= 800) {
                    if (state.severeSince[i] < 0) state.severeSince[i] = now;
                    require(now-state.severeSince[i] < 2400, "Continuous severe hunger reached frozen2400 ticks, original="+i);
                } else state.severeSince[i] = -1;
                if (need != null && need.lastAteAt() >= 0 && need.lastAteAt() != state.lastMeal[i]) {
                    state.lastMeal[i] = need.lastAteAt();
                    if(need.lastFoodSource().equals("远方登记仓储的真实胡萝卜")) {
                        emit("REMOTE_MEAL_NOT_DOUBLE_COUNTED_AS_NEAR",Map.of("person",person.toString(),"at",need.lastAteAt()));
                    } else state.meals[i]++;
                    if (state.firstDelivery >= 0 && need.lastAteAt() > state.firstDelivery) state.afterDeliveryMeals.add(i);
                    emit("ACTUAL_MEAL", Map.of("index", i, "person", person.toString(), "body", body.toString(),
                            "at", need.lastAteAt(), "source", need.lastFoodSource(), "hunger", need.hunger()));
                }
                var schedule = life.schedule(person);
                if (schedule != null) {
                    String activity = schedule.activity().name();
                    if (Set.of("WORK", "REST").contains(activity) && !activity.equals(state.lastWorkRest[i])) {
                        if (!state.lastWorkRest[i].isEmpty()) state.workRest[i]++;
                        state.lastWorkRest[i] = activity;
                        emit("WORK_REST_SWITCH", Map.of("index", i, "activity", activity, "count", state.workRest[i]));
                    }
                }
            }
            var work = server.overworld().getDataStorage().get(FieldworkData.FACTORY,FieldworkData.DATA_NAME);
            if(work==null)return; // Observation must not create an empty production ledger before ordinary work does.
            for(int i=0;i<3;i++) for(var receipt:work.receiptsFor(NaturalVillageLifeData.body(building,i))) {
                if(!state.itemActions.add(receipt.action().key())) continue;
                for(var delta:receipt.inventoryDeltas()) state.itemNet.merge(delta.fingerprint().split("#",2)[0],(long)delta.quantity(),Long::sum);
                emit("ALL_ORIGINAL_WORK_MATERIALS",Map.of("index",i,"receipt",receipt.toString()));
            }
            for (var receipt : work.receiptsFor(NaturalVillageLifeData.body(building, 0))) {
                if (!state.receipts.add(receipt.action().key())) continue;
                String plot = receipt.action().target().toShortString(), operation = receipt.action().operation().name();
                long delta = receipt.inventoryDeltas().stream().filter(d -> "minecraft:carrot#0".equals(d.fingerprint()))
                        .mapToLong(d -> d.quantity()).sum();
                state.netCrop += delta;
                if (operation.equals("HARVEST")) {
                    state.harvested = true; state.harvests.merge(plot, 1, Integer::sum);
                    state.pendingHarvest.put(plot, receipt.committedAt());
                } else if (operation.equals("PLANT")) {
                    state.replanted = true;
                    if (state.pendingHarvest.remove(plot) != null) state.replants.merge(plot, 1, Integer::sum);
                }
                emit("FIELDWORK_RECEIPT", Map.of("key", receipt.action().key(), "plot", plot, "operation", operation,
                        "committedAt", receipt.committedAt(), "carrotDelta", delta, "receipt", receipt.toString()));
            }
            for (var receipt : work.outputConsumptions(NaturalVillageLifeData.body(building, 0))) {
                String observation = receipt.key() + "/" + receipt.state();
                if (state.outputMeals.add(observation)) emit("SELF_OUTPUT_CONSUMPTION", Map.of("receipt", receipt.toString(),
                        "lifeSettlement", String.valueOf(LifeActivityData.get(server).settlement(receipt.key())),
                        "note", "Already one of ACTUAL_MEAL, not additional total village consumption"));
            }
            if (countStore(NaturalVillageLayout.RAW) > 0 && !state.raw) {
                state.raw = true; emit("RAW_PHYSICAL_STOCK", Map.of("carrots", countStore(NaturalVillageLayout.RAW)));
            }
            for (var route : SupplyFlowData.get(server).routes()) {
                if (!route.principal().equals(SubjectRef.household(NaturalVillageLifeData.home(building)))) continue;
                String key = route.id() + "/" + route.stage();
                if (state.routeStages.add(key)) emit("ROUTE_STAGE", Map.of("route", route.toString()));
                var carrier = route.carrierEntity() == null ? null : level.getEntity(route.carrierEntity());
                if (route.stage() == SupplyFlowData.Stage.CARRYING && validFoodRoute(route) && carrier instanceof NpcBase npc && count(npc.getTransportInventory()) == route.quantity()) {
                    state.realCarryingRoutes.add(route.id().toString());
                    if (!state.carrying) emit("CARRYING_PHYSICAL", Map.of("route", route.toString(), "body", npc.getUUID().toString(),
                            "position", npc.position().toString(), "carrots", count(npc.getTransportInventory())));
                    if (!state.carrying) state.firstCarryingRoute = route.id().toString();
                    state.carrying = true;
                }
            }
        }

        UUID storeId(String type,BlockPos relative) { return ContainerIds.block(type,building.dimension(),NaturalVillageLayout.at(building,relative)); }
        boolean validFoodRoute(SupplyFlowData.Route route) {
            return Objects.equals(route.carrierEntity(),NaturalVillageLifeData.body(building,1))
                    &&Objects.equals(route.carrierCharacter(),NaturalVillageLifeData.person(building,1))
                    &&route.principal().equals(SubjectRef.household(NaturalVillageLifeData.home(building)))
                    &&route.source().equals(storeId("private_storage",NaturalVillageLayout.RAW))
                    &&route.destination().equals(storeId("household_storage",NaturalVillageLayout.PANTRY));
        }
        void openingEvidence() throws Exception {
            require(!state.openingCaptured,"Only one original opening boundary");
            state.openingCaptured=true;
            emit("T0_LOADED_PHYSICAL_OBSERVATION",physical(true));
            observeCropAges();
            emit("T0_INITIAL_SOURCE_RULE",Map.of("templateCarrots",4,"laterOriginalFarmerSeeds",12,
                    "source","frozen farm_v3 template plus formal NaturalVillageLifeData initialization; actual objects reconciled at Ready before any work",
                    "noAgeReset",true,"noForcedChunkLoads",true));
        }
        void configurationEvidence() throws Exception {
            require(state.openingCaptured&&!state.configurationCaptured,"One configuration boundary");
            var work=server.overworld().getDataStorage().get(FieldworkData.FACTORY,FieldworkData.DATA_NAME);
            for(int i=0;i<3;i++) {
                var n=(NpcBase)server.overworld().getEntity(NaturalVillageLifeData.body(building,i));
                require(n!=null&&n.getCharacterId().equals(NaturalVillageLifeData.person(building,i)),"Formal originals at Ready");
                require(work==null||work.receiptsFor(n.getUUID()).isEmpty(),"Pre-Ready work requires separate opening reconciliation");
                var need=NpcLifeData.get(server).needs(n.getCharacterId());
                require(need!=null&&need.lastAteAt()<0,"No unobserved meal before opening reconciliation");
                if(i==0) require(count(n.getPersonalInventory())==12,"Formal finite farmer seed allocation12");
                for(int slot=0;slot<n.getPersonalInventory().getContainerSize();slot++) addItem(state.configurationItems,n.getPersonalInventory().getItem(slot));
            }
            require(countStore(NaturalVillageLayout.PANTRY)==4,"Original template PANTRY4, never replenished by observer");
            var total=physical(false); var items=castItems(total);
            require(items.getOrDefault("minecraft:carrot",0L)==16,"Exactly16 initial mobile carrots including real initial configuration");
            state.openingItems.putAll(items);
            state.configurationItems.forEach((item,q)->state.openingItems.merge(item,-q,Long::sum));
            require(state.openingItems.values().stream().allMatch(v->v>=0),"Opening plus formal configuration cannot be negative");
            state.configurationCaptured=true; state.lastItems.putAll(items);
            emit("READY_RECONCILED_INITIAL_STOCK",Map.of("openingSources",state.openingItems,"formalActorConfiguration",state.configurationItems,
                    "physical",total,"basis","Actual original objects, no production or consumption before this boundary; not rewritten T0"));
            for(var relative:NaturalVillageLayout.stores(building)) for(String type:List.of("private_storage","household_storage","natural_public"))
                state.knownVillageContainers.add(storeId(type,relative).toString());
            for(int i=0;i<3;i++) {
                state.knownVillageContainers.add(ContainerIds.npcPersonal(NaturalVillageLifeData.person(building,i)).toString());
                state.knownVillageContainers.add(ContainerIds.npcCargo(NaturalVillageLifeData.person(building,i)).toString());
            }
        }
        @SuppressWarnings("unchecked") Map<String,Long> castItems(Map<String,Object> snapshot) {return (Map<String,Long>)snapshot.get("items");}
        void addItem(Map<String,Long> counts,ItemStack stack) {
            if(!stack.isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),(long)stack.getCount(),Long::sum);
        }
        void collectContainer(Map<String,Long> counts,Map<String,Object> locations,String label,Container c) {
            locations.put(label,nbt(NaturalVillageEconomyData.items(c,server.registryAccess())));
            for(int slot=0;slot<c.getContainerSize();slot++) addItem(counts,c.getItem(slot));
        }
        Map<String,Object> physical(boolean partial) {
            var l=server.overworld(); Map<String,Long> counts=new TreeMap<>(); Map<String,Object> locations=new TreeMap<>();
            for(var local:NaturalVillageLayout.stores(building)) {
                var at=NaturalVillageLayout.at(building,local); var chunk=l.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4);
                if(chunk!=null&&chunk.getBlockEntity(at) instanceof Container c) collectContainer(counts,locations,"store:"+at.toShortString(),c);
                else {locations.put("store:"+at.toShortString(),"NOT_LOADED_OR_MISSING"); if(!partial)require(false,"Original storage missing at inventory boundary "+at);}
            }
            for(int i=0;i<3;i++) {
                var entity=l.getEntity(NaturalVillageLifeData.body(building,i));
                if(!(entity instanceof NpcBase n)){locations.put("npc:"+i,"NOT_YET_PRESENT");continue;}
                collectContainer(counts,locations,"bag:"+n.getUUID(),n.getPersonalInventory());
                collectContainer(counts,locations,"cargo:"+n.getUUID(),n.getTransportInventory());
                for(var slot:EquipmentSlot.values()) {var item=n.getItemBySlot(slot);addItem(counts,item);locations.put("equipment:"+i+":"+slot,item.isEmpty()?"EMPTY":nbt(item.save(server.registryAccess())));}
                int index=0;for(var item:n.getInventoryMigrationOverflowSnapshot()){addItem(counts,item);locations.put("overflow:"+i+":"+(index++),nbt(item.save(server.registryAccess())));}
            }
            for(var p:server.getPlayerList().getPlayers()) collectContainer(counts,locations,"player:"+p.getUUID(),p.getInventory());
            for(var drop:l.getEntitiesOfClass(ItemEntity.class,new AABB(origin).inflate(128))) {
                String item=BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()).toString();
                // Only goods in this bounded economy. Other ambient mob loot is recorded separately.
                boolean relevant=item.equals("minecraft:carrot")||item.equals("minecraft:oak_log")||item.equals("minecraft:iron_axe")
                        ||state.openingItems.containsKey(item)||state.itemNet.containsKey(item);
                locations.put("drop:"+drop.getUUID(),Map.of("position",drop.position().toString(),"item",nbt(drop.getItem().save(server.registryAccess())),"included",relevant));
                if(relevant)addItem(counts,drop.getItem());
            }
            return Map.of("items",counts,"locations",locations,"scope","original stores, original3 all physical slots, actual player, relevant nearby dropped goods; no reservation or transit double count");
        }
        void observeInventory() throws Exception {
            var observed=physical(false);var items=castItems(observed);
            @SuppressWarnings("unchecked")var locations=(Map<String,Object>)observed.get("locations");
            Map<String,String> changed=new TreeMap<>();
            for(var entry:locations.entrySet()){String value=JSON.toJson(entry.getValue());if(!Objects.equals(state.lastLocations.get(entry.getKey()),value))changed.put(entry.getKey(),value);}
            for(var key:state.lastLocations.keySet())if(!locations.containsKey(key))changed.put(key,"REMOVED_FROM_OBSERVED_LOCATION");
            if(!changed.isEmpty())emit("PHYSICAL_LOCATION_DELTAS",Map.of("changes",changed,"total",items));
            state.lastLocations.clear();locations.forEach((key,value)->state.lastLocations.put(key,JSON.toJson(value)));
            state.lastItems=new TreeMap<>(items);
            long expected=16+state.netCrop+state.remoteNet-Arrays.stream(state.meals).sum()-state.remoteMeals-state.playerCarrotsConsumed;
            require(items.getOrDefault("minecraft:carrot",0L)==expected,"CARROT_BALANCE_UNEXPLAINED: actual="+items.getOrDefault("minecraft:carrot",0L)+", expected="+expected);
        }
        void auditTransfers() throws Exception {
            var ledger=server.overworld().getDataStorage().get(TransferLedgerData.FACTORY,TransferLedgerData.DATA_NAME);
            if(ledger==null)return;
            var encoded=ledger.save(new CompoundTag(),server.registryAccess());
            for(var raw:encoded.getList("Receipts",Tag.TAG_COMPOUND)){
                var row=(CompoundTag)raw;String id=row.getUUID("Id").toString();
                if(!state.transferIds.add(id))continue;
                state.transferProofs.put(id,row.toString()); emit("TRANSFER_WITH_ORIGINAL_PAYLOAD",nbt(row));
                if(!row.getString("Status").equals("SUCCESS"))continue;
                require(row.getBoolean("Lawful"),"Successful transfer must retain lawful source rights");
                var identity=row.getCompound("RequestIdentity");
                require(identity.contains("Legs"),"Successful new transfer requires original immutable legs");
                for(var v:identity.getList("Legs",Tag.TAG_COMPOUND)){
                    var leg=(CompoundTag)v;String from=leg.getUUID("Source").toString(),to=leg.getUUID("Destination").toString();
                    boolean insideFrom=state.knownVillageContainers.contains(from),insideTo=state.knownVillageContainers.contains(to);
                    String item=com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint.load(leg.getCompound("Item")).itemId();
                    if(insideFrom!=insideTo&&item.equals("minecraft:carrot")){
                        state.villageCarrotNetOut+=(insideFrom?1:-1)*leg.getInt("Quantity");
                        require(insideFrom,"Outside food cannot sustain either natural sample");
                    }
                }
            }
            var existingNeeds=server.overworld().getDataStorage().get(NeedRequestData.FACTORY,NeedRequestData.NAME);
            if(existingNeeds==null)return;
            var needData=existingNeeds.save(new CompoundTag(),server.registryAccess());
            for(var route:SupplyFlowData.get(server).routes()){
                if(route.stage()!=SupplyFlowData.Stage.DONE||!validFoodRoute(route)||state.routeDelivered.containsKey(route.id().toString()))continue;
                var need=NeedRequestData.get(server).request(route.need());
                require(need!=null&&need.key().item().itemId().equals("minecraft:carrot"),"Delivered route retains original carrot need");
                int total=0;
                for(var raw:needData.getList("Receipts",Tag.TAG_COMPOUND)){
                    var row=(CompoundTag)raw;if(!row.getUUID("Request").equals(route.need())||!row.getString("Action").equals("DELIVER"))continue;
                    var receipt=ledger.receipt(row.getUUID("Id"));if(receipt==null)continue;
                    String proof=state.transferProofs.get(row.getUUID("Id").toString());
                    if(proof==null)continue;
                    var saved=net.minecraft.nbt.TagParser.parseTag(proof);var identity=saved.getCompound("RequestIdentity");
                    if(!identity.getString("Authority").equals(route.token()))continue;
                    require(receipt.successful()&&receipt.lawful(),"Actual delivered need has successful lawful atomic transfer");
                    var legs=identity.getList("Legs",Tag.TAG_COMPOUND);require(legs.size()==1,"One original supply delivery leg");
                    var leg=legs.getCompound(0);
                    require(leg.getUUID("Destination").equals(route.destination())&&leg.getInt("Quantity")==row.getInt("Quantity"),"Original destination and quantity match need receipt");
                    total+=row.getInt("Quantity");
                }
                var order=LogisticsData.get(server).order(route.id());
                require(total==route.quantity()&&order!=null&&order.pickedUpCount()==total&&order.deliveredCount()==total,"Supply route, actual transfer and logistics quantities match");
                state.routeDelivered.put(route.id().toString(),(long)total);
                emit("ORIGINAL_ROUTE_REAL_DELIVERY_PROVEN",Map.of("route",route.toString(),"quantity",total));
                if(state.realCarryingRoutes.contains(route.id().toString())&&!state.delivered){state.delivered=true;state.firstDelivery=server.overworld().getGameTime();}
            }
        }
        void observeObligations() throws Exception {
            long now=server.overworld().getGameTime();var data=server.overworld().getDataStorage().get(NeedRequestData.FACTORY,NeedRequestData.NAME);
            if(data==null)return;
            List<Object> rows=new ArrayList<>();
            for(var need:data.requests()){
                state.firstNeed.putIfAbsent(need.id().toString(),need.createdAt());
                if(need.status().terminal()||need.status()==com.jgeted.sagadyssey.social.needs.NeedRequestStatus.DELIVERED)continue;
                require(need.createdAt()==state.firstNeed.get(need.id().toString()),"Request origin age cannot reset");
                rows.add(Map.of("need",need.toString(),"age",now-need.createdAt(),"responsible",need.key().requester().toString()));
            }
            for(var reservation:RightsStateData.get(server).reservations()) rows.add(Map.of("reservation",reservation.toString(),"age",now-reservation.createdAt()));
            if(now-state.t0>=240000||now%24000==0) emit("UNFINISHED_OBLIGATIONS",rows);
            for(var route:SupplyFlowData.get(server).routes())if(route.stage()==SupplyFlowData.Stage.REVIEW)state.reviewReasons.add("route REVIEW "+route.id()+": "+route.detail());
        }
        void observeMemberPayment() throws Exception {
            if(sample!=2)return;
            for(var player:server.getPlayerList().getPlayers()){
                var person=CharacterRegistry.currentId(player);if(person==null)continue;
                var payment=NaturalVillageLifeData.get(server).payment(person,building.settlementId());
                if(payment==null||!payment.getString("State").equals("PAID"))continue;
                String transaction=payment.getUUID("Transaction").toString();
                if(state.memberTransaction.isEmpty()){
                    var receipt=TransferLedgerData.get(server).receipt(payment.getUUID("Transaction"));
                    require(receipt!=null&&receipt.successful()&&receipt.lawful(),"Normal member payment is backed by a real lawful transfer");
                    require(count(player.getInventory())==2,"Original member actually received two carrots");
                    state.memberAccount=player.getUUID().toString();state.memberPerson=person.toString();state.memberTransaction=transaction;
                    state.memberPaidAt=server.overworld().getGameTime();state.playerPaidCarrots=2;state.playerDeliveredOak=4;
                    emit("ACTUAL_MEMBER_PAYMENT",Map.of("person",person.toString(),"account",player.getUUID().toString(),"payment",nbt(payment),"player",players(),"receipt",receipt.toString()));
                }else require(transaction.equals(state.memberTransaction),"No second reward or new task is allowed in this single transaction sample");
            }
        }
        void observeExistingRemoteReceipts() throws Exception {
            var data=NaturalVillageEconomyData.existing(server);if(data==null)return;
            var region=data.row(building.buildingKey());if(region==null)return;
            require(region.getString("Phase").equals("NEAR")||resume,"Only the declared full-restart gap may use existing remote authority");
            int index=0;
            for(var raw:region.getList("Receipts",Tag.TAG_COMPOUND)){
                var receipt=(CompoundTag)raw;String key=region.getLong("Epoch")+":"+(index++);
                if(!state.remoteReceiptKeys.add(key))continue;
                String kind=receipt.getString("Kind");
                if(kind.equals("HARVEST_REPLANT")||kind.equals("PLANT"))state.remoteNet+=receipt.getInt("Output")-receipt.getInt("Input");
                if(kind.equals("MEAL")){require(receipt.getInt("Input")==1,"One actual remote carrot meal");state.remoteMeals++;}
                emit("EXISTING_REMOTE_RESTART_GAP_RECEIPT",Map.of("epoch",region.getLong("Epoch"),"phase",region.getString("Phase"),"receipt",nbt(receipt)));
            }
        }
        Map<String,Object> finalAudit() {
            List<String> failures=new ArrayList<>(),review=new ArrayList<>(state.reviewReasons);
            for(int i=0;i<3;i++){if(state.meals[i]<3)failures.add("original"+i+" meals<3");if(state.workRest[i]<2)failures.add("original"+i+" work/rest switches<2");}
            for(var relative:NaturalVillageLayout.crops()){String plot=NaturalVillageLayout.at(building,relative).toShortString();if(state.replants.getOrDefault(plot,0)<3)failures.add("plot "+plot+" paired harvest/replant<3");}
            long stableProduction=state.netCrop+state.remoteNet-state.stableNetCrop,stableConsumption=Arrays.stream(state.meals).sum()+state.remoteMeals-state.stableMeals;
            long stableOut=state.villageCarrotNetOut-state.stableVillageNetOut;
            if(stableProduction<stableConsumption+stableOut)failures.add("Stable production does not cover resident consumption and net outward flow");
            if(sample==2&&(state.memberTransaction.isEmpty()||!resume))failures.add("Real member payment or full restart missing");
            // Aggregate non-food units are audited independently; tool components/damage remain in slot evidence.
            var keys=new LinkedHashSet<>(state.lastItems.keySet());keys.addAll(state.openingItems.keySet());keys.addAll(state.configurationItems.keySet());keys.addAll(state.itemNet.keySet());
            Map<String,Object> balances=new TreeMap<>();
            for(String item:keys){long expected=state.openingItems.getOrDefault(item,0L)+state.configurationItems.getOrDefault(item,0L)+state.itemNet.getOrDefault(item,0L);
                if(item.equals("minecraft:carrot"))expected+=state.remoteNet-Arrays.stream(state.meals).sum()-state.remoteMeals-state.playerCarrotsConsumed;
                long actual=state.lastItems.getOrDefault(item,0L);balances.put(item,Map.of("expected",expected,"actual",actual,"difference",actual-expected));
                if(expected!=actual)failures.add("unexplained item difference "+item+"="+(actual-expected));}
            if(!state.pendingHarvest.isEmpty())review.add("End-of-window subsequent replant obligations need age/seed/route audit: "+state.pendingHarvest);
            review.add("Final permission, outstanding-request executable age and command-replay evidence must be reviewed against raw event records; no automatic full acceptance");
            return Map.of("status",!failures.isEmpty()?"FAILED":!review.isEmpty()?"REQUIRES_REVIEW":"PASSED_ASSERTIONS", "failures",failures,"review",review,
                    "itemBalances",balances,"stableProduction",stableProduction,"stableConsumption",stableConsumption,"stableNetOut",stableOut,
                    "originalMeals",state.meals,"perPlotPaired",state.replants,"memberTransaction",state.memberTransaction);
        }
        void observeCropAges() throws Exception {
            var level = server.overworld();
            for (var relative : NaturalVillageLayout.crops()) {
                var at = NaturalVillageLayout.at(building, relative);
                var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
                if (chunk == null) continue;
                var actual = chunk.getBlockState(at);
                int age = actual.is(Blocks.CARROTS) ? actual.getValue(CropBlock.AGE) : -1;
                Integer previous = state.crops.put(at.toShortString(), age);
                if (previous == null || previous != age) emit("ACTUAL_CROP_AGE", Map.of("plot", at.toShortString(),
                        "previous", previous == null ? -2 : previous, "age", age, "actualState", actual.toString()));
            }
        }
        Map<String, Object> loadingGates() {
            var level = server.overworld(); var source = level.getChunkSource();
            List<Object> chunks = new ArrayList<>(); Set<ChunkPos> seen = new LinkedHashSet<>(); boolean all = true;
            for (var relative : NaturalVillageLayout.crops()) {
                var at = NaturalVillageLayout.at(building, relative); var cp = new ChunkPos(at);
                if (!seen.add(cp)) continue;
                var players = source.chunkMap.getPlayersCloseForSpawning(cp);
                boolean loaded = source.getChunkNow(cp.x, cp.z) != null;
                boolean ticking = source.isPositionTicking(cp.toLong());
                boolean natural = level.isNaturalSpawningAllowed(cp);
                boolean playerGate = players.stream().anyMatch(p -> !p.isSpectator() && p.connection != null);
                all &= loaded && ticking && natural && playerGate && server.tickRateManager().runsNormally();
                chunks.add(Map.of("chunk", cp.toString(), "loaded", loaded, "blockTicking", ticking,
                        "naturalSpawnAllowed", natural, "realTrackedPlayers", players.stream().map(p -> p.getUUID().toString()).toList(),
                        "playerGate", playerGate));
            }
            var result = new LinkedHashMap<String, Object>(); result.put("allCropChunksEligible", all);
            result.put("cropChunks", chunks); result.put("players", players()); result.put("forceTicksByObserver", false);
            return result;
        }
        List<Object> players() {
            List<Object> out = new ArrayList<>();
            for (var p : server.getPlayerList().getPlayers()) out.add(Map.of("account", p.getUUID().toString(),
                    "position", p.position().toString(), "spectator", p.isSpectator(), "creative", p.isCreative(),
                    "mode", p.gameMode.getGameModeForPlayer().getName(), "carrots", count(p.getInventory()),
                    "inventory", nbt(NaturalVillageEconomyData.items(p.getInventory(), server.registryAccess())),
                    "invulnerableAbility", p.getAbilities().invulnerable, "instabuild", p.getAbilities().instabuild,
                    "flying", p.getAbilities().flying));
            return out;
        }
        Map<String, Object> environment() {
            var l = server.overworld(); var r = l.getGameRules();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("gameTime", l.getGameTime()); result.put("dayTime", l.getDayTime()); result.put("difficulty", l.getDifficulty().name());
            result.put("randomTickSpeed", r.getInt(GameRules.RULE_RANDOMTICKING)); result.put("daylight", r.getBoolean(GameRules.RULE_DAYLIGHT));
            result.put("weatherCycle", r.getBoolean(GameRules.RULE_WEATHER_CYCLE)); result.put("mobSpawning", r.getBoolean(GameRules.RULE_DOMOBSPAWNING));
            result.put("raining", l.isRaining()); result.put("thundering", l.isThundering()); result.put("tickRate", server.tickRateManager().tickrate());
            result.put("players", players()); return result;
        }
        Map<String, Object> snapshot() {
            state.snapshots++;
            var out = new LinkedHashMap<String, Object>(); out.put("counters", state); out.put("environment", environment());
            var natural = NaturalVillageLifeData.existing(server); var economy = NaturalVillageEconomyData.existing(server);
            out.put("naturalLife", natural == null ? null : nbt(natural.save(new CompoundTag(), server.registryAccess())));
            out.put("naturalEconomy", economy == null ? null : nbt(economy.save(new CompoundTag(), server.registryAccess())));
            if (building == null || state.readyAt < 0) return out;
            out.put("buildings", nbt(SettlementRegistryData.get(server).save(new CompoundTag(), server.registryAccess())));
            out.put("households", nbt(HouseholdData.get(server).save(new CompoundTag(), server.registryAccess())));
            out.put("life", nbt(NpcLifeData.get(server).save(new CompoundTag(), server.registryAccess())));
            var data = server.overworld().getDataStorage();
            var rights = data.get(RightsStateData.FACTORY, RightsStateData.DATA_NAME);
            var chars = data.get(CharacterRegistryData.FACTORY, CharacterRegistryData.DATA_NAME);
            var brain = data.get(NpcBrainData.FACTORY, NpcBrainData.DATA_NAME);
            var needs = data.get(NeedRequestData.FACTORY, NeedRequestData.NAME);
            var transfers = data.get(TransferLedgerData.FACTORY, TransferLedgerData.DATA_NAME);
            out.put("rightsReservations", rights == null ? null : nbt(rights.save(new CompoundTag(), server.registryAccess())));
            out.put("characters", chars == null ? null : nbt(chars.save(new CompoundTag(), server.registryAccess())));
            out.put("plans", brain == null ? null : nbt(brain.save(new CompoundTag(), server.registryAccess())));
            out.put("needsAndReceipts", needs == null ? null : nbt(needs.save(new CompoundTag(), server.registryAccess())));
            out.put("atomicTransfers", transfers == null ? null : nbt(transfers.save(new CompoundTag(), server.registryAccess())));
            var work=data.get(FieldworkData.FACTORY,FieldworkData.DATA_NAME);
            var activities=data.get(LifeActivityData.FACTORY,LifeActivityData.NAME);
            out.put("fieldworkAndSchema2", work==null?null:nbt(work.save(new CompoundTag(), server.registryAccess())));
            out.put("lifeSettlements", activities==null?null:nbt(activities.save(new CompoundTag(), server.registryAccess())));
            out.put("supply", nbt(SupplyFlowData.get(server).save(new CompoundTag(), server.registryAccess())));
            // A formal supply route is published only after the existing logistics order exists.
            out.put("logistics", SupplyFlowData.get(server).routes().isEmpty() ? null
                    : nbt(LogisticsData.get(server).save(new CompoundTag(), server.registryAccess())));
            var l = server.overworld(); List<Object> stores = new ArrayList<>(), actors = new ArrayList<>();
            int stock = 0;
            for (var relative : NaturalVillageLayout.stores(building)) {
                var at = NaturalVillageLayout.at(building, relative);
                var chunk = l.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
                if (chunk != null && chunk.getBlockEntity(at) instanceof Container c) {
                    stock += count(c); stores.add(Map.of("position", at.toShortString(), "items", nbt(NaturalVillageEconomyData.items(c, server.registryAccess()))));
                } else stores.add(Map.of("position", at.toShortString(), "loadedContainer", false));
            }
            for (int i = 0; i < 3; i++) {
                var e = l.getEntity(NaturalVillageLifeData.body(building, i));
                if (e instanceof NpcBase npc) {
                    stock += count(npc.getPersonalInventory()) + count(npc.getTransportInventory());
                    actors.add(Map.of("index", i, "body", npc.getUUID().toString(), "person", npc.getCharacterId().toString(),
                            "entityNbt", nbt(npc.saveWithoutId(new CompoundTag()))));
                } else actors.add(Map.of("index", i, "loaded", false));
            }
            out.put("stores", stores); out.put("actors", actors); out.put("residentCarrotsPhysicalLoadedOnly", stock);
            out.put("nearbyNpcIds", l.getEntitiesOfClass(NpcBase.class, new AABB(origin).inflate(128))
                    .stream().map(n -> n.getUUID() + "/" + n.getCharacterId()).toList());
            out.put("accountingWarning", "Physical loaded stock excludes untracked boundary flows and drops; raw ledger audit still required; reservations/cargo labels are not extra items");
            return out;
        }
        int countStore(BlockPos relative) {
            var at = NaturalVillageLayout.at(building, relative);
            var chunk = server.overworld().getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
            return chunk != null && chunk.getBlockEntity(at) instanceof Container c ? count(c) : 0;
        }
        void emit(String name, Object details) throws Exception {
            var row = new LinkedHashMap<String, Object>(); row.put("utc", Instant.now().toString());
            row.put("gameTime", server.overworld() == null ? -1 : server.overworld().getGameTime());
            row.put("originalT0", state.t0); row.put("episode", episode); row.put("event", name); row.put("detail", details);
            Files.writeString(events, JSON.toJson(row) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
        void stop(String why) throws Exception {
            if (stopping) return; reason = why; emit("REQUEST_NORMAL_STOP", Map.of("reason", why, "state", snapshot()));
            stopping = true; server.halt(false);
        }
        void failure(Exception error) {
            if (stopping) return;
            try { emit("OBSERVATION_FAILED", Map.of("error", error.toString(), "snapshot", snapshot())); }
            catch (Exception ignored) { error.printStackTrace(); }
            reason = "FAILED_OR_BLOCKED: " + error; stopping = true; server.halt(false);
        }
    }
    private static int count(Container c) { int total = 0; for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.CARROT)) total += c.getItem(i).getCount(); return total; }
    private static Object nbt(net.minecraft.nbt.Tag tag) { return NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag); }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
}
