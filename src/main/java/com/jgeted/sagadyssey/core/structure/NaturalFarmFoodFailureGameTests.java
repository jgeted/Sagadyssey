package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.google.gson.Gson;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.life.LifeActivityData;
import com.jgeted.sagadyssey.npc.brain.life.LifePlanFactory;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import java.nio.file.*;
import java.util.*;

/** Diagnostic counterexamples: normal decisions only; preparation and one restoration are explicit. */
public final class NaturalFarmFoodFailureGameTests {
    private NaturalFarmFoodFailureGameTests() { }
    public static Collection<TestFunction> tests() { return tests(false); }
    public static Collection<TestFunction> tests(boolean priorityOnly) {
        return List.of(new TestFunction("natural_farm_food_failure", priorityOnly ? "available_pantry_priority" : "priority_and_actual_bag_consumption",
                "sagadyssey_natural_life:empty", 6400, 0, true, h -> run(h, priorityOnly)));
    }
    private static void run(GameTestHelper h, boolean priorityOnly) {
        var first = new Case(h, NaturalVillageR1GameTests.fixture(h, 0, NaturalVillageLayout.FARM_V3), true);
        var cases = priorityOnly ? List.of(first) : List.of(first,
                new Case(h, NaturalVillageR1GameTests.fixture(h, 256, NaturalVillageLayout.FARM_V3), false));
        boolean[] prepared = {false}, ended = {false}; long[] readyAt = {-1};
        h.onEachTick(() -> {
            if (ended[0]) return;
            try {
                if (!prepared[0]) {
                    if (cases.stream().anyMatch(c -> !c.ready())) {
                        h.assertTrue(h.getTick() < 1200, "PRECONDITION: original fixtures ready before1200"); return;
                    }
                    if (readyAt[0] < 0) {
                        h.getLevel().setDayTime(1000);
                        h.getLevel().getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, h.getLevel().getServer());
                        readyAt[0] = h.getLevel().getGameTime();
                    }
                    // A newly created entity has not necessarily completed its first physics/navigation tick.
                    if (h.getLevel().getGameTime() - readyAt[0] < 20) return;
                    for (var c : cases) {
                        try { if (!c.priority || c.priorityReadyToFreeze()) c.prepare(); }
                        catch (RuntimeException ex) { c.stopIndependently(ex, true); }
                    }
                    prepared[0] = true; save(h.getLevel()); return;
                }
                for (var c : cases) if (!c.finished) {
                    try {
                        if (c.start < 0) { if (c.priorityReadyToFreeze()) c.prepare(); }
                        else c.tick();
                    }
                    catch (RuntimeException ex) { c.stopIndependently(ex, c.start < 0); }
                }
                if (cases.stream().allMatch(c -> c.finished)) {
                    for (var c : cases) { c.snapshot(); c.event("FINAL_RESULT", c.result()); }
                    save(h.getLevel()); ended[0] = true;
                    Sagadyssey.LOGGER.info("NATURAL_FOOD_FAILURE_DIAGNOSTIC {}", cases.stream().map(Case::result).toList());
                    h.assertTrue(cases.stream().noneMatch(c -> c.defect), "CONFIRMED_COUNTEREXAMPLE: " + cases.stream().map(Case::result).toList());
                    h.assertTrue(cases.stream().allMatch(c -> c.status.equals("PASS")), "DIAGNOSTIC_INCOMPLETE_NOT_PRODUCT_FAILURE: " + cases.stream().map(Case::result).toList());
                    h.succeed();
                }
            } catch (RuntimeException ex) {
                for (var c : cases) { c.event("TEST_STOP", String.valueOf(ex.getMessage())); c.snapshot(); }
                save(h.getLevel()); throw ex;
            }
        });
    }

    private static final class Case {
        final GameTestHelper h; final ServerLevel l; final NaturalVillageBuilding b; final boolean priority;
        final Path log; final Set<String> actions = new HashSet<>(), lifeReceipts = new HashSet<>();
        final Set<BlockPos> mature = new HashSet<>(); final List<BlockPos> cage = new ArrayList<>();
        final long[] ateAt = {-1, -1, -1}; final int[] mealsByActor = {0, 0, 0};
        long start = -1, restoredAt = -1, settledAt = -1, netCrop;
        int harvests, plants, allMeals, bagMeals, firstBagPending = -1, firstBagPhysical = -1;
        int initialStock; BlockState originalRaw; boolean finished, defect; String status = "RUNNING";
        Map<String, Object> frozenResult, frozenSnapshot;
        Case(GameTestHelper h, NaturalVillageBuilding b, boolean priority) {
            this.h = h; l = h.getLevel(); this.b = b; this.priority = priority;
            log = l.getServer().getWorldPath(LevelResource.ROOT).resolve(priority
                    ? "natural-farm-failure-pantry-priority.jsonl" : "natural-farm-failure-bag-cargo.jsonl");
        }
        boolean ready() { var row = NaturalVillageLifeData.get(l.getServer()).village(b.buildingKey()); return row != null && row.getBooleanOr("Ready", false); }
        boolean priorityReadyToFreeze() {
            require(h.getTick() < 1100, "third and final priority setup attempt: original actors must naturally land and have paths before1100; no relocation of farmer/courier");
            var pantry = NaturalVillageLayout.at(b, NaturalVillageLayout.PANTRY);
            boolean allGrounded = true;
            for (int i = 0; i < 3; i++) {
                var n = actor(i); allGrounded &= n != null && n.isAlive() && n.onGround();
            }
            boolean routes = allGrounded && FieldworkRoutes.plan(actor(0), pantry, false) != null
                    && FieldworkRoutes.plan(actor(1), pantry, false) != null;
            if (h.getTick() % 20 == 0 || routes) event("PRE_BOUNDARY_WAIT_FOR_NATURAL_LANDING_AND_PATHS", Map.of(
                    "gameTestTick", h.getTick(), "originalActorsGrounded", allGrounded, "twoActualPaths", routes,
                    "routes", routeEvidence(pantry), "preparationDeadline", 1100, "formalWindowUnchanged", 2400));
            return routes;
        }
        void prepare() {
            var life = NpcLifeData.get(l.getServer());
            for (int i = 0; i < 3; i++) {
                var n = actor(i); require(n != null && n.isAlive() && !n.isNoAi()
                        && n.getCharacterId().equals(NaturalVillageLifeData.person(b, i)), "original living AI actor");
                require(life.needs(n.getCharacterId()).lastAteAt() < 0, "no earlier meal hidden by setup");
                ateAt[i] = life.needs(n.getCharacterId()).lastAteAt();
            }
            require(count(actor(0).getPersonalInventory()) == 12 && count(store(NaturalVillageLayout.PANTRY)) == 4 && stock() == 16,
                    "original mobile12+4 before finite initial edits");
            require(FieldworkData.get(l.getServer()).receiptsFor(actor(0).getUUID()).isEmpty(), "no fabricated or previous crop receipts");
            for (var local : NaturalVillageLayout.crops()) {
                var at = NaturalVillageLayout.at(b, local); require(l.getBlockState(at).is(Blocks.CARROTS), "original carrot plot");
                l.setBlockAndUpdate(at, Blocks.CARROTS.defaultBlockState());
            }
            if (priority) {
                life.setHunger(actor(2).getCharacterId(), 1000, l.getGameTime());
                life.setHunger(actor(0).getCharacterId(), 800, l.getGameTime());
                life.setHunger(actor(1).getCharacterId(), 800, l.getGameTime());
                var pantry = NaturalVillageLayout.at(b, NaturalVillageLayout.PANTRY);
                event("ROUTES_BEFORE_CONTROLLED_CAGE", routeEvidence(pantry));
                var center = NaturalVillageLayout.at(b, new BlockPos(6, 1, 9));
                require(FieldworkRoutes.walkable(actor(2), center), "declared original-village empty cage center has a real floor");
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                    for (int dy = 0; dy <= 2; dy++) require(l.getBlockState(center.offset(dx, dy, dz)).isAir(),
                            "declared cage avoids existing facilities and corridors");
                var before = actor(2).position();
                actor(2).getNavigation().stop(); actor(2).setPos(center.getX() + .5, center.getY(), center.getZ() + .5);
                event("CONTROLLED_INITIAL_ORIGINAL_WORKER_LOCATION", Map.of("body", actor(2).getUUID(), "person", actor(2).getCharacterId(),
                        "before", before.toString(), "after", actor(2).position().toString(), "templateLocal", "6,1,9", "noLaterMovementIntervention", true));
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                    for (int dy = 0; dy <= 2; dy++) if (dy == 2 || Math.abs(dx) == 1 || Math.abs(dz) == 1) {
                        var at = center.offset(dx, dy, dz); l.setBlockAndUpdate(at, Blocks.GLASS.defaultBlockState()); cage.add(at);
                    }
                event("ROUTES_AFTER_CONTROLLED_CAGE", routeEvidence(pantry));
                require(FieldworkRoutes.plan(actor(2), pantry, false) == null, "highest hunger original is actually unable to reach pantry");
                require(FieldworkRoutes.plan(actor(0), pantry, false) != null && FieldworkRoutes.plan(actor(1), pantry, false) != null,
                        "other original residents have actual local paths to pantry");
                require(legalPantry(), "four carrots remain legally consumable by the household");
            } else {
                removeCarrots(store(NaturalVillageLayout.PANTRY));
                var raw = store(NaturalVillageLayout.RAW); require(raw != null && raw.isEmpty(), "temporarily removed RAW container starts entirely empty");
                var rawAt = NaturalVillageLayout.at(b, NaturalVillageLayout.RAW); originalRaw = l.getBlockState(rawAt);
                l.setBlockAndUpdate(rawAt, Blocks.AIR.defaultBlockState());
                for (int i = 0; i < 3; i++) {
                    var at = NaturalVillageLayout.at(b, NaturalVillageLayout.crops().get(i));
                    l.setBlockAndUpdate(at, Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, 7)); mature.add(at);
                }
                life.setHunger(actor(0).getCharacterId(), 800, l.getGameTime());
                require(count(actor(0).getPersonalInventory()) == 12, "original protected seed bag was not changed");
                require(NaturalFarmRules.foodSlot(actor(0), actor(0).getPersonalInventory(), "BAG", actor(0).blockPosition(), true, l.getGameTime()) < 0,
                        "no read-only emergency permission before a real harvest");
            }
            start = l.getGameTime(); initialStock = stock();
            var setup = new LinkedHashMap<String, Object>(); setup.put("case", name()); setup.put("initialCarrots", initialStock);
            setup.put("seedBag", 12); setup.put("pantry", priority ? 4 : 0); setup.put("dayTime", l.getDayTime()); setup.put("randomTickSpeed", 0);
            setup.put("minimumPreBoundaryPhysicsTicks", 20); setup.put("formalStartGameTestTick", h.getTick());
            if (priority) setup.put("additionalPreBoundaryCondition", "all original actors naturally onGround and farmer/courier real routes exist before1100 test ticks; third and final setup attempt");
            setup.put("cage", cage.stream().map(BlockPos::toShortString).toList());
            setup.put("matureCells", mature.stream().map(BlockPos::toShortString).toList());
            setup.put("rawTemporaryAbsence", !priority); setup.put("originalRaw", String.valueOf(originalRaw));
            setup.put("initialHunger", java.util.stream.IntStream.range(0, 3)
                    .map(i -> life.needs(actor(i).getCharacterId()).hunger()).boxed().toList());
            setup.put("budget", priority ? "2400 normal ticks" : "2400 ticks to observe real BAG; then2400 ticks after restoring original RAW");
            setup.put("permittedLaterIntervention", priority ? "none" : "restore original empty RAW block once, only after real BAG consumption");
            event("CONTROLLED_INITIAL_BOUNDARY", setup); snapshot();
        }
        void tick() {
            observeWork(); observeMeals();
            require(initialStock + netCrop == stock() + allMeals, "per-item balance initial+realNetCrop=physical+realMeals");
            long now = l.getGameTime(); if ((now - start) % 100 == 0) snapshot();
            if (priority) {
                require(count(actor(0).getPersonalInventory()) == 12 && harvests == 0 && plants == 0, "no seed loss or undeclared food source");
                if (now - start < 2400) return;
                boolean othersAte = mealsByActor[0] > 0 && mealsByActor[1] > 0;
                boolean pathAndFoodRemain = legalPantry() && count(store(NaturalVillageLayout.PANTRY)) >= 2
                        && FieldworkRoutes.plan(actor(0), NaturalVillageLayout.at(b, NaturalVillageLayout.PANTRY), false) != null
                        && FieldworkRoutes.plan(actor(1), NaturalVillageLayout.at(b, NaturalVillageLayout.PANTRY), false) != null;
                if (!othersAte && pathAndFoodRemain && !NaturalFarmRules.pantryTurn(actor(0)) && !NaturalFarmRules.pantryTurn(actor(1))) {
                    defect = true; status = "FAIL_REACHABLE_MEMBERS_STARVED_BY_UNAVAILABLE_PRIORITY";
                } else status = othersAte ? "PASS" : "BLOCKED_PRECONDITION_CHANGED";
                finished = true; event("PRIORITY_WINDOW_COMPLETE", result()); snapshot(); save(l); return;
            }
            if (restoredAt < 0) {
                if (bagMeals > 0) {
                    require(harvests > 0 && plants > 0, "BAG evidence follows actual harvest and replant");
                    verifyActualConsumption();
                    firstBagPending = pending(); firstBagPhysical = count(actor(0).getPersonalInventory());
                    event("ACTUAL_BAG_BEFORE_RESTORE", Map.of("bagMeals", bagMeals, "actualNetHarvest", netCrop,
                            "pendingCargo", firstBagPending, "bagPhysical", firstBagPhysical, "rawAbsent", store(NaturalVillageLayout.RAW) == null));
                    l.setBlockAndUpdate(NaturalVillageLayout.at(b, NaturalVillageLayout.RAW), originalRaw);
                    require(store(NaturalVillageLayout.RAW) != null && store(NaturalVillageLayout.RAW).isEmpty(), "only original empty RAW availability restored");
                    restoredAt = now; event("DECLARED_RAW_RESTORATION", Map.of("at", now, "stockAdded", 0, "rawState", originalRaw.toString())); snapshot(); save(l);
                } else if (now - start >= 2400) {
                    status = "BLOCKED_BAG_BRANCH_NOT_OBSERVED"; finished = true;
                    event("NO_BAG_EVIDENCE_NOT_PRODUCT_FAILURE", result()); snapshot(); save(l);
                }
                return;
            }
            boolean settled = harvests == 3 && plants == 3 && count(actor(0).getPersonalInventory()) == 12
                    && !FieldworkDepositTaskExecutor.hasDeliverableCargo(actor(0), FieldworkData.get(l.getServer()))
                    && FieldworkData.get(l.getServer()).pendingDelivery(actor(0).getUUID()).isEmpty();
            if (settled) {
                if (settledAt < 0) settledAt = now;
                if (now - settledAt >= 200) {
                    verifyActualConsumption();
                    int transferred = recordedDeposits();
                    long actualUndelivered = netCrop - transferred - bagMeals;
                    require(actualUndelivered == 0, "exact production equals actual deposit plus actual BAG consumption, without clamping an over-recorded debit");
                    long tailDifference = pending() - actualUndelivered;
                    require(initialStock == 12 && actualUndelivered == 0, "only original12 seed left after actual output consumption and deposit");
                    require(transferred > 0, "restored RAW receives actual normal deposit, not a manually filled result");
                    defect = tailDifference > 0;
                    status = defect ? "FAIL_CONSUMED_OUTPUT_REMAINS_PENDING" : "PASS";
                    event("SETTLED_OUTPUT_ACCOUNT", Map.of("actualNetHarvest", netCrop, "recordedRealDeposits", transferred,
                            "actualBagConsumption", bagMeals, "physicalBag", count(actor(0).getPersonalInventory()),
                            "expectedUndeliveredOutput", actualUndelivered, "recordedPending", pending(), "tailDifference", tailDifference,
                            "matchesBagConsumption", tailDifference == bagMeals));
                    finished = true; snapshot(); save(l); return;
                }
            } else settledAt = -1;
            if (now - restoredAt >= 2400) {
                status = "BLOCKED_NORMAL_DEPOSIT_NOT_SETTLED"; finished = true; event("RESTORE_WINDOW_ENDED", result()); snapshot(); save(l);
            }
        }
        void observeWork() {
            for (var r : FieldworkData.get(l.getServer()).receiptsFor(actor(0).getUUID())) {
                if (!actions.add(r.action().key())) continue;
                require(r.committedAt() >= start, "only genuine post-boundary crop receipts");
                int delta = r.inventoryDeltas().stream().filter(d -> d.fingerprint().equals("minecraft:carrot#0")).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();
                netCrop += delta;
                if (r.action().operation() == FieldworkAction.Operation.HARVEST) { require(mature.contains(r.action().target()), "finite declared crop source"); harvests++; }
                if (r.action().operation() == FieldworkAction.Operation.PLANT) { require(delta == -1, "actual one-carrot replant"); plants++; }
                event("REAL_FIELDWORK", Map.of("key", r.action().key(), "operation", r.action().operation(), "time", r.committedAt(),
                        "plot", r.action().target().toShortString(), "delta", delta, "after", r.resultingState()));
            }
        }
        void stopIndependently(RuntimeException ex, boolean preparation) {
            finished = true; status = preparation ? "BLOCKED_PRECONDITION" : "FAIL_STATE_INVARIANT";
            event("BRANCH_STOP_OTHER_BRANCH_CONTINUES", Map.of("status", status, "reason", String.valueOf(ex.getMessage())));
            snapshot(); save(l);
        }
        List<Map<String, Object>> routeEvidence(BlockPos pantry) {
            var rows = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < 3; i++) {
                var n = actor(i);
                if (n == null) { rows.add(Map.of("index", i, "missing", true)); continue; }
                var route = FieldworkRoutes.plan(n, pantry, false);
                rows.add(Map.of("index", i, "body", n.getUUID(), "position", n.position().toString(),
                        "onGround", n.onGround(), "pantry", pantry.toShortString(), "route", route == null ? "NONE" : route.toString()));
            }
            return rows;
        }
        void observeMeals() {
            for (int i = 0; i < 3; i++) {
                var n = actor(i); require(n != null && n.isAlive() && n.getCharacterId().equals(NaturalVillageLifeData.person(b, i)), "original person persists");
                var need = NpcLifeData.get(l.getServer()).needs(n.getCharacterId());
                if (need.lastAteAt() > ateAt[i]) {
                    ateAt[i] = need.lastAteAt(); mealsByActor[i]++; allMeals++;
                    event("REAL_MEAL", Map.of("index", i, "person", n.getCharacterId(), "at", need.lastAteAt(), "hunger", need.hunger()));
                }
                var brain = NpcBrainRuntime.data(l.getServer()); var plans = new ArrayList<>(brain.history(n.getUUID())); brain.active(n.getUUID()).ifPresent(plans::add);
                for (var p : plans) if (p.createdAt() >= start && p.providerId().equals(LifePlanFactory.PROVIDER)) for (var step : p.steps()) {
                    String key = p.id() + ":" + step.index();
                    if (!"DONE".equals(LifeActivityData.get(l.getServer()).settlement(key)) || !lifeReceipts.add(key)) continue;
                    String op = step.params().getOrDefault("operation", ""), source = step.params().getOrDefault("source", "");
                    if (i == 0 && op.equals("EAT") && source.equals("BAG")) bagMeals++;
                    event("NORMAL_LIFE_RECEIPT", Map.of("index", i, "key", key, "operation", op, "source", source));
                }
            }
        }
        int pending() { return FieldworkData.get(l.getServer()).pendingCargo(actor(0).getUUID()).getOrDefault("minecraft:carrot#0", 0); }
        void verifyActualConsumption() {
            var data = FieldworkData.get(l.getServer());
            var consumed = data.outputConsumptions(actor(0).getUUID());
            require(data.save(new CompoundTag(), l.registryAccess()).getIntOr("Schema", 0) == 2,
                    "actual consumption persists in Schema2 rather than a fake delivery");
            require(consumed.size() == bagMeals, "one durable event per actual normal BAG meal");
            int actual = 0, output = 0;
            for (var event : consumed) {
                require(event.state() == FieldworkCargo.ConsumptionState.COMMITTED,
                        "normal successful consumption has a committed output event");
                require(event.personId().equals(actor(0).getCharacterId())
                        && event.containerId().equals(ContainerIds.npcPersonal(actor(0).getCharacterId()))
                        && event.owner().equals(SubjectRef.character(actor(0).getCharacterId())),
                        "consumption retains original person, bag and beneficial owner");
                require(event.fingerprint().equals("minecraft:carrot#0") && event.startedAt() >= start
                        && event.finalRevision() == event.beforeRevision() + 1
                        && "DONE".equals(LifeActivityData.get(l.getServer()).settlement(event.key()))
                        && lifeReceipts.contains(event.key()), "consumption matches actual normal life action and container revision");
                actual += event.quantity(); output += event.outputQuantity();
            }
            require(actual == bagMeals && output == bagMeals,
                    "actual consumption and its own output share match, without consuming the original seed allocation");
            event("COMMITTED_OUTPUT_CONSUMPTION_ACCOUNT", Map.of("actual", actual, "ownOutput", output,
                    "records", consumed.stream().map(Object::toString).toList(), "bagMeals", bagMeals,
                    "schema", 2, "pendingOutput", pending()));
        }
        int recordedDeposits() {
            var encoded = FieldworkData.get(l.getServer()).save(new CompoundTag(), l.registryAccess()); int quantity = 0;
            for (var item : MinecraftApi.listOfType(encoded, "Deliveries", Tag.TAG_COMPOUND)) {
                var row = (CompoundTag) item;
                if (row.read("NpcId", UUIDUtil.CODEC).orElseThrow().equals(actor(0).getUUID()) && row.getStringOr("Fingerprint", "").equals("minecraft:carrot#0")) {
                    var receipt = TransferLedgerData.get(l.getServer()).receipt(row.read("TransferId", UUIDUtil.CODEC).orElseThrow());
                    require(receipt != null && receipt.successful() && receipt.lawful(), "actual output deposit has lawful transfer receipt");
                    quantity += row.getIntOr("Quantity", 0);
                }
            }
            return quantity;
        }
        boolean legalPantry() {
            var home = SubjectRef.household(NaturalVillageLifeData.home(b));
            var id = ContainerIds.block("household_storage", b.dimension(), NaturalVillageLayout.at(b, NaturalVillageLayout.PANTRY));
            var box = store(NaturalVillageLayout.PANTRY); if (box == null) return false;
            var service = new RightsService(RightsStateData.get(l.getServer()));
            for (int slot = 0; slot < box.getContainerSize(); slot++) if (box.getItem(slot).is(Items.CARROT)
                    && service.can(home, AssetRight.CONSUME, id, slot, ItemFingerprint.of(box.getItem(slot)), l.getGameTime()).status() == RightsVerdict.Status.ALLOW) return true;
            return false;
        }
        NpcBase actor(int i) { return NaturalVillageR1GameTests.actor(l, b, i); }
        Container store(BlockPos local) { var block = l.getBlockEntity(NaturalVillageLayout.at(b, local)); return block instanceof Container c ? c : null; }
        int stock() {
            int n = 0; for (var at : NaturalVillageLayout.stores(b)) n += count(store(at));
            for (int i = 0; i < 3; i++) { var actor = actor(i); if (actor != null) n += count(actor.getPersonalInventory()) + count(actor.getTransportInventory()); }
            for (var item : l.getEntitiesOfClass(ItemEntity.class, new AABB(b.buildingOrigin()).inflate(48), e -> e.getItem().is(Items.CARROT))) n += item.getItem().getCount();
            return n;
        }
        String name() { return priority ? "unavailable_priority" : "real_bag_output_account"; }
        Map<String, Object> result() {
            if(frozenResult!=null)return frozenResult;
            var row = new LinkedHashMap<String, Object>(); row.put("case", name()); row.put("status", status); row.put("productDefect", defect);
            row.put("observedThroughTick", l.getGameTime());
            row.put("elapsed", start < 0 ? -1 : l.getGameTime() - start); row.put("restoredAt", restoredAt);
            row.put("harvests", harvests); row.put("plants", plants); row.put("netCrop", netCrop); row.put("meals", Arrays.toString(mealsByActor));
            row.put("actualBagMeals", bagMeals); row.put("pendingOutput", actor(0) == null ? -1 : pending());
            row.put("physicalCarrots", stock()); return row;
        }
        void snapshot() {
            if(frozenSnapshot!=null){event("CUTOFF_SNAPSHOT_REPLAY_NOT_NEW_OBSERVATION",frozenSnapshot);return;}
            var people = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < 3; i++) {
                var n = actor(i); if (n == null) continue;
                people.add(Map.of("index", i, "body", n.getUUID(), "person", n.getCharacterId(), "position", n.position().toString(),
                        "bag", count(n.getPersonalInventory()), "cargo", count(n.getTransportInventory()), "needs", String.valueOf(NpcLifeData.get(l.getServer()).needs(n.getCharacterId())),
                        "active", NpcBrainRuntime.data(l.getServer()).active(n.getUUID()).map(Object::toString).orElse("NONE")));
            }
            var stores = new LinkedHashMap<String, Object>(); for (var at : NaturalVillageLayout.stores(b)) stores.put(at.toShortString(), store(at) == null ? "MISSING" : count(store(at)));
            var snapshot = Map.<String,Object>of("result", result(), "people", people, "stores", stores,
                    "rights", RightsStateData.get(l.getServer()).save(new CompoundTag(), l.registryAccess()).toString(),
                    "fieldwork", FieldworkData.get(l.getServer()).save(new CompoundTag(), l.registryAccess()).toString(),
                    "transfers", TransferLedgerData.get(l.getServer()).save(new CompoundTag(), l.registryAccess()).toString());
            event("SNAPSHOT", snapshot);
            if(finished){frozenResult=Map.copyOf(result());frozenSnapshot=snapshot;}
        }
        void event(String kind, Object detail) {
            var row = new LinkedHashMap<String, Object>(); row.put("tick", l.getGameTime()); row.put("case", name()); row.put("event", kind); row.put("detail", detail);
            try { Files.writeString(log, new Gson().toJson(row) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (Exception ex) { throw new IllegalStateException("cannot retain diagnostic evidence", ex); }
        }
        void require(boolean value, String reason) { h.assertTrue(value, name() + ": " + reason); }
    }
    private static int count(Container c) { int n = 0; if (c != null) for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.CARROT)) n += c.getItem(i).getCount(); return n; }
    private static void removeCarrots(Container c) { for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.CARROT)) c.removeItemNoUpdate(i); c.setChanged(); }
    private static void save(ServerLevel l) {
        try { l.getServer().saveEverything(true, true, true);  }
        catch (Exception ex) { throw new IllegalStateException("cannot preserve diagnostic world", ex); }
    }
}
