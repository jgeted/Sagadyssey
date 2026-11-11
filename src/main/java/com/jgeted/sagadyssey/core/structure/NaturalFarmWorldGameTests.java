package com.jgeted.sagadyssey.core.structure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Ordinary dedicated-server PRECHECK only; never a GameTestServer fixture.
 *
 * Opt in with:
 *   -Dsagadyssey.naturalFarm.world=PRECHECK
 *   -Dsagadyssey.naturalFarm.sample=1                 (then 2 in a new process)
 *   -Dsagadyssey.naturalFarm.evidenceRoot=<absolute W/run/natural-world-precheck>
 * Set level-name so getWorldPath(ROOT) is <root>/seed-7019/world or seed-7020/world.
 * Both old discovery properties must be false. The launcher must also record that the
 * target world directory was absent BEFORE Java was launched; Main writes level.dat
 * before ServerAboutToStartEvent, so checking its absence here would be incorrect.
 *
 * Original PRECHECK keeps its archived 7019/7020 batch unchanged.
 * PRECHECK_DISCOVERY_R2 uses discoveryCandidate=1 (7021), then 2 (7022) only when
 * the previous candidate ended NOT_FOUND without selecting any farm. Its separate
 * evidenceRoot must end in natural-world-precheck-discovery-r2. This is bounded
 * candidate discovery, never a third formal long sample; no new seed after selection.
 * Seeds, search order, radius, world paths and a single attempt per seed are frozen.
 * Only native STRUCTURE_STARTS are requested while searching. Once the nearest actual
 * farm_v3 start is found, only its real piece union plus one chunk is requested FULL.
 * Native dependency generation and the normal spawn area are explicitly accounted for.
 * No structure placement, schedule/init call, inventory, rights, time, weather, rule,
 * player, entity, crop-Age or authority mutation is performed by this observer.
 * Chunk force tickets are infrastructure only; no player/forceTicks is synthesized.
 *
 * 1200 server ticks includes candidate selection and Ready waiting, not just after Ready.
 * T0 is BuildingRecord.registeredGameTime. Observation time is separately recorded.
 * PRECHECK_READY means location/registration evidence only; long/day-night, production,
 * real-player participation, survival and restart acceptance remain NOT_RUN.
 *
 * The existing jar exclusion ** / *GameTests*.class (without spaces) also excludes all
 * nested classes. Keep that packaging check when integrating. Do not ship this class.
 */
@EventBusSubscriber(modid = "sagadyssey")
public final class NaturalFarmWorldGameTests {
    private static final String MODE_PROPERTY = "sagadyssey.naturalFarm.world";
    private static final String SAMPLE_PROPERTY = "sagadyssey.naturalFarm.sample";
    private static final String ROOT_PROPERTY = "sagadyssey.naturalFarm.evidenceRoot";
    private static final long[] SEEDS = {7019L, 7020L};
    private static final String DISCOVERY_MODE = "PRECHECK_DISCOVERY_R2";
    private static final String DISCOVERY_PROPERTY = "sagadyssey.naturalFarm.discoveryCandidate";
    private static final long[] DISCOVERY_SEEDS = {7021L, 7022L};
    private static final int RADIUS_CHUNKS = 96;
    private static final int DEADLINE_TICKS = 1200;
    private static final int MAX_REGISTERED_REGIONS = 4;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Comparator<ChunkPos> NEAREST = Comparator
            .comparingLong(NaturalFarmWorldGameTests::distanceSquared)
            .thenComparingInt(p -> p.x).thenComparingInt(p -> p.z);
    private static Run run;

    private NaturalFarmWorldGameTests() {}

    private static boolean enabled() {
        String mode = System.getProperty(MODE_PROPERTY, "");
        return "PRECHECK".equals(mode) || DISCOVERY_MODE.equals(mode);
    }

