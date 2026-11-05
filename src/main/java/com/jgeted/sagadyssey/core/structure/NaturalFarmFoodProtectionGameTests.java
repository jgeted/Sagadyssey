package com.jgeted.sagadyssey.core.structure;

import com.google.gson.Gson;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.life.LifeActivityData;
import com.jgeted.sagadyssey.npc.brain.life.LifePlanFactory;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import java.nio.file.*;
import java.util.*;

/** Controlled food decisions with original residents, real consumption and real fieldwork. */
public final class NaturalFarmFoodProtectionGameTests {
    private NaturalFarmFoodProtectionGameTests() { }
    private static final int FORMAL_LIMIT = 2400;
    public static Collection<TestFunction> tests() {
        return List.of(new TestFunction("natural_farm_food_protection", "actual_consumption_preserves_seeds",
                "sagadyssey_natural_life:empty", 4800, 0, true, NaturalFarmFoodProtectionGameTests::run));
    }
    private static void run(GameTestHelper h) {
        var cases = List.of(
                new Observation(h, NaturalVillageR1GameTests.fixture(h, 0, NaturalVillageLayout.FARM_V3), "pantry_first", 570),
                new Observation(h, NaturalVillageR1GameTests.fixture(h, 256, NaturalVillageLayout.FARM_V3), "protected_without_pantry", 350),
                new Observation(h, NaturalVillageR1GameTests.fixture(h, 512, NaturalVillageLayout.FARM_V3), "harvest_then_emergency", 800));
        boolean[] ready = {false}, finished = {false};
        h.onEachTick(() -> {
            if (finished[0]) return;
            try {
                if (!ready[0]) {
                    if (cases.stream().anyMatch(c -> !c.ready())) {
                        h.assertTrue(h.getTick() < 1200, "PRECONDITION: three original natural initializers become ready"); return;
                    }
                    // All three fixtures share one boundary, so later setup cannot alter a running case's clock.
                    h.getLevel().setDayTime(13000);
                    h.getLevel().getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, h.getLevel().getServer());
                    for (var c : cases) c.prepare();
                    ready[0] = true; save(h.getLevel()); return;
                }
                for (var c : cases) c.tick();
                if (cases.stream().allMatch(c -> c.complete)) {
                    for (var c : cases) { c.snapshot(); c.event("CASE_PASS", c.summary()); }
                    save(h.getLevel()); finished[0] = true;
                    Sagadyssey.LOGGER.info("NATURAL_FOOD_PROTECTION_PASS cases={} sameFormalLimit={}",
                            cases.stream().map(Observation::summary).toList(), FORMAL_LIMIT);
                    h.succeed();
                }
            } catch (RuntimeException failure) {
                for (var c : cases) { c.event("FAILURE", String.valueOf(failure.getMessage())); c.snapshot(); }
                save(h.getLevel()); throw failure;
            }
        });
    }

    private static final class Observation {
        final GameTestHelper h; final ServerLevel level; final NaturalVillageBuilding b;
        final String scenario; final int initialHunger; final Path log;
        final long[] lastMeals = {-1, -1, -1};
        final Set<String> workKeys = new HashSet<>(), eatKeys = new HashSet<>();
        final Set<UUID> observedPlantPlans = new HashSet<>();
        final List<FieldworkActionReceipt> harvests = new ArrayList<>(), plants = new ArrayList<>();
        final List<Map<String, Object>> mealEvidence = new ArrayList<>();
        final Set<BlockPos> declaredMature = new HashSet<>();
        final Map<UUID, Long> activePlantFirstSeen = new HashMap<>();
        long formalAt = -1, firstHarvestAt = -1, firstFarmerMealAt = -1, lastFarmerMealAt = -1;
        int meals, farmerMeals, farmerBagMeals, farmerHouseholdMeals, farmerPrivateMeals, initialMobile;
        long netCrop; boolean complete, normalSleepSeen, rawSeen, noHarvestBlockedSeen;

        Observation(GameTestHelper h, NaturalVillageBuilding b, String scenario, int hunger) {
            this.h = h; level = h.getLevel(); this.b = b; this.scenario = scenario; initialHunger = hunger;
            log = level.getServer().getWorldPath(LevelResource.ROOT).resolve("natural-farm-food-" + scenario + ".jsonl");
        }
        boolean ready() {
            var marker = NaturalVillageLifeData.get(level.getServer()).village(b.buildingKey());
            return marker != null && marker.getBoolean("Ready");
        }
        void prepare() {
            long now = level.getGameTime();
            for (int i = 0; i < 3; i++) {
                var npc = actor(i); require(npc != null && npc.isAlive(), "original resident present index=" + i);
                require(npc.getCharacterId().equals(NaturalVillageLifeData.person(b, i)), "original resident identity");
                var needs = NpcLifeData.get(level.getServer()).needs(npc.getCharacterId());
                require(needs != null && needs.lastAteAt() < 0, "no consumption hidden before controlled setup");
                lastMeals[i] = needs.lastAteAt();
            }
            require(count(actor(0).getPersonalInventory()) == 12 && count(store(NaturalVillageLayout.PANTRY)) == 4,
                    "original finite seeds12 and pantry4");
            require(stock() == 16, "no other undisclosed carrot stock");
            require(FieldworkData.get(level.getServer()).receiptsFor(actor(0).getUUID()).isEmpty(), "no pre-existing harvest receipt");
            int removed = 0;
            if (!scenario.equals("pantry_first")) removed += removeCarrots(store(NaturalVillageLayout.PANTRY));
            if (scenario.equals("harvest_then_emergency")) removed += removeCarrots(actor(0).getPersonalInventory());
            for (var local : NaturalVillageLayout.crops()) {
                var at = NaturalVillageLayout.at(b, local);
                require(level.getBlockState(at).is(Blocks.CARROTS), "original registered carrot field");
                level.setBlockAndUpdate(at, Blocks.CARROTS.defaultBlockState());
            }
            if (scenario.equals("harvest_then_emergency")) {
                // Finite controlled crop input; growth is excluded from this consumption-focused test.
                for (int i = 0; i < 3; i++) {
                    var at = NaturalVillageLayout.at(b, NaturalVillageLayout.crops().get(i));
                    level.setBlockAndUpdate(at, Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, 7));
                    declaredMature.add(at);
                }
            }
            NpcLifeData.get(level.getServer()).setHunger(actor(0).getCharacterId(), initialHunger, now);
            initialMobile = stock(); formalAt = now;
            require(NaturalFarmRules.foodSlot(actor(0), actor(0).getPersonalInventory(), "BAG", actor(0).blockPosition(), true, now) < 0,
                    "read-only selector cannot spend seeds before any real harvest");
            noHarvestBlockedSeen = true;
            event("CONTROLLED_INITIAL_BOUNDARY", Map.of("removedCarrots", removed, "initialMobileCarrots", initialMobile,
                    "farmerHunger", initialHunger, "declaredMatureCells", declaredMature.stream().map(BlockPos::toShortString).toList(),
                    "originalPeopleAndPermissions", true, "randomTickSpeed", 0, "dayTime", 13000,
                    "formalLimit", FORMAL_LIMIT, "furtherInterventions", "none; no plans, meals or receipts installed"));
            snapshot();
        }
        void tick() {
            if (formalAt < 0) return;
            observeWork(); observeMeals(); observePlans(); accounting();
            long elapsed = level.getGameTime() - formalAt;
            if (elapsed % 100 == 0) snapshot();
            if (scenario.equals("pantry_first")) {
                require(count(actor(0).getPersonalInventory()) == 12, "ordinary food and care preserve all twelve seeds");
                require(harvests.isEmpty() && plants.isEmpty(), "young crops are not consumption-test output");
                require(farmerBagMeals == 0 && farmerMeals <= 2, "limited ordinary meals only from pantry");
                if (farmerMeals == 2) {
                    require(farmerHouseholdMeals == 2 && count(store(NaturalVillageLayout.PANTRY)) == 2,
                            "two actual household meals spend pantry4 to2");
                    complete = true;
                }
            } else if (scenario.equals("protected_without_pantry")) {
                require(count(actor(0).getPersonalInventory()) == 12 && stock() == 12 && meals == 0,
                        "no pantry and non-urgent hunger cannot consume or donate the protected twelve");
                require(harvests.isEmpty() && plants.isEmpty(), "no crop output obscures seed conservation");
                if (elapsed >= 600 && normalSleepSeen && noHarvestBlockedSeen) complete = true;
            } else {
                if (firstHarvestAt < 0) require(meals == 0, "no emergency meal before real new output");
                if (farmerBagMeals > 0) {
                    require(firstFarmerMealAt >= firstHarvestAt && firstHarvestAt >= formalAt,
                            "actual harvest precedes emergency bag consumption");
                    require(farmerBagMeals <= 1, "one emergency bag meal, without consuming a second protected seed");
                }
                if (!plants.isEmpty() && farmerMeals >= 1) {
                    boolean realReplant = plants.stream().anyMatch(p -> harvests.stream().anyMatch(r ->
                            r.action().target().equals(p.action().target()) && r.committedAt() <= p.committedAt()));
                    require(realReplant, "actual harvested cell is replanted with one actual carrot");
                    require(count(actor(0).getPersonalInventory()) >= 1, "real replant and meal leave at least one held seed");
                    boolean allObservedPlantsFinished = !observedPlantPlans.isEmpty() && observedPlantPlans.stream()
                            .allMatch(id -> plants.stream().anyMatch(p -> p.action().planId().equals(id)));
                    // Continue for multiple normal decisions after the meal to expose repeated seed spending.
                    if (allObservedPlantsFinished && elapsed >= 600 && level.getGameTime() - lastFarmerMealAt >= 200) complete = true;
                }
            }
            require(complete || elapsed < FORMAL_LIMIT, "fixed food test deadline: " + summary());
        }
        void observeWork() {
            var receipts = FieldworkData.get(level.getServer()).receiptsFor(actor(0).getUUID()).stream()
                    .sorted(Comparator.comparingLong(FieldworkActionReceipt::committedAt).thenComparingInt(r -> r.action().stepIndex())).toList();
            for (var r : receipts) {
                if (!workKeys.add(r.action().key())) continue;
                require(r.committedAt() >= formalAt, "only post-boundary genuine fieldwork");
                int delta = r.inventoryDeltas().stream().filter(d -> d.fingerprint().equals("minecraft:carrot#0"))
                        .mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();
                netCrop += delta;
                if (r.action().operation() == FieldworkAction.Operation.HARVEST) {
                    require(declaredMature.contains(r.action().target()), "harvest uses a declared finite mature crop");
                    require(delta > 0, "harvest yields actual carrots"); harvests.add(r);
                    if (firstHarvestAt < 0) firstHarvestAt = r.committedAt();
                }
                if (r.action().operation() == FieldworkAction.Operation.PLANT) {
                    require(delta == -1 && r.resultingState().contains("minecraft:carrots"), "real plant consumes one and creates a carrot crop");
                    plants.add(r);
                }
                event("FIELDWORK_RECEIPT", Map.of("key", r.action().key(), "operation", r.action().operation(),
                        "plot", r.action().target().toShortString(), "time", r.committedAt(), "delta", delta,
                        "before", r.action().expectedState(), "after", r.resultingState()));
            }
            rawSeen |= count(store(NaturalVillageLayout.RAW)) > 0;
        }
        void observeMeals() {
            for (int i = 0; i < 3; i++) {
                var npc = actor(i); require(npc != null && npc.isAlive() && npc.getCharacterId().equals(NaturalVillageLifeData.person(b, i)),
                        "original living identity persists index=" + i);
                var needs = NpcLifeData.get(level.getServer()).needs(npc.getCharacterId());
                if (needs.lastAteAt() > lastMeals[i]) {
                    lastMeals[i] = needs.lastAteAt(); meals++;
                    if (i == 0) {
                        farmerMeals++; lastFarmerMealAt = needs.lastAteAt();
                        if (firstFarmerMealAt < 0) firstFarmerMealAt = needs.lastAteAt();
                    }
                    event("ACTUAL_MEAL", Map.of("resident", i, "body", npc.getUUID(), "person", npc.getCharacterId(),
                            "businessTick", needs.lastAteAt(), "hunger", needs.hunger(), "bagRemaining", count(npc.getPersonalInventory())));
                }
                for (var plan : plans(npc)) {
                    if (plan.createdAt() < formalAt || !plan.providerId().equals(LifePlanFactory.PROVIDER)) continue;
                    for (var step : plan.steps()) {
                        String operation = step.params().get("operation"), key = plan.id() + ":" + step.index();
                        if (!("EAT".equals(operation) || "CARE".equals(operation))
                                || !"DONE".equals(LifeActivityData.get(level.getServer()).settlement(key)) || !eatKeys.add(key)) continue;
                        String source = step.params().getOrDefault("source", "BAG");
                        require(i == 0 && "EAT".equals(operation), "only the controlled hungry original farmer consumes");
                        if (source.equals("BAG")) farmerBagMeals++;
                        if (source.equals("HOUSEHOLD")) farmerHouseholdMeals++;
                        if (source.equals("PRIVATE")) farmerPrivateMeals++;
                        var evidence = Map.<String, Object>of("key", key, "source", source, "operation", operation,
                                "person", npc.getCharacterId(), "planCreatedAt", plan.createdAt());
                        mealEvidence.add(evidence); event("NORMAL_CONSUMPTION_RECEIPT", evidence);
                    }
                }
            }
            require(farmerBagMeals + farmerHouseholdMeals + farmerPrivateMeals == farmerMeals,
                    "every measured meal has exactly one normal source receipt");
        }
        void observePlans() {
            var current = NpcBrainRuntime.data(level.getServer()).active(actor(0).getUUID()).orElse(null);
            if (current != null && current.providerId().equals(LifePlanFactory.PROVIDER)
                    && "SLEEP".equals(current.currentStep().params().get("operation")) && actor(0).isSleeping()) normalSleepSeen = true;
            if (current != null && current.providerId().equals(FieldworkPlanFactory.PROVIDER)
                    && "PLANT".equals(current.currentStep().params().get("operation"))) {
                observedPlantPlans.add(current.id());
                if (activePlantFirstSeen.putIfAbsent(current.id(), level.getGameTime()) == null)
                    event("NORMAL_PLANT_PHASE", Map.of("plan", current.id(), "step", current.currentStep().index(),
                            "hunger", NpcLifeData.get(level.getServer()).needs(actor(0).getCharacterId()).hunger(),
                            "bag", count(actor(0).getPersonalInventory())));
            }
            for (var p : plans(actor(0))) if (observedPlantPlans.contains(p.id())
                    && (p.status().name().equals("CANCELLED") || p.status().name().equals("FAILED")))
                require(plants.stream().anyMatch(r -> r.action().planId().equals(p.id())),
                        "observed PLANT cannot be lost to emergency plan cancellation");
        }
        List<NpcPlan> plans(NpcBase npc) {
            var data = NpcBrainRuntime.data(level.getServer()); var result = new ArrayList<>(data.history(npc.getUUID()));
            data.active(npc.getUUID()).ifPresent(result::add); return result;
        }
        NpcBase actor(int i) { return NaturalVillageR1GameTests.actor(level, b, i); }
        Container store(BlockPos local) { return (Container) level.getBlockEntity(NaturalVillageLayout.at(b, local)); }
        int stock() {
            int result = 0;
            for (var local : NaturalVillageLayout.stores(b)) result += count(store(local));
            for (int i = 0; i < 3; i++) { var n = actor(i); if (n != null) result += count(n.getPersonalInventory()) + count(n.getTransportInventory()); }
            for (var item : level.getEntitiesOfClass(ItemEntity.class, new AABB(b.buildingOrigin()).inflate(48), e -> e.getItem().is(Items.CARROT)))
                result += item.getItem().getCount();
            return result;
        }
        void accounting() { require(initialMobile + netCrop == stock() + meals,
                "physical carrot balance: initial=" + initialMobile + " netCrop=" + netCrop + " stock=" + stock() + " meals=" + meals); }
        Map<String, Object> summary() {
            var result = new LinkedHashMap<String, Object>(); result.put("case", scenario); result.put("complete", complete);
            result.put("elapsed", formalAt < 0 ? -1 : level.getGameTime() - formalAt);
            result.put("harvests", harvests.size()); result.put("plants", plants.size()); result.put("meals", meals);
            result.put("bagMeals", farmerBagMeals); result.put("householdMeals", farmerHouseholdMeals);
            result.put("privateMeals", farmerPrivateMeals);
            result.put("BAGEmergencyCoverage", farmerBagMeals > 0 ? "observed" : "not observed; no inference");
            result.put("rawSeen", rawSeen); return result;
        }
        void snapshot() {
            var people = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < 3; i++) {
                var n = actor(i); if (n == null) continue;
                people.add(Map.of("index", i, "body", n.getUUID(), "person", n.getCharacterId(), "position", n.position().toString(),
                        "bag", count(n.getPersonalInventory()), "cargo", count(n.getTransportInventory()), "health", n.getHealth(),
                        "needs", String.valueOf(NpcLifeData.get(level.getServer()).needs(n.getCharacterId())),
                        "active", NpcBrainRuntime.data(level.getServer()).active(n.getUUID()).map(Object::toString).orElse("NONE")));
            }
            var stores = new LinkedHashMap<String, Integer>();
            for (var local : NaturalVillageLayout.stores(b)) stores.put(local.toShortString(), count(store(local)));
            event("SNAPSHOT", Map.of("summary", summary(), "people", people, "stores", stores,
                    "initialMobile", initialMobile, "netCrop", netCrop, "physical", stock(), "meals", meals,
                    "routes", SupplyFlowData.get(level.getServer()).routes().stream()
                            .filter(r -> r.principal().id().equals(NaturalVillageLifeData.home(b))).map(Object::toString).toList(),
                    "reservations", RightsStateData.get(level.getServer()).reservations().stream().map(Object::toString).toList(),
                    "transfers", TransferLedgerData.get(level.getServer()).audits().stream().map(Object::toString).toList()));
        }
        void event(String kind, Object detail) {
            var row = new LinkedHashMap<String, Object>(); row.put("tick", level.getGameTime()); row.put("case", scenario);
            row.put("elapsed", formalAt < 0 ? -1 : level.getGameTime() - formalAt); row.put("event", kind); row.put("detail", detail);
            try { Files.writeString(log, new Gson().toJson(row) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (Exception e) { throw new IllegalStateException("cannot retain runtime food evidence", e); }
        }
        void require(boolean value, String reason) { h.assertTrue(value, scenario + ": " + reason); }
    }
    private static int count(Container c) {
        int result = 0; if (c != null) for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.CARROT)) result += c.getItem(i).getCount(); return result;
    }
    private static int removeCarrots(Container c) {
        int removed = 0; for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.CARROT)) {
            removed += c.getItem(i).getCount(); c.removeItemNoUpdate(i);
        }
        c.setChanged(); return removed;
    }
    private static void save(ServerLevel level) {
        try { level.getServer().saveEverything(true, true, true); net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete(); }
        catch (Exception e) { throw new IllegalStateException("cannot preserve complete food protection world", e); }
    }
}
