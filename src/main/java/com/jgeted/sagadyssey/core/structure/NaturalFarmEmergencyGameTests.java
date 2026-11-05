package com.jgeted.sagadyssey.core.structure;

import com.google.gson.Gson;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.life.LifePlanFactory;
import com.jgeted.sagadyssey.npc.brain.life.LifeActivityData;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import java.nio.file.*;
import java.util.*;

/** Normal scheduling after explicitly recorded finite emergency fixtures; no fabricated fulfillment. */
public final class NaturalFarmEmergencyGameTests {
    private NaturalFarmEmergencyGameTests() { }
    public static Collection<TestFunction> tests(boolean exhausted) {
        return List.of(new TestFunction("natural_farm_emergency",
                exhausted ? "night_emergency_after_real_retry_exhaustion" : "night_emergency_wakes_original_workers",
                "sagadyssey_natural_life:empty", exhausted ? 29000 : 6400, 0, true,
                h -> run(h, exhausted)));
    }
    private static void run(GameTestHelper h, boolean exhausted) {
        var b = NaturalVillageR1GameTests.fixture(h, 0, NaturalVillageLayout.FARM_V3);
        var observation = new Observation(h, b, exhausted);
        h.onEachTick(() -> {
            try { observation.tick(); }
            catch (RuntimeException error) {
                observation.event("FAILURE", Map.of("reason", String.valueOf(error.getMessage()),
                        "stage", observation.formalAt < 0 ? "PRECONDITION" : "FORMAL_2400_TICKS"));
                observation.snapshot(); observation.save(); throw error;
            }
        });
    }

    private static final class Observation {
        final GameTestHelper h; final ServerLevel level; final NaturalVillageBuilding building;
        final boolean exhausted; final Path log; final UUID household;
        final Set<String> actions = new HashSet<>(), routeStates = new HashSet<>();
        final Set<BlockPos> harvested = new HashSet<>(), replanted = new HashSet<>();
        final long[] lastMeals = {-1, -1, -1};
        final boolean[] woke = {false, false};
        final BlockPos[] preparedPlots = new BlockPos[3];
        long preparedAt = -1, nightAt = -1, formalAt = -1, workerMeal = -1, deliveryAt = -1;
        UUID originalNeed, deliveredRoute; int observedAttempts = -1, meals;
        long netCrop; boolean rawSeen, carried, delivered, completed;

        Observation(GameTestHelper h, NaturalVillageBuilding b, boolean exhausted) {
            this.h = h; level = h.getLevel(); building = b; this.exhausted = exhausted;
            household = NaturalVillageLifeData.home(b);
            log = level.getServer().getWorldPath(LevelResource.ROOT).resolve(
                    exhausted ? "natural-farm-emergency-exhausted.jsonl" : "natural-farm-emergency-night.jsonl");
            for (int i = 0; i < 3; i++) preparedPlots[i] = NaturalVillageLayout.at(b, NaturalVillageLayout.crops().get(i));
        }
        void tick() {
            if (completed) return;
            long now = level.getGameTime();
            if (preparedAt < 0) {
                var v = NaturalVillageLifeData.get(level.getServer()).village(building.buildingKey());
                if (v == null || !v.getBoolean("Ready")) {
                    require(h.getTick() < 1200, "PRECONDITION_FAIL: original natural initializer did not become ready"); return;
                }
                prepare(); return;
            }
            observePeople(); observeNeed();
            if (formalAt < 0) {
                require(now - preparedAt <= (exhausted ? 26000 : 3000),
                        "PRECONDITION_FAIL: could not establish required original sleep/budget state within preparation bound");
                if (exhausted && nightAt < 0) {
                    var r = request();
                    if (r != null && r.attempts() == r.maxAttempts()) {
                        require(r.maxAttempts() == 32 && r.reserved() == 0 && r.inTransit() == 0,
                                "PRECONDITION_FAIL: expected 32 genuine failed matches without held cargo");
                        require(r.key().deadline() - now > 2400, "PRECONDITION_FAIL: retry window has insufficient remaining lifetime");
                        level.setDayTime(13000); nightAt = now;
                        event("CONTROLLED_NIGHT_REQUESTED_AFTER_EXHAUSTION", Map.of("request", r.save().toString(),
                                "remainingTicks", r.key().deadline() - now, "dayTime", level.getDayTime()));
                        save();
                    }
                }
                if (nightAt >= 0 && sleeping(actor(0)) && sleeping(actor(1))) freeze();
                else if ((now - preparedAt) % 200 == 0) snapshot();
                return;
            }
            observeBusiness();
            long elapsed = now - formalAt;
            if (elapsed % 100 == 0) { snapshot(); assertAccounting(); }
            if (woke[0] && woke[1] && !harvested.isEmpty() && !replanted.isEmpty()
                    && rawSeen && carried && delivered && workerMeal >= deliveryAt && deliveryAt >= formalAt) {
                assertAccounting();
                require(harvested.stream().anyMatch(replanted::contains), "same real harvested cell was replanted");
                require(workerMealReceipt() != null, "original worker has a normal HOUSEHOLD EAT receipt");
                event("EMERGENCY_COMPLETE", Map.of("elapsed", elapsed, "originalNeed", originalNeed,
                        "deliveredRoute", deliveredRoute, "workerMealReceipt", workerMealReceipt(),
                        "harvested", harvested.toString(), "replanted", replanted.toString(),
                        "netCrop", netCrop, "stock", stock(), "consumed", meals));
                snapshot(); save(); completed = true;
                Sagadyssey.LOGGER.info("NATURAL_EMERGENCY_PASS exhausted={} elapsed={} originalNeed={} netCrop={} stock={} meals={}",
                        exhausted, elapsed, originalNeed, netCrop, stock(), meals);
                h.succeed(); return;
            }
            require(elapsed < 2400, "FORMAL_FAIL: 2400 ticks expired; wake=" + Arrays.toString(woke)
                    + " harvest=" + harvested.size() + " plant=" + replanted.size() + " RAW=" + rawSeen
                    + " carrying=" + carried + " delivered=" + delivered + " originalWorkerMeal=" + workerMeal);
        }

