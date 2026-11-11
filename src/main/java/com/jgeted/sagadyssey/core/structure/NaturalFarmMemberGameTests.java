package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import net.minecraft.core.UUIDUtil;

import com.google.gson.Gson;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.life.LifePlanFactory;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.social.character.CharacterLifecycleEvents;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.npc.brain.life.LifeActivityData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.nio.file.*;
import java.util.*;

/** Controlled payment cases; fixture construction and initial finite grants are not natural-play evidence. */
public final class NaturalFarmMemberGameTests {
    private NaturalFarmMemberGameTests() { }

    public static Collection<TestFunction> tests() {
        return List.of(new TestFunction("natural_farm_member", "bounded_payment_and_shared_pantry",
                "sagadyssey_natural_life:empty", 4800, 0, true, NaturalFarmMemberGameTests::payments));
    }

    private static void payments(GameTestHelper h) {
        var buildings = List.of(
                NaturalVillageR1GameTests.fixture(h, 0, NaturalVillageLayout.FARM_V3),
                NaturalVillageR1GameTests.fixture(h, 256, NaturalVillageLayout.FARM_V3),
                NaturalVillageR1GameTests.fixture(h, 512, NaturalVillageLayout.FARM_V3));
        var cases = new ArrayList<Case>();
        long[] began = {-1};
        boolean[] rejectedAfterMeal = {false}, paid = {false};
        h.onEachTick(() -> {
            long now = h.getLevel().getGameTime();
            if (began[0] < 0) {
                for (var b : buildings) {
                    var row = NaturalVillageLifeData.get(h.getLevel().getServer()).village(b.buildingKey());
                    if (row == null || !row.getBooleanOr("Ready", false)) return;
                }
                // These are separate initial configurations, frozen before the first member operation.
                cases.add(prepare(h, buildings.get(0), "seven_refused", 7, 120));
                cases.add(prepare(h, buildings.get(1), "consumption_invalidates_acceptance", 8, 350));
                cases.add(prepare(h, buildings.get(2), "severe_then_eight_paid", 9, 800));
                began[0] = now;
                var a = cases.get(0); var b = cases.get(1); var c = cases.get(2);
                check(h, command(a.player, "saga society work") == 0
                        && command(a.player, "saga society deliver") == 0, "seven cannot accept or pay");
                a.unchanged(h, 7, "seven refused without transfer");
                check(h, a.payment() == null, "refusal creates no payment transaction");
                a.event("SEVEN_REFUSED", a.snapshot());

                check(h, command(b.player, "saga society work") == 1, "eight accepts before ordinary consumption");
                b.accepted = b.payment().read("Transaction", UUIDUtil.CODEC).orElseThrow();
                check(h, b.payment().getStringOr("State", "").equals("ACCEPTED"), "acceptance has no paid result");
                b.unchanged(h, 8, "acceptance reserves no reward and transfers no goods");
                b.event("ACCEPTED_AT_EIGHT", b.snapshot());

                check(h, NaturalFarmRules.severelyHungry(h.getLevel().getServer(), c.building), "controlled initial severe need is visible");
                check(h, command(c.player, "saga society work") == 0
                        && command(c.player, "saga society deliver") == 0, "severe need refuses despite nine food");
                c.unchanged(h, 9, "severe refusal preserves real stock");
                check(h, c.payment() == null, "severe refusal creates no settlement");
                c.event("SEVERE_REFUSED", c.snapshot());
                return;
            }

            for (var c : cases) c.observe(h);
            var b = cases.get(1); var c = cases.get(2);
            if (!rejectedAfterMeal[0] && b.meals == 1) {
                check(h, count(b.pantry, Items.CARROT) == 7 && b.householdMealReceipt() != null,
                        "ordinary courier EAT actually consumes one household carrot");
                check(h, command(b.player, "saga society deliver") == 0, "seven at submission refuses accepted task");
                b.unchanged(h, 7, "failed payment takes neither player logs nor household reward");
                check(h, b.payment().getStringOr("State", "").equals("ACCEPTED")
                        && b.payment().read("Transaction", UUIDUtil.CODEC).orElseThrow().equals(b.accepted)
                        && TransferLedgerData.get(h.getLevel().getServer()).receipt(b.accepted) == null,
                        "unsubmitted original acceptance survives; no invented transfer receipt");
                b.event("CONSUMPTION_INVALIDATED_PAYMENT", b.snapshot());
                rejectedAfterMeal[0] = true;
            }
            if (!paid[0] && c.meals == 1) {
                check(h, count(c.pantry, Items.CARROT) == 8 && c.householdMealReceipt() != null,
                        "normal meal changes nine to eight before positive payment");
                check(h, !NaturalFarmRules.severelyHungry(h.getLevel().getServer(), c.building),
                        "real food resolved severe need before reopening");
                check(h, command(c.player, "saga society work") == 1, "eight with no severe resident accepts");
                c.accepted = c.payment().read("Transaction", UUIDUtil.CODEC).orElseThrow();
                check(h, command(c.player, "saga society work") == 1
                        && c.accepted.equals(c.payment().read("Transaction", UUIDUtil.CODEC).orElseThrow()), "repeat acceptance keeps one transaction");
                c.event("BEFORE_PAYMENT_AT_EIGHT", c.snapshot());
                check(h, command(c.player, "saga society deliver") == 1, "formal delivery succeeds");
                check(h, count(c.player.getInventory(), Items.OAK_LOG) == 0
                        && count(c.goods, Items.OAK_LOG) == 4
                        && count(c.player.getInventory(), Items.CARROT) == 2
                        && count(c.pantry, Items.CARROT) == 6, "four logs transfer and two food pay, six remain");
                var receipt = TransferLedgerData.get(h.getLevel().getServer()).receipt(c.accepted);
                check(h, receipt != null && receipt.successful() && receipt.lawful() && c.payment().getStringOr("State", "").equals("PAID"),
                        "same formal transaction has a successful real receipt");
                c.assertPaidOwnership(h);
                var before = c.stock();
                check(h, command(c.player, "saga society deliver") == 0
                        && command(c.player, "saga society work") == 0
                        && command(c.player, "saga society deliver") == 0, "repeat interactions cannot claim again");
                check(h, before.equals(c.stock()) && c.accepted.equals(c.payment().read("Transaction", UUIDUtil.CODEC).orElseThrow()),
                        "repeat interactions change neither stock nor transaction identity");
                c.paid = true;
                c.event("PAID_ONCE_AND_DUPLICATES_REFUSED", c.snapshot());
                paid[0] = true;
            }
            if (now - began[0] >= 1400 && rejectedAfterMeal[0] && paid[0]) {
                for (var item : cases) {
                    check(h, item.maintenanceChecks >= 3, "several normal 200-tick life maintenance opportunities observed");
                    item.assertAccounting(h);
                    item.event("FINAL", item.snapshot());
                }
                Sagadyssey.LOGGER.info("NATURAL_FARM_MEMBER_PASS sevenReject=true consumedBeforeSubmitReject=true severeReject=true eightPaysTwoLeavesSix=true duplicateDelta=0 courierSharedPantry=true controlledFixtures=3");
                h.succeed();
            }
        });
    }

