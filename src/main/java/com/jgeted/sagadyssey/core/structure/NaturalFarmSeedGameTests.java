package com.jgeted.sagadyssey.core.structure;

import com.google.gson.Gson;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.*;
import java.util.*;

/** Three controlled fixtures. Seed pickup cases do not claim natural cold-start or transport competition. */
public final class NaturalFarmSeedGameTests {
    private static final ItemFingerprint CARROT = new ItemFingerprint("minecraft:carrot", 0);
    private NaturalFarmSeedGameTests() { }

    public static Collection<TestFunction> tests() {
        return List.of(test("v3_initial_grants_once", NaturalFarmSeedGameTests::initialization, 1200),
                test("v3_zero_seed_from_original_raw", h -> seed(h, false), 4800),
                test("v3_reserved_raw_and_ungranted_pantry", h -> seed(h, true), 4800));
    }

    private static TestFunction test(String name, java.util.function.Consumer<GameTestHelper> action, int limit) {
        return new TestFunction("natural_farm_seed", name, "sagadyssey_natural_life:empty", limit, 0, true, action);
    }

    private static void initialization(GameTestHelper h) {
        var b = NaturalVillageR1GameTests.fixture(h, 0, NaturalVillageLayout.FARM_V3);
        long[] began = {-1}, initialized = {-1};
        var log = new Evidence(h, "initialization");
        h.onEachTick(() -> {
            var server = h.getLevel().getServer(); var life = NaturalVillageLifeData.get(server);
            var row = life.village(b.buildingKey());
            if (row == null || !row.getBoolean("Ready")) return;
            if (began[0] < 0) {
                verifyOriginal(h, b);
                for (int i = 0; i < 3; i++) actor(h, b, i).setCommand(NpcCommand.STAY);
                check(h, count(actor(h, b, 0).getPersonalInventory()) == 12 && count(store(h, b, NaturalVillageLayout.PANTRY)) == 4,
                        "actual v3 initialization gives twelve personal seed carrots and four household carrots");
                check(h, total(h, b) == 16, "no extra initialized carrot stock");
                began[0] = h.getLevel().getGameTime(); initialized[0] = row.getLong("Initialized");
                log.write("FORMAL_INITIALIZATION", Map.of("state", snapshot(h, b), "intervention", "all three original residents STAY after initialization; no inventory grant or reset"));
            }
            // A repeated registration intent never repeats physical template placement.
            life.schedule(b);
            verifyOriginal(h, b);
            check(h, life.village(b.buildingKey()).getLong("Initialized") == initialized[0], "same completed initialization intent");
            check(h, count(actor(h, b, 0).getPersonalInventory()) == 12 && count(store(h, b, NaturalVillageLayout.PANTRY)) == 4
                    && total(h, b) == 16, "repeated schedule does not give food or seeds again");
            if (h.getLevel().getGameTime() - began[0] >= 600) {
                check(h, life.villages().stream().filter(v -> NaturalVillageLifeData.decode(v).buildingKey().equals(b.buildingKey())).count() == 1,
                        "one original village intent after six hundred repeated callbacks");
                log.write("PASS", snapshot(h, b));
                Sagadyssey.LOGGER.info("NATURAL_FARM_SEED_INITIAL_PASS building={} farmerSeeds=12 pantry=4 repeatedSchedules=601 samePeople=true", b.buildingKey());
                h.succeed();
            }
        });
    }