        void prepare() {
            long now = level.getGameTime();
            int removed = 0;
            for (int i = 0; i < 3; i++) {
                var npc = actor(i); require(npc != null && npc.isAlive(), "PRECONDITION_FAIL: original actor missing");
                require(npc.getCharacterId().equals(NaturalVillageLifeData.person(building, i)), "original person binding");
                removed += removeCarrots(npc.getPersonalInventory()) + removeCarrots(npc.getTransportInventory());
                lastMeals[i] = NpcLifeData.get(level.getServer()).needs(npc.getCharacterId()).lastAteAt();
                require(lastMeals[i] < 0, "PRECONDITION_FAIL: consumption occurred before controlled initial configuration");
            }
            for (var at : NaturalVillageLayout.stores(building)) removed += removeCarrots(store(at));
            require(removed == 16, "PRECONDITION_FAIL: record exactly original 12 seed + 4 pantry removal");
            for (var at : NaturalVillageLayout.crops()) {
                var pos = NaturalVillageLayout.at(building, at);
                require(level.getBlockState(pos).is(Blocks.CARROTS) && level.getBlockState(pos.below()).is(Blocks.FARMLAND),
                        "PRECONDITION_FAIL: original registered crop and soil");
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
            require(FieldworkData.get(level.getServer()).receiptsFor(actor(0).getUUID()).isEmpty(),
                    "PRECONDITION_FAIL: no earlier crop output is hidden by fixture setup");
            preparedAt = now;
            if (!exhausted) { level.setDayTime(13000); nightAt = now; }
            event("CONTROLLED_INITIAL_PREPARATION", Map.of("removedInitialCarrots", removed,
                    "remainingMobileCarrots", stock(), "registeredEmptyFarmCells", 12,
                    "automaticActorsAndPlansUnchanged", true, "randomTickSpeed", 3,
                    "normalDaylightCycle", true, "fixtureMobSpawning", false,
                    "exhaustAttemptsByNormalScheduling", exhausted,
                    "interventionBoundary", "finite fixture setup; no formal fulfillment or budget fields written"));
            snapshot(); save();
        }

        void freeze() {
            long now = level.getGameTime();
            var r = request(); require(r != null, "PRECONDITION_FAIL: ordinary supply demand missing");
            if (exhausted) {
                require(r.attempts() == 32 && r.maxAttempts() == 32, "PRECONDITION_FAIL: retries were not naturally exhausted");
                require(r.key().deadline() - now > 2400, "PRECONDITION_FAIL: cannot let normal expiry imitate urgent recovery");
            }
            require(stock() == 0 && meals == 0, "PRECONDITION_FAIL: zero-food preparation has no undisclosed input or consumption");
            var life = NpcLifeData.get(level.getServer());
            for (int i = 0; i < 2; i++) require(life.needs(actor(i).getCharacterId()).hunger() < 350,
                    "PRECONDITION_FAIL: original farmer/courier not independently hungry before household emergency");
            for (var pos : preparedPlots) {
                require(level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).is(Blocks.FARMLAND),
                        "PRECONDITION_FAIL: controlled mature source must occupy original empty registered field");
                level.setBlockAndUpdate(pos, Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, 7));
            }
            life.setHunger(actor(2).getCharacterId(), 800, now);
            formalAt = now;
            event("FROZEN_FORMAL_START", Map.of("formalLimitTicks", 2400,
                    "controlledMatureCells", Arrays.stream(preparedPlots).map(BlockPos::toShortString).toList(),
                    "workerInitialHunger", 800, "farmerHunger", life.needs(actor(0).getCharacterId()).hunger(),
                    "courierHunger", life.needs(actor(1).getCharacterId()).hunger(),
                    "farmerNormalSleepPlan", active(actor(0)), "courierNormalSleepPlan", active(actor(1)),
                    "originalNeed", r.save().toString(), "remainingDeadline", r.key().deadline() - now,
                    "allowedFurtherInterventions", "none; no time/resource/identity/plan edits"));
            snapshot(); save();
        }