    @SubscribeEvent
    public static void aboutToStart(ServerAboutToStartEvent event) {
        if (!enabled()) return;
        run = new Run(event.getServer());
        try {
            run.prepare();
        } catch (Throwable failure) {
            run.fail("PRECHECK_SETUP_FAILED", failure);
            // Fail before vanilla loads chunks of a rejected/reused world.
            throw new IllegalStateException("Natural farm precheck setup rejected; inspect failure evidence", failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void started(ServerStartedEvent event) {
        if (!enabled() || run == null || run.server != event.getServer() || run.finished) return;
        try {
            run.start();
        } catch (Throwable failure) {
            run.fail("PRECHECK_START_FAILED", failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (!enabled() || run == null || run.server != event.getServer() || !run.started || run.finished) return;
        try {
            run.tick();
        } catch (Throwable failure) {
            run.fail("PRECHECK_EXCEPTION", failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void stopping(ServerStoppingEvent event) {
        if (run == null || run.server != event.getServer()) return;
        if (!run.finished) run.fail("STOPPED_BEFORE_PRECHECK_TERMINAL", null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void stopped(ServerStoppedEvent event) {
        if (run == null || run.server != event.getServer()) return;
        run.recordStopped();
    }

    private static final class Run {
        final MinecraftServer server;
        final boolean discoveryR2 = DISCOVERY_MODE.equals(System.getProperty(MODE_PROPERTY, ""));
        JsonObject predecessor;
        Path root;
        Path directory;
        Path world;
        Path jsonl;
        long seed;
        int sample;
        int ticks;
        int inspected;
        long startedGameTime = -1;
        Long t0;
        Long firstReadyObservedAt;
        boolean started;
        boolean finished;
        boolean ownsAttempt;
        boolean saveCompleted;
        String result = "RUNNING";
        String failureCode = "";
        Holder<Structure> structure;
        List<ChunkPos> candidates = List.of();
        NaturalVillageBuilding selected;
        BoundingBox footprint;
        final List<ChunkPos> forceTickets = new ArrayList<>();
        final Map<String, Long> observedRegistrations = new LinkedHashMap<>();
        final TreeSet<String> observedReady = new TreeSet<>();

        Run(MinecraftServer server) { this.server = server; }

        void prepare() throws Exception {
            world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            String rootText = System.getProperty(ROOT_PROPERTY, "");
            require(!rootText.isBlank(), "evidenceRoot is required");
            root = Path.of(rootText);
            require(root.isAbsolute(), "evidenceRoot must be absolute");
            root = root.normalize();
            sample = Integer.parseInt(System.getProperty(discoveryR2 ? DISCOVERY_PROPERTY : SAMPLE_PROPERTY, "0"));
            require(sample == 1 || sample == 2, "precheck ordinal must be 1 or 2");
            seed = (discoveryR2 ? DISCOVERY_SEEDS : SEEDS)[sample - 1];
            if (discoveryR2) {
                require(root.getFileName().toString().equals("natural-world-precheck-discovery-r2"),
                        "discovery R2 must use its separate evidence directory");
                require(sample != 1 || !Files.exists(root.resolve("seed-7022/attempt.json")), "discovery order violated");
                Path prior = sample == 1 ? root.resolveSibling("natural-world-precheck").resolve("seed-7020")
                        : root.resolve("seed-7021");
                predecessor = requireUnselectedNotFound(prior, sample == 1 ? 7020 : 7021);
            }
            directory = root.resolve("seed-" + seed);
            require(world.equals(directory.resolve("world")), "world path differs from frozen seed directory");
            require(server instanceof DedicatedServer, "ordinary DedicatedServer required");
            Files.createDirectories(root);
            String contract = JSON.toJson(contract(discoveryR2));
            Path contractFile = root.resolve("precheck-contract.json");
            if (Files.exists(contractFile)) {
                require(Files.readString(contractFile, StandardCharsets.UTF_8).equals(contract), "precheck contract changed");
            } else {
                Files.writeString(contractFile, contract, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            if (!discoveryR2) {
                require(sample != 1 || !Files.exists(root.resolve("seed-7020/attempt.json")), "seed order violated");
                require(sample != 2 || Files.isRegularFile(root.resolve("seed-7019/stopped.json")),
                        "sample 2 requires sample 1 stopped marker; PASS is not required; launcher must await process exit");
            }
            Files.createDirectories(directory);
            JsonObject attempt = object(discoveryR2 ? "discoveryCandidate" : "sample", sample,
                    "seed", seed, "world", world.toString(),
                    "pid", ProcessHandle.current().pid(), "startedUtc", Instant.now().toString());
            if (discoveryR2) attempt.addProperty("formalLongSample", false);
            Files.writeString(directory.resolve("attempt.json"), JSON.toJson(attempt), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            ownsAttempt = true;
            jsonl = directory.resolve("precheck.jsonl");
            Files.createFile(jsonl);
            emit("CONTRACT", contract(discoveryR2));
            emit("ATTEMPT_STARTED", attempt);
            if (discoveryR2) emit("PREDECESSOR_NOT_FOUND_VERIFIED", predecessor);
            require(!Boolean.getBoolean("sagadyssey.society.discovery") && !Boolean.getBoolean("sagadyssey.p2.discovery"),
                    "legacy discovery observers must be disabled");
            require(server.getWorldData().worldGenOptions().seed() == seed, "world seed differs from frozen sample");
            require(server.getWorldData().worldGenOptions().generateStructures(), "generate-structures must be true");
            require(!server.getWorldData().isFlatWorld() && !server.getWorldData().isDebugWorld(), "normal terrain required");
            var data = server.getWorldData().overworldData();
            require(!data.isInitialized() && data.getGameTime() == 0 && data.getDayTime() == 0,
                    "precheck requires a never-initialized new world");
            // Main has already written a fresh level.dat shell at this point. No world editing is done.
            for (String child : List.of("region", "entities", "poi", "data", "playerdata", "advancements", "stats", "DIM-1", "DIM1")) {
                Path path = world.resolve(child);
                if (Files.exists(path)) try (var files = Files.walk(path)) {
                    require(files.noneMatch(Files::isRegularFile), "prior world payload exists: " + child);
                }
            }
            emit("FRESH_WORLD_GATE", object("initialized", false, "gameTime", 0, "dayTime", 0,
                    "levelDatMayExist", true, "reason", "Vanilla Main.saveDataTag precedes this event; launcher supplies pre-process absence evidence"));
        }

        /** Read-only admission: a selected farm or any abnormal exit forbids the next seed. */
        JsonObject requireUnselectedNotFound(Path prior, long expectedSeed) throws Exception {
            Path stoppedFile = prior.resolve("stopped.json");
            require(Files.isRegularFile(stoppedFile), "prior candidate has no stopped evidence");
            var stopped = JSON.fromJson(Files.readString(stoppedFile, StandardCharsets.UTF_8), JsonObject.class);
            require(stopped.get("seed").getAsLong() == expectedSeed
                    && "NOT_FOUND".equals(stopped.get("result").getAsString()),
                    "only prior NOT_FOUND permits another discovery candidate");
            require(stopped.has("T0") && stopped.get("T0").isJsonNull()
                    && stopped.has("firstReadyObservedAt") && stopped.get("firstReadyObservedAt").isJsonNull(),
                    "prior registration or Ready forbids another seed");
            require(stopped.get("explicitNativeSaveCompleted").getAsBoolean()
                    && stopped.get("levelDatPresent").getAsBoolean()
                    && stopped.get("serverStoppedEventObserved").getAsBoolean(), "prior world was not safely saved/stopped");
            require(!stopped.has("selectionOccurred") || !stopped.get("selectionOccurred").getAsBoolean(),
                    "prior selected farm forbids another seed");
            Path priorLog = prior.resolve("precheck.jsonl");
            require(Files.isRegularFile(priorLog), "prior candidate event log is missing");
            try (var lines = Files.lines(priorLog, StandardCharsets.UTF_8)) {
                for (String line : lines.toList()) {
                    var entry = JSON.fromJson(line, JsonObject.class);
                    require(!"SELECTED_NEAREST_FARM_V3".equals(entry.get("event").getAsString()),
                            "prior selected farm cannot be hidden by a NOT_FOUND label");
                }
            }
            var attempt = JSON.fromJson(Files.readString(prior.resolve("attempt.json"), StandardCharsets.UTF_8), JsonObject.class);
            long pid = attempt.get("pid").getAsLong();
            require(ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty(),
                    "prior Java process may still be running; launcher must wait for exit");
            return object("seed", expectedSeed, "result", "NOT_FOUND", "selected", false,
                    "stoppedFile", stoppedFile.toString(), "stoppedSha256", sha256(stoppedFile),
                    "eventsSha256", sha256(priorLog), "priorPid", pid, "priorProcessAlive", false,
                    "sourceOfAuthority", "design v1.1 determines second natural seed during bounded precheck");
        }

        void start() throws Exception {
            var level = server.overworld();
            require(!(level.getChunkSource().getGenerator() instanceof FlatLevelSource), "flat generator rejected");
            require(server.getPlayerList().getPlayerCount() == 0, "PRECHECK has no player observer");
            structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(
                    ResourceKey.create(Registries.STRUCTURE, NaturalVillageBuilding.SUPPORTED));
            var state = level.getChunkSource().getGeneratorState();
            var placements = state.getPlacementsForStructure(structure);
            require(!placements.isEmpty(), "supported structure has no active placement");
            TreeSet<ChunkPos> points = new TreeSet<>(NEAREST);
            JsonArray placementAudit = new JsonArray();
            for (var placement : placements) {
                require(placement instanceof RandomSpreadStructurePlacement, "only declared native random-spread placement is supported");
                var spread = (RandomSpreadStructurePlacement) placement;
                require(spread.spacing() > 0, "invalid placement spacing");
                // salt() is protected; use the native codec to audit the complete placement without reflection.
                placementAudit.add(RandomSpreadStructurePlacement.CODEC.codec().encodeStart(
                        level.registryAccess().createSerializationContext(JsonOps.INSTANCE), spread).getOrThrow());
                int low = Math.floorDiv(-RADIUS_CHUNKS, spread.spacing());
                int high = Math.floorDiv(RADIUS_CHUNKS, spread.spacing());
                for (int rx = low; rx <= high; rx++) for (int rz = low; rz <= high; rz++) {
                    var point = spread.getPotentialStructureChunk(seed, rx * spread.spacing(), rz * spread.spacing());
                    if (distanceSquared(point) <= (long) RADIUS_CHUNKS * RADIUS_CHUNKS
                            && spread.isStructureChunk(state, point.x, point.z)) points.add(point);
                }
            }
            candidates = List.copyOf(points);
            startedGameTime = level.getGameTime();
            started = true;
            JsonArray positions = new JsonArray();
            candidates.forEach(p -> positions.add(chunk(p)));
            JsonObject plan = object("startedGameTime", startedGameTime, "candidateCount", candidates.size(),
                    "candidateOrder", "squared start-chunk distance from (0,0), chunkX, chunkZ", "players", 0);
            plan.add("placements", placementAudit);
            plan.add("candidates", positions);
            plan.add("environment", environment());
            emit("CANDIDATES_FROZEN", plan);
            observeRegistrations("NORMAL_SPAWN_BASELINE");
            checkRegionLimit();
            if (candidates.isEmpty()) finish("NOT_FOUND", "No legal placement point in frozen radius");
        }

        void tick() throws Exception {
            ticks++;
            require(server.getPlayerList().getPlayerCount() == 0, "unexpected player joined PRECHECK");
            require(server.overworld().getGameTime() - startedGameTime == ticks,
                    "game time diverged from ordinary server tick count");
            observeRegistrations("TICK");
            checkRegionLimit();
            // At most one candidate start per ordinary tick, always in the frozen nearest order.
            if (selected == null && inspected < candidates.size() && ticks < DEADLINE_TICKS) inspectNext();
            observeRegistrations("AFTER_NATIVE_GENERATION");
            checkRegionLimit();
            if (selected == null && inspected >= candidates.size()) {
                finish("NOT_FOUND", "No farm_v3 among all legal native starts inside the frozen 96-chunk circle");
                return;
            }
            if (ticks % 100 == 0) emit("PROGRESS", snapshot());
            if (ticks >= DEADLINE_TICKS) {
                if (selected == null) finish("FAIL", "SEARCH_DEADLINE");
                else if (t0 == null) finish("FAIL", "NO_FORMAL_REGISTRATION_AT_DEADLINE");
                else if (firstReadyObservedAt == null) finish("FAIL", "NO_READY_AT_DEADLINE");
                else {
                    var economy = NaturalVillageEconomyData.existing(server);
                    if (economy == null || economy.row(selected.buildingKey()) == null)
                        finish("FAIL", "TARGET_NOT_ENROLLED_IN_BOUNDED_ECONOMY");
                    else finish("PRECHECK_READY", "Native farm selected, formal T0/Ready and bounded economy recorded; long acceptance NOT_RUN");
                }
            }
        }

        void inspectNext() throws Exception {
            var point = candidates.get(inspected++);
            var level = server.overworld();
            // No FULL request in candidate search. Already generated normal spawn chunks are permitted and audited.
            var access = level.getChunkSource().getChunk(point.x, point.z, ChunkStatus.STRUCTURE_STARTS, true);
            require(access != null, "native STRUCTURE_STARTS request returned null");
            StructureStart start = access.getStartForStructure(structure.value());
            JsonObject observation = object("ordinal", inspected, "requestedStatus", "STRUCTURE_STARTS",
                    "returnedStatus", access.getPersistedStatus().toString(), "validStart", start != null && start.isValid());
            observation.add("chunk", chunk(point));
            JsonArray templates = new JsonArray();
            List<PoolElementStructurePiece> farms = new ArrayList<>();
            if (start != null && start.isValid()) {
                require(start.getChunkPos().equals(point), "native start identity mismatch");
                for (var piece : start.getPieces()) if (piece instanceof PoolElementStructurePiece pool) {
                    String template = templateId(level, pool);
                    JsonObject row = object("template", template, "rotation", pool.getRotation().name());
                    row.add("origin", position(pool.getPosition()));
                    row.add("bounds", bounds(pool.getBoundingBox()));
                    templates.add(row);
                    if (NaturalVillageLayout.FARM_V3.toString().equals(template)) farms.add(pool);
                }
            }
            observation.add("templates", templates);
            emit("NATIVE_START_INSPECTED", observation);
            if (farms.isEmpty()) return;
            farms.sort(Comparator.comparingInt((PoolElementStructurePiece p) -> p.getPosition().getX())
                    .thenComparingInt(p -> p.getPosition().getY()).thenComparingInt(p -> p.getPosition().getZ())
                    .thenComparing(p -> p.getRotation().name()));
            var piece = farms.getFirst();
            var anchor = start.getChunkPos().getWorldPosition();
            var center = new BlockPos(anchor.getX() + 8, start.getPieces().getFirst().getBoundingBox().minY(), anchor.getZ() + 8);
            selected = new NaturalVillageBuilding(level.dimension().identifier().toString(), NaturalVillageBuilding.SUPPORTED.toString(),
                    anchor, center, NaturalVillageLayout.FARM_V3.toString(), piece.getPosition(), piece.getRotation());
            footprint = pieceUnion(start);
            JsonObject chosen = building(selected);
            chosen.add("actualPieceUnion", bounds(footprint));
            chosen.addProperty("farmPiecesInSelectedStart", farms.size());
            chosen.addProperty("selectedOrdinal", inspected);
            if (!discoveryR2) chosen.addProperty("historical7019PointIsOnlyClue", true);
            else chosen.addProperty("selectionIsFinalForThisCandidate", true);
            emit("SELECTED_NEAREST_FARM_V3", chosen);
            int minX = Math.floorDiv(footprint.minX(), 16) - 1;
            int maxX = Math.floorDiv(footprint.maxX(), 16) + 1;
            int minZ = Math.floorDiv(footprint.minZ(), 16) - 1;
            int maxZ = Math.floorDiv(footprint.maxZ(), 16) + 1;
            JsonArray fullRequests = new JsonArray();
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                var cp = new ChunkPos(x, z);
                forceTickets.add(cp);
                fullRequests.add(chunk(cp));
            }
            emit("FULL_FOOTPRINT_REQUESTS", objectWith("chunks", fullRequests,
                    "paddingChunks", 1, "scope", "real native structure piece union", "forceTicks", false,
                    "note", "Vanilla spawn and generation dependencies can register neighbours; every resulting natural registration is audited"));
            for (var cp : forceTickets) {
                level.setChunkForced(cp.x, cp.z, true);
                level.getChunkSource().getChunk(cp.x, cp.z, ChunkStatus.FULL, true);
            }
            observeRegistrations("FIRST_FULL_FOOTPRINT_OBSERVATION");
            emit("FIRST_FULL_FOOTPRINT_OBSERVATION", snapshot());
        }

        void observeRegistrations(String reason) throws Exception {
            var registry = SettlementRegistryData.get(server);
            for (var record : registry.buildings().stream().sorted(Comparator.comparing(r -> r.key())).toList()) {
                if (!NaturalVillageBuilding.SUPPORTED.toString().equals(record.structureId())) continue;
                Long previous = observedRegistrations.putIfAbsent(record.key(), record.registeredGameTime());
                require(previous == null || previous == record.registeredGameTime(), "registered T0 changed for " + record.key());
                if (previous == null) emit("FORMAL_BUILDING_FIRST_OBSERVED", objectWith("buildingRecord", nbt(record.save()),
                        "registeredGameTime", record.registeredGameTime(), "observedGameTime", server.overworld().getGameTime(),
                        "observationReason", reason));
            }
            if (selected != null) {
                var record = registry.building(selected.buildingKey());
                if (record != null) {
                    if (t0 == null) {
                        t0 = record.registeredGameTime();
                        emit("TARGET_T0_RECORDED", object("T0", t0, "source", "BuildingRecord.registeredGameTime",
                                "observedGameTime", server.overworld().getGameTime(), "notReadyOrObserverStart", true));
                    } else require(t0 == record.registeredGameTime(), "target registered T0 changed");
                }
            }
            var life = NaturalVillageLifeData.existing(server);
            if (life == null) return;
            for (var row : life.villages()) {
                var b = NaturalVillageLifeData.decode(row);
                if (row.getBooleanOr("Ready", false) && observedReady.add(b.buildingKey())) {
                    if (selected != null && selected.buildingKey().equals(b.buildingKey())) {
                        require(t0 != null, "Ready exists without formal BuildingRecord");
                        firstReadyObservedAt = server.overworld().getGameTime();
                    }
                    emit("FIRST_READY_OBSERVED", objectWith("initialObservedState", snapshot(),
                            "buildingKey", b.buildingKey(), "initializedGameTime", row.getLongOr("Initialized", 0L),
                            "observedGameTime", server.overworld().getGameTime(), "neverBackdatedToT0", true));
                }
            }
            // If normal spawn had already initialized the selected farm, still retain its original T0.
            if (selected != null && firstReadyObservedAt == null) {
                var target = life.village(selected.buildingKey());
                if (target != null && target.getBooleanOr("Ready", false)) {
                    require(t0 != null, "selected spawn farm Ready without BuildingRecord");
                    firstReadyObservedAt = server.overworld().getGameTime();
                    emit("TARGET_ALREADY_READY_WHEN_SELECTED", snapshot());
                }
            }
        }

        void checkRegionLimit() {
            var life = NaturalVillageLifeData.existing(server);
            var economy = NaturalVillageEconomyData.existing(server);
            // A fifth pending building is also rejected: do not hide an unenrolled neighbour behind the four-row cap.
            require(life == null || life.villages().size() <= MAX_REGISTERED_REGIONS,
                    "REGISTERED_NATURAL_REGIONS_EXCEED_FOUR");
            require(economy == null || economy.rows().size() <= MAX_REGISTERED_REGIONS,
                    "ECONOMY_REGIONS_EXCEED_FOUR");
            long buildings = SettlementRegistryData.get(server).buildings().stream()
                    .filter(b -> NaturalVillageBuilding.SUPPORTED.toString().equals(b.structureId())).count();
            require(buildings <= MAX_REGISTERED_REGIONS, "FORMAL_NATURAL_BUILDINGS_EXCEED_FOUR");
        }

        JsonObject snapshot() {
            var level = server.overworld();
            JsonObject out = object("ticksFromServerStarted", ticks, "gameTime", level.getGameTime(),
                    "T0", t0, "firstReadyObservedAt", firstReadyObservedAt, "candidatesInspected", inspected);
            if (selected != null) out.add("selected", building(selected));
            out.add("environment", environment());
            out.add("allRegisteredBuildingsAndSettlements", nbt(SettlementRegistryData.get(server).save(new CompoundTag(), server.registryAccess())));
            var life = NaturalVillageLifeData.existing(server);
            var economy = NaturalVillageEconomyData.existing(server);
            out.add("allNaturalLifeIntents", life == null ? JsonNull.INSTANCE : nbt(life.save(new CompoundTag(), server.registryAccess())));
            out.add("allEconomyRegions", economy == null ? JsonNull.INSTANCE : nbt(economy.save(new CompoundTag(), server.registryAccess())));
            var characters = level.getDataStorage().get(CharacterRegistryData.FACTORY.type(CharacterRegistryData.DATA_NAME));
            var rights = level.getDataStorage().get(RightsStateData.FACTORY.type(RightsStateData.DATA_NAME));
            var flows = level.getDataStorage().get(SupplyFlowData.FACTORY.type(SupplyFlowData.NAME));
            out.add("allCharacters", characters == null ? JsonNull.INSTANCE : nbt(characters.save(new CompoundTag(), server.registryAccess())));
            out.add("allRightsIncludingReservations", rights == null ? JsonNull.INSTANCE : nbt(rights.save(new CompoundTag(), server.registryAccess())));
            out.add("allSupplyKnowledgeAndRoutes", flows == null ? JsonNull.INSTANCE : nbt(flows.save(new CompoundTag(), server.registryAccess())));
            boolean anyReady = life != null && life.villages().stream().anyMatch(v -> v.getBooleanOr("Ready", false));
            // These APIs lack an existing() getter. Only read them after native Ready proves they already exist.
            out.add("allHouseholds", anyReady ? nbt(HouseholdData.get(server).save(new CompoundTag(), server.registryAccess())) : JsonNull.INSTANCE);
            out.add("allNpcLifeIncludingBedsNeedsAndAuthority", anyReady ? nbt(NpcLifeData.get(server).save(new CompoundTag(), server.registryAccess())) : JsonNull.INSTANCE);
            out.addProperty("householdLifeNullMeaning", "not read before any native Ready; observer never registers household/life state");
            JsonArray households = new JsonArray();
            Map<String, NaturalVillageBuilding> buildings = new LinkedHashMap<>();
            if (life != null) for (var row : life.villages()) {
                var b = NaturalVillageLifeData.decode(row);
                buildings.put(b.buildingKey(), b);
            }
            if (selected != null) buildings.putIfAbsent(selected.buildingKey(), selected);
            buildings.values().stream().sorted(Comparator.comparing(NaturalVillageBuilding::buildingKey))
                    .forEach(b -> households.add(physical(b)));
            out.add("allObservedNaturalHouseholdPhysicalState", households);
            return out;
        }

        JsonObject physical(NaturalVillageBuilding b) {
            JsonObject out = building(b);
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(b.dimension())));
            if (level == null) { out.addProperty("dimensionLoaded", false); return out; }
            out.addProperty("dimensionLoaded", true);
            JsonArray stores = new JsonArray();
            var relativeStores = NaturalVillageLayout.stores(b);
            for (int i = 0; i < relativeStores.size(); i++) {
                var at = NaturalVillageLayout.at(b, relativeStores.get(i));
                var state = block(level, at);
                state.addProperty("role", NaturalVillageLayout.storageRole(i));
                state.addProperty("containerId", NaturalVillageEconomyData.storeId(b, i).toString());
                var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
                if (chunk != null && chunk.getBlockEntity(at) instanceof Container container)
                    state.add("actualItems", nbt(NaturalVillageEconomyData.items(container, server.registryAccess())));
                else state.add("actualItems", JsonNull.INSTANCE);
                stores.add(state);
            }
            out.add("stores", stores);
            JsonArray beds = new JsonArray();
            for (var relative : NaturalVillageLayout.BEDS) beds.add(block(level, NaturalVillageLayout.at(b, relative)));
            out.add("originalBeds", beds);
            JsonArray spareBeds = new JsonArray();
            for (var relative : NaturalVillageLayout.spareBeds()) spareBeds.add(block(level, NaturalVillageLayout.at(b, relative)));
            out.add("spareBedPositionsActualState", spareBeds);
            JsonArray crops = new JsonArray();
            for (var relative : NaturalVillageLayout.crops()) {
                var at = NaturalVillageLayout.at(b, relative);
                JsonObject crop = block(level, at);
                crop.add("soil", block(level, at.below()));
                crops.add(crop);
            }
            out.add("twelveOriginalCropPositionsActualAge", crops);
            JsonArray residents = new JsonArray();
            // Character storage is world/overworld scoped even if future supported dimensions change.
            var registry = server.overworld().getDataStorage().get(CharacterRegistryData.FACTORY.type(CharacterRegistryData.DATA_NAME));
            for (int i = 0; i < 3; i++) {
                var person = NaturalVillageLifeData.person(b, i);
                var body = NaturalVillageLifeData.body(b, i);
                JsonObject row = object("originalIndex", i, "personId", person.toString(), "bodyId", body.toString());
                var character = registry == null ? null : registry.byId(person);
                row.add("character", character == null ? JsonNull.INSTANCE : nbt(character.save()));
                if (character != null) row.addProperty("ageSinceCharacterBornTicks", level.getGameTime() - character.bornGameTime());
                var entity = level.getEntity(body);
                row.addProperty("loaded", entity != null);
                if (entity instanceof NpcBase npc) {
                    row.addProperty("characterMatches", person.equals(npc.getCharacterId()));
                    row.addProperty("health", npc.getHealth());
                    row.addProperty("profession", npc.getProfession().name());
                    row.addProperty("command", npc.getCommand().name());
                    row.add("position", object("x", npc.getX(), "y", npc.getY(), "z", npc.getZ()));
                    row.add("personalInventory", nbt(NaturalVillageEconomyData.items(npc.getPersonalInventory(), server.registryAccess())));
                    row.add("transportInventory", nbt(NaturalVillageEconomyData.items(npc.getTransportInventory(), server.registryAccess())));
                    row.add("entityNbtObserved", nbt(com.jgeted.sagadyssey.core.MinecraftApi.saveEntity(npc)));
                }
                residents.add(row);
            }
            out.add("originalResidents", residents);
            return out;
        }

        JsonObject block(ServerLevel level, BlockPos at) {
            JsonObject out = objectWith("position", position(at));
            var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
            out.addProperty("loaded", chunk != null);
            if (chunk != null) {
                var state = chunk.getBlockState(at);
                out.add("actualBlockState", nbt(NbtUtils.writeBlockState(state)));
                out.add("Age", state.hasProperty(CropBlock.AGE) ? JSON.toJsonTree(state.getValue(CropBlock.AGE)) : JsonNull.INSTANCE);
                out.addProperty("AgeNullMeaning", "actual block has no CropBlock.AGE property; never inferred or assigned");
            }
            return out;
        }

        JsonObject environment() {
            var level = server.overworld();
            var rules = level.getGameRules();
            return object("gameTime", level.getGameTime(), "dayTime", level.getDayTime(), "raining", level.isRaining(),
                    "thundering", level.isThundering(), "difficulty", level.getDifficulty().name(),
                    "doDaylightCycle", rules.get(GameRules.ADVANCE_TIME),
                    "doWeatherCycle", rules.get(GameRules.ADVANCE_WEATHER),
                    "doMobSpawning", rules.get(GameRules.SPAWN_MOBS),
                    "randomTickSpeed", rules.get(GameRules.RANDOM_TICK_SPEED),
                    "spawnChunkRadius", 0,
                    "trackedPlayers", server.getPlayerList().getPlayerCount(), "nightThreatValidated", false,
                    "randomCropGrowthValidated", false, "longAcceptance", "NOT_RUN");
        }

        void finish(String status, String reason) throws Exception {
            if (finished) return;
            result = status;
            failureCode = "PRECHECK_READY".equals(status) ? "" : reason;
            emit("TERMINAL_OBSERVATION", objectWith("state", snapshot(), "result", result, "reason", reason,
                    "longAcceptance", "NOT_RUN", "nightThreatValidated", false));
            if (!"PRECHECK_READY".equals(status)) {
                JsonObject failure = object("result", status, "code", failureCode, "exception", "");
                emit("FAIL", failure);
                Files.writeString(directory.resolve("failure.json"), JSON.toJson(failure), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
            }
            finished = true;
            saveThenHalt();
        }

        void fail(String code, Throwable failure) {
            result = "FAIL";
            failureCode = code;
            finished = true;
            try {
                // Do not append to an earlier attempt when a forbidden restart/second world is rejected.
                if (ownsAttempt) {
                    emit("FAIL", object("code", code, "exception", failure == null ? "" : trace(failure)));
                    if (server.overworld() != null) try { emit("FAIL_STATE", snapshot()); }
                    catch (Throwable snapshotFailure) { emit("FAIL_STATE_UNAVAILABLE", object("exception", trace(snapshotFailure))); }
                    Files.writeString(directory.resolve("failure.json"), JSON.toJson(object("code", code,
                            "exception", failure == null ? "" : trace(failure))), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } else {
                    // Setup errors have a distinct immutable rejection artifact and never overwrite accepted evidence.
                    Path fallback = root != null ? root : world.getParent();
                    Files.createDirectories(fallback);
                    Files.writeString(fallback.resolve("rejected-start-" + ProcessHandle.current().pid() + "-" + System.nanoTime() + ".json"),
                            JSON.toJson(object("code", code, "world", world == null ? "unknown" : world.toString(),
                                    "exception", failure == null ? "" : trace(failure))), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                }
            } catch (Throwable evidenceFailure) {
                Sagadyssey.LOGGER.error("NATURAL_FARM_PRECHECK evidence write failed; acceptance is FAIL", evidenceFailure);
            }
            Sagadyssey.LOGGER.error("NATURAL_FARM_PRECHECK FAIL code={}", code, failure);
            saveThenHalt();
        }

        void saveThenHalt() {
            try {
                if (ownsAttempt && server.overworld() != null) {
                    // Save the complete native world; normal stopping handlers may still perform a LOD handoff.
                    // Force tickets do not suppress it: TERMINAL_OBSERVATION is the pre-stop snapshot,
                    // while stopped.json inventories the authoritative final disk state as save evidence.
                    server.saveEverything(true, true, true);
                    saveCompleted = true;
                    emit("WORLD_SAVE_COMPLETED", object("world", world.toString(), "result", result,
                            "forceTicketsPreservedForRestartAudit", forceTickets.size()));
                }
            } catch (Throwable failure) {
                result = "FAIL";
                failureCode = "WORLD_SAVE_FAILED";
                try { if (ownsAttempt) emit("FAIL", object("code", failureCode, "exception", trace(failure))); }
                catch (Throwable evidenceFailure) { Sagadyssey.LOGGER.error("Cannot write save failure evidence", evidenceFailure); }
                Sagadyssey.LOGGER.error("Natural farm precheck world save failed", failure);
            } finally {
                server.halt(false);
            }
        }

        void recordStopped() {
            if (!ownsAttempt) return;
            try {
                if (!finished) {
                    result = "FAIL";
                    failureCode = "SERVER_ABORTED_BEFORE_TERMINAL";
                    finished = true;
                    emit("FAIL", object("code", failureCode, "note", "inspect ordinary server crash/log output"));
                }
                JsonArray files = new JsonArray();
                try (var paths = Files.walk(world)) {
                    for (var file : paths.filter(Files::isRegularFile).sorted().toList()) {
                        if (file.getFileName().toString().equals("session.lock")) continue;
                        files.add(object("path", world.relativize(file).toString().replace('\\', '/'),
                                "bytes", Files.size(file), "sha256", sha256(file)));
                    }
                }
                boolean levelSaved = Files.isRegularFile(world.resolve("level.dat"));
                if (!levelSaved || !saveCompleted) { result = "FAIL"; failureCode = "COMPLETE_WORLD_SAVE_NOT_CONFIRMED"; }
                JsonObject stopped = object("result", result, "failureCode", failureCode,
                        discoveryR2 ? "discoveryCandidate" : "sample", sample, "seed", seed,
                        "world", world.toString(), "stoppedUtc", Instant.now().toString(), "ticksFromServerStarted", ticks,
                        "T0", t0, "firstReadyObservedAt", firstReadyObservedAt, "explicitNativeSaveCompleted", saveCompleted,
                        "levelDatPresent", levelSaved, "worldPreservedInPlace", true, "longAcceptance", "NOT_RUN",
                        "serverStoppedEventObserved", true, "processExitStillMustBeAwaitedByLauncher", true);
                if (discoveryR2) {
                    stopped.addProperty("selectionOccurred", selected != null);
                    stopped.addProperty("formalLongSample", false);
                    stopped.addProperty("nextCandidatePermitted", "NOT_FOUND".equals(result) && selected == null && sample == 1);
                }
                stopped.add("worldFiles", files);
                emit("SERVER_STOPPED", stopped);
                Files.writeString(directory.resolve("stopped.json"), JSON.toJson(stopped), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
            } catch (Throwable failure) {
                result = "FAIL";
                failureCode = "STOP_EVIDENCE_FAILED";
                try { emit("FAIL", object("code", failureCode, "exception", trace(failure))); }
                catch (Throwable evidenceFailure) { Sagadyssey.LOGGER.error("Cannot write terminal evidence failure", evidenceFailure); }
                Sagadyssey.LOGGER.error("NATURAL_FARM_PRECHECK stopped evidence failed", failure);
                // No stopped marker: sample 2 must not proceed automatically.
            }
        }

        void emit(String event, JsonObject payload) throws IOException {
            JsonObject line = object("event", event, "utc", Instant.now().toString(),
                    discoveryR2 ? "discoveryCandidate" : "sample", sample, "seed", seed,
                    "tick", ticks, "mode", discoveryR2 ? DISCOVERY_MODE : "PRECHECK");
            line.add("payload", payload);
            Files.writeString(jsonl, JSON.toJson(line) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
    }

    private static JsonObject contract(boolean discoveryR2) {
        if (discoveryR2) return object("schema", 1, "mode", DISCOVERY_MODE,
                "frozenDiscoverySeedsInOrder", List.of(7021, 7022), "formalLongSamplesFrozen", false,
                "formalLongSampleCountRemains", 2, "priorBatchRetained", "natural-world-precheck:7019,7020",
                "correction", "7019/7020 were precheck candidates; design v1.1 chooses the second formal seed during precheck",
                "maximumAdditionalCandidates", 2, "maximumConsecutiveNotFoundIncluding7020", 3,
                "continueOnlyAfter", "NOT_FOUND with no selection, T0 or Ready, complete save and process exit",
                "oneWorldAttemptPerSeed", true, "searchCenterStartChunk", List.of(0, 0), "geometricRadiusChunks", RADIUS_CHUNKS,
                "radiusMetric", "startChunkX^2 + startChunkZ^2 <= 96^2", "sort", "distanceSquared, chunkX, chunkZ",
                "candidateGeneration", "native RandomSpreadStructurePlacement legal points; STRUCTURE_STARTS only until first actual farm_v3",
                "sameStartFarmTieBreak", "originX, originY, originZ, rotationName", "fullLoading", "selected start actual piece union plus one chunk",
                "maxRegisteredNaturalBuildingsAndEconomyRows", MAX_REGISTERED_REGIONS,
                "deadlineServerTicksIncludingSearch", DEADLINE_TICKS, "T0Source", "BuildingRecord.registeredGameTime",
                "failurePolicy", "save world and stop; selected initialization/terrain failure forbids another seed; READY ends discovery",
                "nightThreatValidated", false, "realPlayerUsed", false, "randomCropGrowthValidated", false,
                "longAcceptance", "NOT_RUN");
        return object("schema", 1, "mode", "PRECHECK", "frozenSeedsInOrder", List.of(7019, 7020),
                "oneWorldAttemptPerSeed", true, "searchCenterStartChunk", List.of(0, 0), "geometricRadiusChunks", RADIUS_CHUNKS,
                "radiusMetric", "startChunkX^2 + startChunkZ^2 <= 96^2", "sort", "distanceSquared, chunkX, chunkZ",
                "correction", "Previous API argument 24 was a placement-ring radius, not 24 geometric chunks; before any run it is replaced by an explicit frozen 96-chunk circle",
                "historical7019StartChunk", List.of(-68, -47), "historicalPointGetsNoPriority", true,
                "candidateGeneration", "native RandomSpreadStructurePlacement legal points; STRUCTURE_STARTS only until first actual farm_v3",
                "sameStartFarmTieBreak", "originX, originY, originZ, rotationName", "fullLoading", "selected start actual piece union plus one chunk",
                "maxRegisteredNaturalBuildingsAndEconomyRows", MAX_REGISTERED_REGIONS, "deadlineServerTicksIncludingSearch", DEADLINE_TICKS,
                "T0Source", "BuildingRecord.registeredGameTime", "failurePolicy", "save failure evidence and whole world, halt; never change seed/radius/site after selection",
                "nightThreatValidated", false, "realPlayerUsed", false, "longAcceptance", "NOT_RUN");
    }

    private static String templateId(ServerLevel level, PoolElementStructurePiece piece) {
        if (piece.getElement().getClass() != SinglePoolElement.class) return "UNSUPPORTED_ELEMENT_CLASS:" + piece.getElement().getClass().getName();
        var encoded = SinglePoolElement.CODEC.codec().encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                (SinglePoolElement) piece.getElement()).getOrThrow();
        require(encoded instanceof CompoundTag, "native single pool element did not encode as a compound");
        return ((CompoundTag) encoded).getStringOr("location", "");
    }

    private static BoundingBox pieceUnion(StructureStart start) {
        require(!start.getPieces().isEmpty(), "empty selected start");
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var piece : start.getPieces()) {
            var b = piece.getBoundingBox();
            minX = Math.min(minX, b.minX()); minY = Math.min(minY, b.minY()); minZ = Math.min(minZ, b.minZ());
            maxX = Math.max(maxX, b.maxX()); maxY = Math.max(maxY, b.maxY()); maxZ = Math.max(maxZ, b.maxZ());
        }
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static long distanceSquared(ChunkPos p) { return (long) p.x * p.x + (long) p.z * p.z; }
    private static JsonElement nbt(net.minecraft.nbt.Tag value) { return NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, value); }
    private static JsonObject position(BlockPos p) { return object("x", p.getX(), "y", p.getY(), "z", p.getZ()); }
    private static JsonObject chunk(ChunkPos p) { return object("x", p.x, "z", p.z, "distanceSquared", distanceSquared(p)); }
    private static JsonObject bounds(BoundingBox b) { return object("minX", b.minX(), "minY", b.minY(), "minZ", b.minZ(), "maxX", b.maxX(), "maxY", b.maxY(), "maxZ", b.maxZ()); }

    private static JsonObject building(NaturalVillageBuilding b) {
        JsonObject out = object("key", b.buildingKey(), "settlementId", b.settlementId().toString(), "dimension", b.dimension(),
                "structure", b.structureId(), "template", b.templateId(), "rotation", b.rotation().name(),
                "householdId", NaturalVillageLifeData.home(b).toString());
        out.add("startChunkOrigin", position(b.startChunkOrigin()));
        out.add("settlementCenter", position(b.settlementCenter()));
        out.add("origin", position(b.buildingOrigin()));
        return out;
    }

    private static JsonObject object(Object... values) {
        JsonObject out = new JsonObject();
        for (int i = 0; i < values.length; i += 2) out.add((String) values[i], JSON.toJsonTree(values[i + 1]));
        return out;
    }

    private static JsonObject objectWith(String key, JsonElement element, Object... values) {
        JsonObject out = object(values);
        out.add(key, element);
        return out;
    }

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    private static String trace(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int read; (read = input.read(buffer)) != -1;) hash.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
}