    private static void seed(GameTestHelper h, boolean occupied) {
        var b = NaturalVillageR1GameTests.fixture(h, occupied ? 512 : 256, NaturalVillageLayout.FARM_V3);
        var log = new Evidence(h, occupied ? "reserved_raw_ungranted_pantry" : "raw_pickup");
        long[] ready = {-1}, began = {-1}; boolean[] released = {false}, waitSeen = {false};
        String token = "natural-farm-seed-test-hold:" + b.buildingKey();
        var field = NaturalVillageLayout.at(b, NaturalVillageLayout.crops().getFirst());
        h.onEachTick(() -> {
            var l = h.getLevel(); var server = l.getServer(); long now = l.getGameTime();
            var row = NaturalVillageLifeData.get(server).village(b.buildingKey());
            if (row == null || !row.getBoolean("Ready")) return;
            if (ready[0] < 0) {
                verifyOriginal(h, b);
                check(h, total(h, b) == 16 && count(actor(h, b, 0).getPersonalInventory()) == 12
                        && count(store(h, b, NaturalVillageLayout.PANTRY)) == 4, "observe actual versioned grants before controlled configuration");
                for (int i = 0; i < 3; i++) actor(h, b, i).setCommand(NpcCommand.STAY);
                ready[0] = now;
            }
            // Let the ordinary command path settle pre-existing work before the initial test boundary.
            if (began[0] < 0 && now - ready[0] < 60) return;
            var farmer = actor(h, b, 0); var rights = RightsStateData.get(server);
            UUID rawId = ContainerIds.block("private_storage", b.dimension(), NaturalVillageLayout.at(b, NaturalVillageLayout.RAW));
            UUID pantryId = ContainerIds.block("household_storage", b.dimension(), NaturalVillageLayout.at(b, NaturalVillageLayout.PANTRY));
            var raw = store(h, b, NaturalVillageLayout.RAW); var pantry = store(h, b, NaturalVillageLayout.PANTRY);
            if (began[0] < 0) {
                check(h, FieldworkData.get(server).receiptsFor(farmer.getUUID()).isEmpty() && count(raw) == 0,
                        "no production or source transfer before controlled setup");
                check(h, farmer.getTransportInventory().isEmpty(), "no old carried inventory in the test farmer");
                farmer.getPersonalInventory().clearContent();
                raw.setItem(0, new ItemStack(Items.CARROT, 1)); raw.setChanged();
                pantry.setItem(0, new ItemStack(Items.CARROT, 16)); pantry.setChanged();
                check(h, l.getBlockState(field).is(Blocks.CARROTS) && l.getBlockState(field.below()).is(Blocks.FARMLAND),
                        "the sowing target is an original registered farmland cell");
                l.setBlockAndUpdate(field, Blocks.AIR.defaultBlockState());
                farmer.setWorkZone(field, field);
                check(h, NaturalFarmRules.isFarmer(farmer), "original v3 farmer remains in its original household and role");
                verifyPantryDenied(h, b, farmer, pantryId);
                if (occupied) check(h, rights.reserve(new AssetReservation(ContainerIds.named(token), rawId, CARROT, 1,
                                SubjectRef.character(farmer.getCharacterId()), token, "controlled prior obligation, released at elapsed 800", now, now + 6000), 1, now),
                        "finite original raw carrot has one real prior reservation");
                began[0] = now; farmer.setCommand(NpcCommand.WORK);
                var setup = new LinkedHashMap<String, Object>();
                setup.put("state", snapshot(h, b)); setup.put("field", field.toShortString());
                setup.put("setupRemovedPersonalCarrots", 12); setup.put("setupAddedRawCarrots", 1); setup.put("setupAddedPantryCarrots", 12);
                setup.put("initialStockAfterSetup", 17); setup.put("initialCrops", "eleven original seedlings; one original farmland cell cleared before start");
                setup.put("workArea", "one original registered plot only"); setup.put("otherTwoResidents", "STAY; normal life maintenance retained");
                setup.put("reservation", occupied ? token : "NONE"); setup.put("allowedAfterStart", occupied ? "release this same prior reservation at elapsed 800 only" : "none");
                setup.put("scope", "controlled original-worker seed recovery; not cold start, competition, or a natural pantry-grant proof");
                log.write("FROZEN_INITIAL_STATE", setup);
                return;
            }
            long elapsed = now - began[0]; verifyOriginal(h, b);
            verifyPantryDenied(h, b, farmer, pantryId);
            var plans = plans(h, farmer);
            if (plans.stream().anyMatch(p -> p.steps().stream().anyMatch(s -> s.params().getOrDefault("reason", "").contains("种用胡萝卜")))) waitSeen[0] = true;
            if (elapsed % 100 == 0) log.write("OBSERVE", Map.of("elapsed", elapsed, "state", snapshot(h, b),
                    "field", l.getBlockState(field).toString(), "seedPickupAudits", pickups(h, farmer).toString(), "waitSeen", waitSeen[0]));
            if (occupied && !released[0]) {
                check(h, l.getBlockState(field).isAir() && count(raw) == 1 && count(farmer.getPersonalInventory()) == 0
                        && count(pantry) == 16 && pickups(h, farmer).isEmpty(), "occupied RAW and ungranted PANTRY produce no unauthorized withdrawal or sowing");
                check(h, rights.reservedQuantity(rawId, CARROT, null, now) == 1, "the original occupied carrot remains reserved");
                if (elapsed < 800) return;
                check(h, waitSeen[0], "normal seed discovery actually reached the missing authorized seed wait");
                log.write("BLOCKED_BEFORE_RELEASE", snapshot(h, b));
                rights.releaseReservations(token); released[0] = true;
                log.write("ORIGINAL_RESERVATION_RELEASED", Map.of("token", token, "rawCarrots", count(raw), "pantryCarrots", count(pantry),
                        "remainingReservation", rights.reservedQuantity(rawId, CARROT, null, now), "itemsAdded", 0));
                return;
            }
            var planted = FieldworkData.get(server).receiptsFor(farmer.getUUID()).stream()
                    .filter(r -> r.action().operation() == FieldworkAction.Operation.PLANT && r.action().target().equals(field)).toList();
            if (planted.isEmpty()) return;
            check(h, planted.size() == 1 && planted.getFirst().inventoryDeltas().size() == 1
                    && planted.getFirst().inventoryDeltas().getFirst().fingerprint().startsWith("minecraft:carrot#")
                    && planted.getFirst().inventoryDeltas().getFirst().quantity() == -1, "normal real planting consumed exactly one carrot");
            check(h, l.getBlockState(field).is(Blocks.CARROTS) && count(raw) == 0 && count(farmer.getPersonalInventory()) == 0
                    && count(pantry) == 16 && total(h, b) == 16, "initial seventeen stock becomes sixteen plus one actually planted seed");
            check(h, FieldworkData.get(server).receiptsFor(farmer.getUUID()).stream().noneMatch(r -> r.action().operation() == FieldworkAction.Operation.HARVEST),
                    "recovery did not substitute newly harvested seeds for the frozen source");
            var audits = pickups(h, farmer); check(h, audits.size() == 1 && audits.getFirst().lawful(), "exactly one lawful real seed transfer");
            var transfer = audits.getFirst().transferId();
            var seedPlan = plans.stream().filter(p -> p.id().equals(transfer)).findFirst().orElseThrow();
            check(h, seedPlan.steps().getFirst().params().get("seed_source").equals(rawId.toString()), "normal plan froze the original RAW source");
            var savedLedger = TransferLedgerData.get(server).save(new CompoundTag(), server.registryAccess());
            CompoundTag savedReceipt = null;
            for (var entry : savedLedger.getList("Receipts", Tag.TAG_COMPOUND)) if (((CompoundTag) entry).getUUID("Id").equals(transfer)) savedReceipt = (CompoundTag) entry;
            check(h, savedReceipt != null && savedReceipt.contains("RequestIdentity"), "real transfer has durable request identity");
            var legs = savedReceipt.getCompound("RequestIdentity").getList("Legs", Tag.TAG_COMPOUND);
            check(h, legs.size() == 1 && legs.getCompound(0).getUUID("Source").equals(rawId)
                    && legs.getCompound(0).getUUID("Destination").equals(ContainerIds.npcPersonal(farmer.getCharacterId()))
                    && legs.getCompound(0).getInt("Quantity") == 1 && legs.getCompound(0).getCompound("Item").getString("Item").equals("minecraft:carrot"),
                    "receipt proves one carrot from original raw into original farmer inventory");
            log.write("PASS", Map.of("elapsed", elapsed, "state", snapshot(h, b), "transfer", savedReceipt.toString(),
                    "plan", seedPlan.toString(), "plantReceipt", planted.getFirst().toString(), "releasedPriorReservation", released[0]));
            Sagadyssey.LOGGER.info("NATURAL_FARM_SEED_PASS occupiedCase={} elapsed={} person={} source={} pickup={} planted=1 raw=0 pantry=16 seedBag=0", occupied, elapsed, farmer.getCharacterId(), rawId, transfer);
            h.succeed();
        });
    }