        void observePeople() {
            var life = NpcLifeData.get(level.getServer());
            for (int i = 0; i < 3; i++) {
                var npc = actor(i); require(npc != null && npc.isAlive()
                        && npc.getCharacterId().equals(NaturalVillageLifeData.person(building, i)), "original living resident persists index=" + i);
                var need = life.needs(npc.getCharacterId()); require(need != null, "original resident needs record persists");
                if (need.lastAteAt() > lastMeals[i]) {
                    lastMeals[i] = need.lastAteAt(); meals++;
                    if (i == 2) workerMeal = need.lastAteAt();
                    event("ACTUAL_MEAL", Map.of("resident", i, "body", npc.getUUID(), "person", npc.getCharacterId(),
                            "time", need.lastAteAt(), "source", need.lastFoodSource(), "hunger", need.hunger()));
                }
                if (formalAt >= 0 && i < 2 && !woke[i] && !npc.isSleeping()) {
                    woke[i] = true; event("ORIGINAL_SLEEPER_WOKE", Map.of("resident", i, "body", npc.getUUID(),
                            "person", npc.getCharacterId(), "elapsed", level.getGameTime() - formalAt, "plan", active(npc)));
                }
            }
            var economy = NaturalVillageEconomyData.existing(level.getServer());
            var region = economy == null ? null : economy.row(building.buildingKey());
            require(region == null || region.getString("Phase").equals("NEAR"), "emergency remains actual near entities");
        }

        void observeNeed() {
            if (originalNeed == null) {
                var found = NeedRequestData.get(level.getServer()).requests().stream()
                        .filter(r -> r.key().requester().equals(household)
                                && r.key().purpose().contains("natural-diet:")
                                && r.key().item().equals(new ItemFingerprint("minecraft:carrot", 0)))
                        .sorted(Comparator.comparingLong(NeedRequest::createdAt)).findFirst().orElse(null);
                if (found == null) return; originalNeed = found.id();
            }
            var r = request();
            if (r.attempts() != observedAttempts) {
                require(r.attempts() > observedAttempts, "retry count cannot be reset to manufacture recovery");
                observedAttempts = r.attempts(); event("NORMAL_MATCH_ATTEMPT", Map.of("request", r.save().toString(),
                        "remainingDeadline", r.key().deadline() - level.getGameTime()));
            }
            if (formalAt < 0) require(!r.status().terminal() && r.key().deadline() > level.getGameTime(),
                    "PRECONDITION_FAIL: original supply request expired before required setup; do not substitute a fresh request");
        }