    private static Case prepare(GameTestHelper h, NaturalVillageBuilding b, String name, int food, int courierHunger) {
        var l = h.getLevel(); var server = l.getServer();
        var row = NaturalVillageLifeData.get(server).village(b.buildingKey());
        check(h, NaturalVillageLayout.autonomousFarm(b) && row != null && row.getBooleanOr("Ready", false), "formal new-template initialization");
        for (int i = 0; i < 3; i++) {
            var npc = NaturalVillageR1GameTests.actor(l, b, i);
            check(h, npc != null && npc.isAlive() && npc.getCharacterId().equals(NaturalVillageLifeData.person(b, i)), "original fixture resident identity");
            // Isolate payment from farming inputs; the courier's normal life scheduler remains active.
            if (i != 1) npc.setCommand(NpcCommand.STAY);
        }
        var courier = NaturalVillageR1GameTests.actor(l, b, 1);
        var pantry = (Container) l.getBlockEntity(NaturalVillageLayout.at(b, NaturalVillageLayout.PANTRY));
        var goods = (Container) l.getBlockEntity(NaturalVillageLayout.at(b, NaturalVillageLayout.PUBLIC));
        check(h, count(pantry, Items.CARROT) == 4 && count(goods, Items.OAK_LOG) == 0,
                "unmodified natural four-food starting grant and real public wood deficit");
        pantry.setItem(0, new ItemStack(Items.CARROT, food)); pantry.setChanged();
        var life = NpcLifeData.get(server); var initial = life.needs(courier.getCharacterId());
        check(h, initial != null && initial.hunger() <= courierHunger, "controlled hunger only increases initial needs");
        life.increaseHunger(courier.getCharacterId(), courierHunger - initial.hunger(), l.getGameTime());
        var player = FakePlayerFactory.get(l, new GameProfile(UUID.randomUUID(), "NaturalMember"));
        player.setGameMode(GameType.SURVIVAL);
        var at = NaturalVillageLayout.at(b, NaturalVillageLayout.PANTRY);
        player.snapTo(at.getX() + .5, at.getY(), at.getZ() + .5, 0, 0);
        player.getInventory().clearContent(); player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 4));
        check(h, CharacterRegistry.currentId(player) == null && player.isAlive(), "fresh live test account before login");
        CharacterLifecycleEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(player));
        var person = CharacterRegistry.currentId(player);
        check(h, person != null, "formal live first login creates the player character");
        check(h, command(player, "saga society join") == 1, "permission-zero ordinary household join");
        var result = new Case(h, b, name, player, person, courier, pantry, goods, food);
        result.event("FROZEN_INITIAL_STATE", Map.of("state", result.snapshot(),
                "finiteAdditionalCarrots", food - 4, "playerOakLogs", 4,
                "courierInitialHunger", courierHunger, "farmerAndWorkerCommand", "STAY",
                "courierCommand", "WORK", "fixtureNaturalMobSpawning", false,
                "normalDaylightAndRandomTicks", true, "playerIdentityCreation", "formal PlayerLoggedInEvent",
                "scope", "controlled member payment; not natural cold start or process restart"));
        return result;
    }

    private static final class Case {
        final GameTestHelper h; final NaturalVillageBuilding building; final String name;
        final ServerPlayer player; final UUID person; final NpcBase courier;
        final Container pantry, goods; final int initialFood; final Path log;
        UUID accepted; long lastMeal; int meals, maintenanceChecks; boolean paid; int lastMaintenanceTick = -1;
        Case(GameTestHelper h, NaturalVillageBuilding b, String name, ServerPlayer p, UUID person,
             NpcBase courier, Container pantry, Container goods, int initialFood) {
            this.h = h; this.building = b; this.name = name; this.player = p; this.person = person;
            this.courier = courier; this.pantry = pantry; this.goods = goods; this.initialFood = initialFood;
            lastMeal = NpcLifeData.get(p.level().getServer()).needs(courier.getCharacterId()).lastAteAt();
            log = p.level().getServer().getWorldPath(LevelResource.ROOT).resolve("natural-farm-member-" + name + ".jsonl");
        }
        CompoundTag payment() { return NaturalVillageLifeData.get(player.level().getServer()).payment(person, building.settlementId()); }
        void unchanged(GameTestHelper h, int food, String why) {
            check(h, count(player.getInventory(), Items.OAK_LOG) == 4 && count(player.getInventory(), Items.CARROT) == 0
                    && count(goods, Items.OAK_LOG) == 0 && count(pantry, Items.CARROT) == food, why);
        }
        Map<String, Integer> stock() { return Map.of("playerOak", count(player.getInventory(), Items.OAK_LOG),
                "playerFood", count(player.getInventory(), Items.CARROT), "publicOak", count(goods, Items.OAK_LOG),
                "pantryFood", count(pantry, Items.CARROT)); }
        Map<String, Object> snapshot() {
            var result = new LinkedHashMap<String, Object>();
            result.put("building", building.buildingKey()); result.put("settlement", building.settlementId());
            result.put("household", NaturalVillageLifeData.home(building)); result.put("account", player.getUUID());
            result.put("playerPerson", person); result.put("courierBody", courier.getUUID()); result.put("courierPerson", courier.getCharacterId());
            result.put("stock", stock()); result.put("courierNeeds", NpcLifeData.get(player.level().getServer()).needs(courier.getCharacterId()).toString());
            var record = NpcLifeData.get(player.level().getServer()).record(courier.getCharacterId());
            result.put("decision", record.lastDecision()); result.put("decisionReason", record.lastDecisionReason());
            result.put("payment", payment() == null ? "NONE" : payment().toString());
            var transfer = accepted == null ? null : TransferLedgerData.get(player.level().getServer()).receipt(accepted);
            result.put("transferReceipt", transfer == null ? "NONE" : transfer.save().toString());
            result.put("meals", meals); result.put("mealReceipt", Objects.toString(householdMealReceipt(), "NONE"));
            result.put("maintenanceChecks", maintenanceChecks); return result;
        }
        void observe(GameTestHelper h) {
            check(h, courier.isAlive() && person.equals(CharacterRegistry.currentId(player)), "original live courier and player identity remain");
            var needs = NpcLifeData.get(player.level().getServer()).needs(courier.getCharacterId());
            if (needs.lastAteAt() > lastMeal) {
                lastMeal = needs.lastAteAt(); meals++;
                check(h, householdMealReceipt() != null, "meal has normal household consumption receipt");
                event("NORMAL_HOUSEHOLD_MEAL", snapshot());
            }
            var life = NpcLifeData.get(player.level().getServer()).record(courier.getCharacterId());
            check(h, life.privateStoragePosition() == null, "courier remains without an invented private warehouse");
            var home = HouseholdData.get(player.level().getServer()).forMember(courier.getCharacterId());
            var container = ContainerIds.block("household_storage", building.dimension(), home.sharedStorage());
            var context = RightsStateData.get(player.level().getServer()).context(container);
            check(h, home.id().equals(NaturalVillageLifeData.home(building))
                    && context != null && context.defaultBeneficialOwner().equals(SubjectRef.household(home.id()))
                    && context.custodian().equals(SubjectRef.household(home.id())), "existing legal shared pantry association");
            check(h, !life.lastDecisionReason().contains("私人储物缺失")
                    && !life.lastDecisionReason().contains("repair confirm")
                    && !life.lastDecision().equals("报告住宅设施失效"), "valid courier shared storage is not reported as broken private storage");
            // Sample immediately after each observed normal 200-tick boundary; never invoke maintenance directly.
            int boundary = courier.tickCount / 200;
            if (boundary > 0 && boundary != lastMaintenanceTick && courier.tickCount % 200 > 0) {
                lastMaintenanceTick = boundary; maintenanceChecks++;
                event("SHARED_PANTRY_DIAGNOSTIC", snapshot());
            }
        }
        String householdMealReceipt() {
            var brain = NpcBrainRuntime.data(player.level().getServer());
            var plans = new ArrayList<NpcPlan>(brain.history(courier.getUUID()));
            brain.active(courier.getUUID()).ifPresent(plans::add);
            for (var plan : plans) if (plan.providerId().equals(LifePlanFactory.PROVIDER)
                    && plan.steps().stream().anyMatch(s -> "EAT".equals(s.params().get("operation"))
                    && "HOUSEHOLD".equals(s.params().get("source")))
                    && "DONE".equals(LifeActivityData.get(player.level().getServer()).settlement(plan.id() + ":2")))
                return plan.id() + ":2";
            return null;
        }
        void assertAccounting(GameTestHelper h) {
            check(h, count(pantry, Items.CARROT) + count(player.getInventory(), Items.CARROT) + meals == initialFood,
                    name + ": actual food positions plus real consumption equal finite initial food");
            check(h, count(goods, Items.OAK_LOG) + count(player.getInventory(), Items.OAK_LOG) == 4,
                    name + ": actual player/public oak conservation");
            check(h, paid == (payment() != null && payment().getStringOr("State", "").equals("PAID")), "paid state matches observed real transfer");
            if (paid) assertPaidOwnership(h);
        }
        void assertPaidOwnership(GameTestHelper h) {
            owned(h, player.getInventory(), ContainerIds.playerPersonal(person), Items.CARROT, SubjectRef.character(person));
            owned(h, goods, ContainerIds.block("natural_public", building.dimension(),
                    NaturalVillageLayout.at(building, NaturalVillageLayout.PUBLIC)), Items.OAK_LOG, SubjectRef.institution(building.settlementId()));
            owned(h, pantry, ContainerIds.block("household_storage", building.dimension(),
                    NaturalVillageLayout.at(building, NaturalVillageLayout.PANTRY)), Items.CARROT, SubjectRef.household(NaturalVillageLifeData.home(building)));
        }
        void owned(GameTestHelper h, Container inventory, UUID id, Item item, SubjectRef expected) {
            var rights = RightsStateData.get(player.level().getServer()); var context = rights.context(id);
            check(h, context != null, "transferred physical inventory has an existing rights context");
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                var stack = inventory.getItem(slot); if (!stack.is(item)) continue;
                var claims = rights.claimsAt(id, slot, ItemFingerprint.of(stack));
                check(h, claims.size() <= 1 && (claims.isEmpty() ? expected.equals(context.defaultBeneficialOwner())
                        : claims.getFirst().quantity() == stack.getCount() && expected.equals(claims.getFirst().beneficialOwner())),
                        "actual transferred stack belongs to the intended household, player or institution");
            }
        }
        void event(String event, Object detail) {
            var value = new LinkedHashMap<String, Object>(); value.put("tick", h.getLevel().getGameTime());
            value.put("case", name); value.put("event", event); value.put("detail", detail);
            try { Files.writeString(log, new Gson().toJson(value) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (Exception e) { throw new IllegalStateException("cannot retain member case evidence", e); }
        }
    }

    private static int command(ServerPlayer player, String command) {
        try { return player.level().getServer().getCommands().getDispatcher().execute(command,
                player.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.ALL).withSuppressedOutput()); }
        catch (Exception e) { throw new IllegalStateException("ordinary member command failed: " + command, e); }
    }
    private static int count(Container c, Item item) {
        int total = 0; for (int slot = 0; slot < c.getContainerSize(); slot++)
            if (c.getItem(slot).is(item)) total += c.getItem(slot).getCount(); return total;
    }
    private static void check(GameTestHelper h, boolean condition, String reason) { h.assertTrue(condition, reason); }
}