    private static void verifyPantryDenied(GameTestHelper h, NaturalVillageBuilding b, NpcBase farmer, UUID pantryId) {
        var rights = new RightsService(RightsStateData.get(h.getLevel().getServer()));
        var actor = SubjectRef.character(farmer.getCharacterId()); long now = h.getLevel().getGameTime();
        for (var right : List.of(AssetRight.ACCESS, AssetRight.CONSUME, AssetRight.TRANSFER))
            check(h, rights.can(actor, right, pantryId, 0, CARROT, now).status() != RightsVerdict.Status.ALLOW,
                    "natural household ownership alone is not a personal farmer seed grant: " + right);
        check(h, SupplyFlowData.get(h.getLevel().getServer()).known(SubjectRef.household(NaturalVillageLifeData.home(b))).contains(pantryId),
                "pantry is known but has no personal withdrawal grant; not confused with unknown source");
    }

    private static List<TransferAudit> pickups(GameTestHelper h, NpcBase farmer) {
        return TransferLedgerData.get(h.getLevel().getServer()).audits().stream()
                .filter(a -> a.authority().equals("farmer_seed:" + farmer.getCharacterId()) && a.status() == TransferReceipt.Status.SUCCESS).toList();
    }
    private static List<NpcPlan> plans(GameTestHelper h, NpcBase npc) {
        var brain = NpcBrainRuntime.data(h.getLevel().getServer()); var result = new ArrayList<>(brain.history(npc.getUUID()));
        brain.active(npc.getUUID()).ifPresent(result::add); return result;
    }
    private static void verifyOriginal(GameTestHelper h, NaturalVillageBuilding b) {
        var home = HouseholdData.get(h.getLevel().getServer()).byId(NaturalVillageLifeData.home(b));
        check(h, home != null && b.settlementId().equals(home.settlementId()) && home.members().size() == 3, "one original three-person household");
        for (int i = 0; i < 3; i++) {
            var npc = actor(h, b, i);
            check(h, npc != null && npc.isAlive() && npc.getUUID().equals(NaturalVillageLifeData.body(b, i))
                    && npc.getCharacterId().equals(NaturalVillageLifeData.person(b, i)) && home.contains(npc.getCharacterId()), "same original resident identity " + i);
        }
    }
    private static NpcBase actor(GameTestHelper h, NaturalVillageBuilding b, int index) { return NaturalVillageR1GameTests.actor(h.getLevel(), b, index); }
    private static Container store(GameTestHelper h, NaturalVillageBuilding b, BlockPos relative) { return (Container) h.getLevel().getBlockEntity(NaturalVillageLayout.at(b, relative)); }
    private static int count(Container inventory) {
        int result = 0; for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i).is(Items.CARROT)) result += inventory.getItem(i).getCount(); return result;
    }
    private static int total(GameTestHelper h, NaturalVillageBuilding b) {
        int result = 0; for (var p : NaturalVillageLayout.stores(b)) result += count(store(h, b, p));
        for (int i = 0; i < 3; i++) { result += count(actor(h, b, i).getPersonalInventory()); result += count(actor(h, b, i).getTransportInventory()); }
        return result;
    }
    private static Map<String, Object> snapshot(GameTestHelper h, NaturalVillageBuilding b) {
        var result = new LinkedHashMap<String, Object>(); result.put("building", b.buildingKey()); result.put("settlement", b.settlementId());
        result.put("household", NaturalVillageLifeData.home(b)); result.put("totalStoredCarrots", total(h, b));
        var stores = new LinkedHashMap<String, Integer>(); for (var p : NaturalVillageLayout.stores(b)) stores.put(p.toShortString(), count(store(h, b, p))); result.put("stores", stores);
        var people = new ArrayList<Object>();
        for (int i = 0; i < 3; i++) { var npc = actor(h, b, i); var life = NpcLifeData.get(h.getLevel().getServer());
            people.add(Map.of("person", npc.getCharacterId(), "body", npc.getUUID(), "position", npc.blockPosition().toShortString(),
                    "personalCarrots", count(npc.getPersonalInventory()), "cargoCarrots", count(npc.getTransportInventory()),
                    "command", npc.getCommand().name(), "needs", Objects.toString(life.needs(npc.getCharacterId())),
                    "plan", Objects.toString(NpcBrainRuntime.data(h.getLevel().getServer()).active(npc.getUUID()).orElse(null)))); }
        result.put("people", people); return result;
    }
    private record Evidence(GameTestHelper helper, String name) {
        void write(String event, Object detail) {
            var path = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("natural-farm-seed-" + name + ".jsonl");
            try { Files.writeString(path, new Gson().toJson(Map.of("tick", helper.getLevel().getGameTime(), "case", name, "event", event, "detail", detail)) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (Exception ex) { throw new IllegalStateException("cannot retain seed case evidence", ex); }
        }
    }
    private static void check(GameTestHelper h, boolean condition, String reason) { h.assertTrue(condition, reason); }
}