        void observeBusiness() {
            long now = level.getGameTime();
            for (var receipt : FieldworkData.get(level.getServer()).receiptsFor(actor(0).getUUID())) {
                if (!actions.add(receipt.action().key())) continue;
                require(receipt.committedAt() >= formalAt, "no prior production is counted as emergency output");
                long delta = receipt.inventoryDeltas().stream().filter(d -> d.fingerprint().equals("minecraft:carrot#0"))
                        .mapToLong(FieldworkActionReceipt.ItemDelta::quantity).sum(); netCrop += delta;
                if (receipt.action().operation() == FieldworkAction.Operation.HARVEST) {
                    boolean initialMature = Arrays.asList(preparedPlots).contains(receipt.action().target());
                    boolean replantedSource = NaturalVillageLayout.crops().stream()
                            .map(local -> NaturalVillageLayout.at(building, local)).anyMatch(receipt.action().target()::equals)
                            && FieldworkData.get(level.getServer()).receiptsFor(actor(0).getUUID()).stream()
                            .anyMatch(plant -> plant.action().operation() == FieldworkAction.Operation.PLANT
                                    && plant.action().target().equals(receipt.action().target())
                                    && plant.committedAt() >= formalAt && plant.committedAt() < receipt.committedAt()
                                    && plant.resultingState().contains("minecraft:carrots"));
                    require(initialMature || replantedSource,
                            "harvest source is a declared mature cell or a registered cell with earlier real replant evidence");
                    harvested.add(receipt.action().target());
                }
                if (receipt.action().operation() == FieldworkAction.Operation.PLANT) {
                    replanted.add(receipt.action().target()); require(delta == -1, "replant consumes one real carrot");
                }
                event("FIELDWORK_RECEIPT", Map.of("operation", receipt.action().operation().toString(),
                        "key", receipt.action().key(), "plot", receipt.action().target().toShortString(),
                        "committedAt", receipt.committedAt(), "carrotDelta", delta));
            }
            if (!rawSeen && count(store(NaturalVillageLayout.RAW)) > 0) {
                rawSeen = true; event("RAW_ACTUAL_STOCK", Map.of("count", count(store(NaturalVillageLayout.RAW)))); save();
            }
            for (var route : SupplyFlowData.get(level.getServer()).routes()) {
                if (!route.principal().equals(SubjectRef.household(household))) continue;
                if (routeStates.add(route.id() + ":" + route.stage())) event("ROUTE_STATE", route.toString());
                require(route.need().equals(originalNeed), "formal recovery retains original request rather than resetting its age/budget");
                if (route.carrierEntity() != null) require(route.carrierEntity().equals(actor(1).getUUID())
                        && route.carrierCharacter().equals(actor(1).getCharacterId()), "original courier carries the original duty");
                if (route.stage() == SupplyFlowData.Stage.CARRYING) {
                    require(count(actor(1).getTransportInventory()) >= route.quantity(), "CARRYING has physical cargo");
                    if (!carried) event("PHYSICAL_CARRIAGE", Map.of("route", route.id(), "cargo", count(actor(1).getTransportInventory()),
                            "position", actor(1).position().toString())); carried = true;
                }
                if (!delivered && route.stage() == SupplyFlowData.Stage.DONE) {
                    var ledger = TransferLedgerData.get(level.getServer());
                    var cargo = ContainerIds.npcCargo(actor(1).getCharacterId());
                    var transfer = ledger.audits().stream().filter(a -> a.authority().equals(route.token()))
                            .map(a -> ledger.receipt(a.transferId())).filter(Objects::nonNull)
                            .filter(r -> r.successful() && r.lawful() && r.finalRevisions().containsKey(cargo)
                                    && r.finalRevisions().containsKey(route.destination()))
                            .min(Comparator.comparingLong(com.jgeted.sagadyssey.worldsim.transfer.TransferReceipt::completedAt)).orElse(null);
                    require(transfer != null, "DONE route has its lawful cargo-to-destination transfer receipt");
                    delivered = true; deliveredRoute = route.id(); deliveryAt = transfer.completedAt();
                    event("ACTUAL_PANTRY_DELIVERY", Map.of("route", route.id(), "quantity", route.quantity(),
                            "pantry", count(store(NaturalVillageLayout.PANTRY)), "request", request().save().toString(),
                            "transferReceipt", transfer.save().toString(), "observedAt", now)); save();
                }
            }
        }

