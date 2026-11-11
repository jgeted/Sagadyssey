package com.jgeted.sagadyssey.core.structure;

import net.minecraft.world.level.gamerules.GameRules;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.growth.SettlementCoordinatorData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.NeedRequestData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** 延续原原生观察器和完整物资对账，不跳刻、不代替居民执行、不进入玩家 JAR。 */
@EventBusSubscriber(modid = "sagadyssey")
public final class SettlementRedesignWorldGameTests {
    private static Run run;

    @SubscribeEvent public static void before(ServerAboutToStartEvent event) {
        if (!Boolean.getBoolean("sagadyssey.redesign.observe")) return;
        try { run = new Run(event.getServer()); run.prepare(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void started(ServerStartedEvent event) {
        if (run != null && run.server == event.getServer()) try { run.start(); } catch (Exception error) { run.fail(error); }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event) {
        if (run != null && run.server == event.getServer() && !run.stopping) try { run.tick(); } catch (Exception error) { run.fail(error); }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        if (run != null && run.server == event.getServer()) try { run.stopped(); } catch (Exception error) { throw new IllegalStateException(error); }
    }

    static final class Run extends SettlementGrowthWorldGameTests.Run {
        boolean activated;
        long firstTemporarySleep = -1;
        String lastTasks = "";
        final Map<String, Long> timeSamples = new TreeMap<>();
        final Map<String, Long> openingAdjustment = new TreeMap<>();
        boolean savedBaselineOpened;
        boolean coldStart;
        boolean preexistingTemporaryBed;

        Run(MinecraftServer server) { super(server); }

        @Override void start() throws Exception {
            super.start();
            // 使用原版逐刻 sprint；不让固定 1000 TPS 的虚拟排程落后触发误导性单刻超时。
            server.tickRateManager().setTickRate(20);
            server.tickRateManager().requestGameToSprint((int) Math.min(Integer.MAX_VALUE, duration + 24000));
            emit("TICK_PACING", Map.of("method", "vanilla_tick_sprint", "skipSimulationTicks", false,
                    "watchdogChanged", false, "randomTickSpeed", server.overworld().getGameRules().get(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED)));
        }

        @Override void tick() throws Exception {
            super.tick();
            if (!stopping && village != null && Boolean.getBoolean("sagadyssey.redesign.diagnostic")
                    && server.overworld().getGameTime() % 2400 == 0) emit("DIAGNOSTIC_SNAPSHOT", snapshot());
        }

        @Override void prepare() throws Exception {
            coldStart = episode == 0 && !Boolean.getBoolean("sagadyssey.redesign.savedBaseline");
            if (Boolean.getBoolean("sagadyssey.redesign.savedBaseline")) {
                require(seed == 7021 && server.getWorldData().worldGenOptions().seed() == seed
                        && !server.getWorldData().isFlatWorld(), "Only the preserved native checkpoint is admitted");
                root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().getParent();
                state.last = server.getWorldData().overworldData().getGameTime();
                state.ready = state.last;
                state.formalStart = state.last;
                events = root.resolve("episode-" + episode + "-events.jsonl");
                java.nio.file.Files.createFile(events);
                emit("SAVED_CHECKPOINT_ADMISSION", Map.of("savedTick", state.last, "originalWorldNotChanged", true,
                        "unsavedPriorObservationNotCredited", true));
            } else if (Boolean.getBoolean("sagadyssey.redesign.crashResume")) {
                require(seed == 7021 && episode > 1, "Only preserved 7021 calibration incident may resume");
                require(server.getWorldData().worldGenOptions().seed() == seed && !server.getWorldData().isFlatWorld(), "Wrong preserved world");
                root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().getParent();
                var previous = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(root.resolve("episode-" + (episode - 1) + "-stopped.json"))).getAsJsonObject();
                require(!previous.get("normalStop").getAsBoolean(), "Crash recovery must be explicit and specific");
                state = new com.google.gson.Gson().fromJson(previous.get("state"), SettlementGrowthWorldGameTests.State.class);
                long saved = server.getWorldData().overworldData().getGameTime();
                require(saved >= state.last && saved - state.last <= 200, "Unobserved gap requires separate reconciliation");
                events = root.resolve("episode-" + episode + "-events.jsonl");
                java.nio.file.Files.createFile(events);
                emit("PRESERVED_CRASH_RESUME", Map.of("lastObserved", state.last, "savedTick", saved,
                        "remainingGapCheckedByFullItemAudit", true, "noWorldRewrite", true));
                state.last = saved;
            } else super.prepare();
            var observerFile = root.resolve("episode-" + (episode - 1) + "-coordination-observer.json");
            if (episode > 0 && java.nio.file.Files.exists(observerFile)) {
                var saved = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(observerFile)).getAsJsonObject();
                firstTemporarySleep = saved.get("firstTemporarySleep").getAsLong();
                coldStart = saved.has("coldStart") && saved.get("coldStart").getAsBoolean();
                preexistingTemporaryBed = saved.has("preexistingTemporaryBed") && saved.get("preexistingTemporaryBed").getAsBoolean();
                saved.getAsJsonObject("timeSamples").entrySet().forEach(entry -> timeSamples.put(entry.getKey(), entry.getValue().getAsLong()));
                if (!Boolean.getBoolean("sagadyssey.redesign.savedBaseline")) {
                    saved.getAsJsonObject("openingAdjustment").entrySet().forEach(entry -> openingAdjustment.put(entry.getKey(), entry.getValue().getAsLong()));
                }
            }
            // 新阶段从同一个世界的当前时刻继续计时，原累计账目和行动 ID 不重置。
            if (Boolean.getBoolean("sagadyssey.redesign.newEpisode") && state.ready >= 0) {
                state.formalStart = server.getWorldData().overworldData().getGameTime();
                state.formalMeals = state.meals.clone();
                state.allFeatures = -1;
            }
        }

        @Override void stopped() throws Exception {
            super.stopped();
            java.nio.file.Files.writeString(root.resolve("episode-" + episode + "-coordination-observer.json"),
                    new com.google.gson.Gson().toJson(Map.of("firstTemporarySleep", firstTemporarySleep,
                            "timeSamples", timeSamples, "openingAdjustment", openingAdjustment,
                            "coldStart", coldStart, "preexistingTemporaryBed", preexistingTemporaryBed)),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        }

        CompoundTag coordination() {
            var data = SettlementCoordinatorData.existing(server);
            var row = data == null || village == null ? null : data.site(village.buildingKey());
            return row == null ? new CompoundTag() : row;
        }

        @Override CompoundTag growth() {
            var old = super.growth();
            var coordinator = coordination();
            if (coordinator.isEmpty()) return old;
            var house = coordinator.getCompoundOrEmpty("Jobs").getCompoundOrEmpty("HOUSE");
            old.putString("HouseStage", house.getStringOr("HouseStage", ""));
            old.putInt("Placed", house.getIntOr("Placed", 0));
            old.putString("WallMaterial", house.getStringOr("WallMaterial", ""));
            int labor = 0;
            for (String key : coordinator.getCompoundOrEmpty("Jobs").keySet()) labor += coordinator.getCompoundOrEmpty("Jobs").getCompoundOrEmpty(key).getIntOr("Labor", 0);
            old.putInt("Labor", labor);
            return old;
        }

        Map<String, String> authoritative() {
            var out = new LinkedHashMap<String, String>();
            out.put("people", CharacterRegistryData.get(server).save(new CompoundTag(), server.registryAccess()).toString());
            out.put("households", HouseholdData.get(server).save(new CompoundTag(), server.registryAccess()).toString());
            out.put("life", NpcLifeData.get(server).save(new CompoundTag(), server.registryAccess()).toString());
            out.put("rights", RightsStateData.get(server).save(new CompoundTag(), server.registryAccess()).toString());
            out.put("needs", NeedRequestData.get(server).save(new CompoundTag(), server.registryAccess()).toString());
            out.put("routes", SupplyFlowData.get(server).save(new CompoundTag(), server.registryAccess()).toString());
            out.put("physical", physical().toString());
            return out;
        }

        @Override void observe() throws Exception {
            if (Boolean.getBoolean("sagadyssey.redesign.savedBaseline") && !savedBaselineOpened) {
                for (int i = 0; i < 3; i++) {
                    var body = NaturalVillageLifeData.body(village, i);
                    require(server.overworld().getEntity(body) instanceof NpcBase, "Original body must load before measurement");
                    var needs = NpcLifeData.get(server).needs(NaturalVillageLifeData.person(village, i));
                    state.lastMeal[i] = needs.lastAteAt();
                    for (var receipt : com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData.get(server).receiptsFor(body)) state.actions.add(receipt.action().key());
                }
                state.opening.putAll(physical());
                state.ready = server.overworld().getGameTime();
                state.formalStart = state.ready;
                preexistingTemporaryBed = coordination().getCompoundOrEmpty("Jobs").getCompoundOrEmpty("TEMP_BED").getIntOr("Placed", 0) == 1
                        && server.overworld().getBlockState(NaturalVillageLayout.at(village,
                        new net.minecraft.core.BlockPos(6, 1, 13))).is(net.minecraft.world.level.block.Blocks.WHITE_BED);
                var prior = expected();
                for (String key : prior.keySet()) openingAdjustment.put(key, prior.get(key) - state.opening.getOrDefault(key, 0L));
                savedBaselineOpened = true;
                emit("CHECKPOINT_INITIAL_LEDGER", Map.of("physical", physical(), "priorTransformations", openingAdjustment,
                        "authoritative", authoritative(), "coordinator", nbt(coordination())));
            }
            if (!activated && Boolean.getBoolean("sagadyssey.redesign.activate")) {
                var before = authoritative();
                var home = HouseholdData.get(server).byId(NaturalVillageLifeData.home(village));
                require(home != null, "Original household missing");
                String result = SettlementCoordinatorData.get(server).activate(server, village.buildingKey(),
                        home.headCharacterId(), server.overworld().getGameTime());
                require(result.equals("ACTIVATED") || result.equals("ALREADY_ACTIVE"), "Activation denied: " + result);
                require(before.equals(authoritative()), "Activation changed original people, stock or obligations");
                emit("ACTIVATION_AUTHORITY_AND_CONSERVATION", Map.of("decision", result, "official", home.headCharacterId(),
                        "unchanged", before, "coordinator", nbt(coordination())));
                activated = true;
            }
            super.observe();
            var coordinator = coordination();
            if (coordinator.isEmpty()) return;
            long now = server.overworld().getGameTime();
            for (String key : coordinator.getCompoundOrEmpty("Jobs").keySet()) {
                var job = coordinator.getCompoundOrEmpty("Jobs").getCompoundOrEmpty(key);
                require(!job.getBooleanOr("Review", false), "Coordinator job requires review: " + key + ":" + job.getStringOr("Reason", ""));
            }
            for (int i = 0; i < 3; i++) if (server.overworld().getEntity(NaturalVillageLifeData.body(village, i)) instanceof NpcBase npc) {
                var plan = com.jgeted.sagadyssey.npc.brain.persistence.NpcBrainData.get(server).active(npc.getUUID()).orElse(null);
                String activity = npc.isSleeping() ? "SLEEP" : plan == null ? "NO_PLAN" : plan.providerId().toString() + ":" + plan.currentStep().executorId();
                timeSamples.merge(i + ":" + activity, 1L, Long::sum);
                if (i == 2 && npc.isSleeping() && npc.getSleepingPos().filter(NaturalVillageLayout.at(village, new net.minecraft.core.BlockPos(6, 1, 13))::equals).isPresent() && firstTemporarySleep < 0) {
                    firstTemporarySleep = now;
                    emit("ORIGINAL_RESIDENT_SLEPT_IN_TEMPORARY_BED", Map.of("person", npc.getCharacterId(), "tick", now));
                }
            }
            StringBuilder summary = new StringBuilder();
            for (String key : coordinator.getCompoundOrEmpty("Jobs").keySet()) {
                var task = coordinator.getCompoundOrEmpty("Jobs").getCompoundOrEmpty(key);
                summary.append(key).append(':').append(task.getStringOr("State", "")).append(':').append(task.getIntOr("Placed", 0))
                        .append(':').append(task.getIntOr("Cycle", 0)).append(':').append(task.read("Person", UUIDUtil.CODEC).isPresent() ? task.read("Person", UUIDUtil.CODEC).orElseThrow() : "none").append(';');
            }
            if (!summary.toString().equals(lastTasks)) { lastTasks = summary.toString(); emit("COORDINATION_PROGRESS", nbt(coordinator)); }
            if (growth().getStringOr("HouseStage", "").equals("DONE") && state.slept >= 0
                    && (firstTemporarySleep >= 0 || preexistingTemporaryBed)
                    && (!coldStart || state.net.getOrDefault("minecraft:carrot", 0L) > 0
                        && growth().getIntOr("ConsumedFeed", 0) >= 4 && MinecraftApi.listOfType(growth(), "Animals", Tag.TAG_COMPOUND).size() == 4
                        && (!coordinator.getBooleanOr("ExpandFields", false) || !state.tilled.isEmpty() && !state.newHarvested.isEmpty()))
                    && Arrays.stream(state.meals).allMatch(value -> value > 0) && state.allFeatures < 0) {
                state.allFeatures = now;
                emit("REDESIGN_CHAIN_OBSERVED", snapshot());
            }
        }

        @Override Map<String, Long> expected() {
            var expected = super.expected();
            var coordinator = coordination();
            if (!coordinator.isEmpty()) {
                var jobs = coordinator.getCompoundOrEmpty("Jobs");
                if (jobs.getCompoundOrEmpty("TEMP_BED").getIntOr("Placed", 0) == 1) expected.merge("minecraft:white_bed", -1L, Long::sum);
                if (jobs.getCompoundOrEmpty("RELOCATE").contains("RecoveredAt")) expected.merge("minecraft:white_bed", 1L, Long::sum);
            }
            openingAdjustment.forEach((key, value) -> expected.merge(key, -value, Long::sum));
            return expected;
        }

        @Override Object snapshot() {
            var out = new LinkedHashMap<String, Object>();
            out.put("originalObserver", super.snapshot());
            out.put("coordinator", nbt(coordination()));
            out.put("firstTemporarySleep", firstTemporarySleep);
            out.put("coldStart", coldStart);
            out.put("preexistingTemporaryBed", preexistingTemporaryBed);
            out.put("timeSamplesEvery200Ticks", timeSamples);
            return out;
        }
    }
}