        String workerMealReceipt() {
            var npc = actor(2); var brain = NpcBrainRuntime.data(level.getServer());
            var plans = new ArrayList<>(brain.history(npc.getUUID())); brain.active(npc.getUUID()).ifPresent(plans::add);
            for (var p : plans) if (p.createdAt() >= formalAt && p.providerId().equals(LifePlanFactory.PROVIDER)
                    && p.steps().stream().anyMatch(step -> "EAT".equals(step.params().get("operation"))
                    && "HOUSEHOLD".equals(step.params().get("source")))
                    && "DONE".equals(LifeActivityData.get(level.getServer()).settlement(p.id() + ":2"))) return p.id() + ":2";
            return null;
        }
        boolean sleeping(NpcBase npc) {
            if (npc == null || !npc.isSleeping()) return false;
            var p = NpcBrainRuntime.data(level.getServer()).active(npc.getUUID()).orElse(null);
            return p != null && p.providerId().equals(LifePlanFactory.PROVIDER)
                    && "SLEEP".equals(p.currentStep().params().get("operation"));
        }
        String active(NpcBase npc) { return NpcBrainRuntime.data(level.getServer()).active(npc.getUUID()).map(Object::toString).orElse("NONE"); }
        NpcBase actor(int i) { return NaturalVillageR1GameTests.actor(level, building, i); }
        NeedRequest request() { return originalNeed == null ? null : NeedRequestData.get(level.getServer()).request(originalNeed); }
        Container store(BlockPos local) { return (Container) level.getBlockEntity(NaturalVillageLayout.at(building, local)); }
        int stock() {
            int total = 0; for (var local : NaturalVillageLayout.stores(building)) total += count(store(local));
            for (int i = 0; i < 3; i++) { var npc = actor(i); if (npc != null) total += count(npc.getPersonalInventory()) + count(npc.getTransportInventory()); }
            for (var item : level.getEntitiesOfClass(ItemEntity.class, new AABB(building.buildingOrigin()).inflate(48),
                    e -> e.getItem().is(Items.CARROT))) total += item.getItem().getCount();
            return total;
        }
        void assertAccounting() { require(netCrop == stock() + meals, "carrot conservation from zero mobile stock: netCrop=" + netCrop + " actual=" + stock() + " meals=" + meals); }
        void snapshot() {
            var people = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < 3; i++) {
                var npc = actor(i); if (npc == null) { people.add(Map.of("resident", i, "missing", true)); continue; }
                var row = new LinkedHashMap<String, Object>(); row.put("resident", i); row.put("body", npc.getUUID());
                row.put("person", npc.getCharacterId()); row.put("health", npc.getHealth()); row.put("sleeping", npc.isSleeping());
                row.put("position", npc.position().toString()); row.put("needs", String.valueOf(NpcLifeData.get(level.getServer()).needs(npc.getCharacterId())));
                row.put("bag", count(npc.getPersonalInventory())); row.put("cargo", count(npc.getTransportInventory()));
                row.put("plan", active(npc)); row.put("activity", npc.getWorkActivityDetail()); people.add(row);
            }
            var inventories = new LinkedHashMap<String, Integer>();
            for (var local : NaturalVillageLayout.stores(building)) inventories.put(local.toShortString(), count(store(local)));
            var r = request();
            event("SNAPSHOT", Map.of("people", people, "stores", inventories, "dayTime", level.getDayTime(),
                    "netCrop", netCrop, "meals", meals, "need", r == null ? "NONE" : r.save().toString(),
                    "routes", SupplyFlowData.get(level.getServer()).routes().stream().filter(x -> x.principal().equals(SubjectRef.household(household))).map(Object::toString).toList(),
                    "reservations", RightsStateData.get(level.getServer()).reservations().stream().map(Object::toString).toList()));
        }
        void event(String event, Object detail) {
            var row = new LinkedHashMap<String, Object>(); row.put("tick", level.getGameTime());
            row.put("formalElapsed", formalAt < 0 ? -1 : level.getGameTime() - formalAt);
            row.put("event", event); row.put("detail", detail);
            try { Files.writeString(log, new Gson().toJson(row) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (Exception error) { throw new IllegalStateException("cannot retain emergency evidence", error); }
        }
        void save() {
            try {
                level.getServer().saveEverything(true, true, true);
                net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            } catch (Exception error) { throw new IllegalStateException("cannot retain complete emergency world", error); }
        }
        void require(boolean value, String reason) { h.assertTrue(value, reason); }
    }

    private static int count(Container c) { int n = 0; if (c != null) for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.CARROT)) n += c.getItem(i).getCount(); return n; }
    private static int removeCarrots(Container c) {
        int n = 0; for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.CARROT)) {
            n += c.getItem(i).getCount(); c.removeItemNoUpdate(i);
        }
        c.setChanged(); return n;
    }
}
