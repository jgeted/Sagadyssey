package com.jgeted.sagadyssey.core.structure;

import com.google.gson.Gson;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.life.LifeActivityData;
import com.jgeted.sagadyssey.npc.brain.life.LifePlanFactory;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBrainData;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.politics.*;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.worldsim.lod.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * DRAFT, NOT RUN. Approved output-consumption scope only; three independent JVMs.
 * True HARVEST/PLANT/BAG consumption is produced exclusively by existing executors.
 * No fabricated consumption/PENDING record, cleared obligation or forced delivery.
 * Initial controlled conditions match food-failures-02's BAG branch. One later
 * restoration of the original EMPTY RAW block is declared, only after restart.
 * AI is held solely at save/admission boundaries, then its preceding state restored.
 * RAW absence cannot support a regional handoff: normal stop, final disk and whole
 * world copies establish this checkpoint. This does not claim real LOD or crash recovery.
 */
@GameTestHolder("sagadyssey_natural_life")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid = "sagadyssey")
public final class NaturalFarmConsumptionRestartGameTests {
    private static final String SAVE = "natural-consumption-save", RESUME = "natural-consumption-resume", VERIFY = "natural-consumption-verify";
    private static final String MARKER = "natural-farm-consumption-checkpoint.nbt";
    private static final String ITEM = "minecraft:carrot#0";
    private static final int WINDOW = 2400, RETURN_WAIT = 400;
    private static final Gson JSON = new Gson();
    private static CompoundTag restored, terminal;
    private static final Set<UUID> admitted = new HashSet<>();
    private static Run active;
    private static GameTestHelper running;
    private static boolean startupVerified, failed;
    private NaturalFarmConsumptionRestartGameTests() { }
    private static String phase() { return System.getProperty("sagadyssey.society.phase", ""); }
    private static boolean enabled() { return Set.of(SAVE, RESUME, VERIFY).contains(phase()); }

    @GameTestGenerator public static Collection<TestFunction> tests() {
        if (!enabled()) return List.of();
        return List.of(new TestFunction("natural_farm_consumption_restart", phase().replace('-', '_'),
                "sagadyssey_natural_life:empty", 3800, 0, true, h -> {
                    running = h; active = new Run(h); LodContinuityP8GameTests.paced(h.getLevel());
                }));
    }

    /** GameTestServer.initServer calls this before loadLevel; verify the copy before loading it. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void about(net.neoforged.neoforge.event.server.ServerAboutToStartEvent e) {
        if (!enabled() || !(e.getServer() instanceof GameTestServer)) return;
        active = null; running = null; restored = null; terminal = null;
        admitted.clear(); startupVerified = false; failed = false;
        var world = e.getServer().getWorldPath(LevelResource.ROOT);
        try {
            if (phase().equals(SAVE)) {
                require(Files.notExists(world.resolve(MARKER)), "fresh isolated save-stage world; do not reuse an old attempt"); return;
            }
            restored = read(world.resolve(MARKER));
            require(restored.getBoolean("StoppedVerified"), "prior normal stop and final disk verification required");
            require(restored.getString("Phase").equals(phase().equals(RESUME) ? SAVE : RESUME), "explicit consecutive checkpoint phase");
            require(restored.getLong("SavedProcess") != ProcessHandle.current().pid(), "independent JVM required");
            for (long prior : restored.getLongArray("Processes")) require(prior != ProcessHandle.current().pid(), "third phase must also use a new JVM");
            require(stateFiles(world).equals(restored.getList("StateFiles", Tag.TAG_COMPOUND)), "entire associated world state copied, not selected ledgers");
            event(e.getServer(), "PRE_LOAD_COMPLETE_WORLD_VERIFIED", Map.of("priorProcess", restored.getLong("SavedProcess"), "files", restored.getList("StateFiles", Tag.TAG_COMPOUND).size()));
        } catch (Exception error) { failed = true; failure(e.getServer(), error); throw new IllegalStateException("complete stopped consumption world required", error); }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void admit(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) {
        if (!enabled() || restored == null || !(e.getLevel() instanceof ServerLevel l)
                || !(l.getServer() instanceof GameTestServer) || !(e.getEntity() instanceof NpcBase n)) return;
        var body = restored.getList("Bodies", Tag.TAG_COMPOUND).stream().map(t -> (CompoundTag) t)
                .filter(t -> t.getUUID("UUID").equals(n.getUUID())).findFirst().orElse(null);
        if (body == null) return;
        try {
            require(startupVerified, "original body must not load before first-tick associated-state verification");
            require(n.getCharacterId().equals(body.getUUID("PersonProof")) && n.isNoAi() == body.getBoolean("AIProof"), "same original body/person and saved AI hold");
            require(LodFingerprints.inventoryTag(n).equals(body.get("AllItemsProof")), "all original personal/cargo/equipment/overflow before resumed work");
            require(admitted.add(n.getUUID()), "one deserialized admission per original body");
            event(l.getServer(), "ORIGINAL_ADMISSION", Map.of("body", n.getUUID(), "person", n.getCharacterId(), "beforeWork", true));
        } catch (RuntimeException error) { failed = true; failure(l.getServer(), error); throw error; }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void started(net.neoforged.neoforge.event.server.ServerStartedEvent e) {
        if (!enabled() || restored == null || !(e.getServer() instanceof GameTestServer)) return;
        var s = e.getServer(); var l = s.overworld();
        try {
            var expected = restored.getCompound("FinalLinks").copy();
            for (var raw : expected.getCompound("Economy").getList("Regions", Tag.TAG_COMPOUND)) {
                var row = (CompoundTag) raw; row.putBoolean("ObservedAway", true); row.putBoolean("ReturnRequested", false);
            }
            var actual = links(l); write(s, "consumption-before-first-tick-" + phase() + ".nbt", actual);
            require(comparable(actual).equals(comparable(expected)), "all21 associated state families survive before first resumed tick");
            var disk = read(s.getWorldPath(LevelResource.ROOT).resolve("level.dat")).getCompound("Data");
            require(disk.getLong("Time") == restored.getLong("SavedTime") && disk.getCompound("GameRules").equals(restored.getCompound("SavedRules")), "actual associated disk time/rules");
            // GameTestServer creates fresh in-memory level metadata. Restore only this verified disk metadata.
            l.getGameRules().assignFrom(new GameRules(new com.mojang.serialization.Dynamic<>(NbtOps.INSTANCE, disk.getCompound("GameRules"))), s);
            s.getWorldData().overworldData().setGameTime(disk.getLong("Time"));
            s.getWorldData().overworldData().setDayTime(disk.getLong("DayTime"));
            require(admitted.isEmpty(), "released village tickets keep originals out of startup/spawn loading");
            startupVerified = true;
            event(s, "FIRST_TICK_LINKS_VERIFIED", Map.of("categories", actual.getAllKeys().size(), "gameTime", l.getGameTime(), "offlineCatchup", false));
        } catch (RuntimeException error) { failed = true; failure(s, error); throw error; }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void observe(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if (!enabled() || active == null || failed) return;
        try { active.tick(); }
        catch (RuntimeException error) {
            failed = true; failure(e.getServer(), error); active = null;
            running.runAtTickTime(running.getTick() + 1, () -> { throw new GameTestAssertException(error.getMessage()); });
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void stopping(net.neoforged.neoforge.event.server.ServerStoppingEvent e) {
        if (!enabled() || terminal == null || failed) return;
        // This remains pre-close evidence: closing can still hand off/unload original entities.
        terminal.put("StoppingLinks", links(e.getServer().overworld()));
        terminal.putBoolean("ServerStoppingObserved", true);
        write(e.getServer(), "consumption-stopping-not-final.nbt", terminal);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e) {
        if (!enabled() || terminal == null || failed) return;
        var s = e.getServer(); var world = s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        try {
            require(terminal.getBoolean("ServerStoppingObserved"), "normal stop event occurred");
            var finalLinks = links(s.overworld());
            require(finalLinks.getAllKeys().size() == 21, "all21 linked families captured after normal closing");
            // Compare persisted families with final memory, including normal closing changes.
            // Empty, never-dirtied families may have no file; the next JVM must recreate an identical empty value.
            var absent = new ListTag();
            for (var entry : dataNames().entrySet()) {
                var file = world.resolve("data").resolve(entry.getValue() + ".dat");
                if (Files.isRegularFile(file)) {
                    var diskOne = new CompoundTag(); diskOne.put(entry.getKey(), read(file).getCompound("data"));
                    var liveOne = new CompoundTag(); liveOne.put(entry.getKey(), finalLinks.getCompound(entry.getKey()));
                    require(comparable(diskOne).equals(comparable(liveOne)), "final disk family differs from saved live state: " + entry.getKey());
                } else absent.add(StringTag.valueOf(entry.getKey()));
            }
            var data = read(world.resolve("level.dat")).getCompound("Data");
            terminal.put("FinalLinks", finalLinks); terminal.put("AbsentUnwrittenFamilies", absent);
            terminal.putLong("SavedTime", data.getLong("Time")); terminal.putLong("SavedDayTime", data.getLong("DayTime"));
            terminal.put("SavedRules", data.getCompound("GameRules").copy()); terminal.putLong("SavedProcess", ProcessHandle.current().pid());
            terminal.putBoolean("StoppedVerified", true); terminal.put("StateFiles", stateFiles(world));
            write(s, MARKER, terminal);
            var snapshot = world.resolveSibling("world-natural-consumption-" + (phase().equals(SAVE) ? "after-bag" : phase().equals(RESUME) ? "after-deposit" : "verified-final"));
            var indexPath = snapshot.resolveSibling(snapshot.getFileName() + "-files.nbt");
            require(Files.notExists(snapshot), "never overwrite a prior complete checkpoint");
            require(Files.notExists(indexPath), "complete-world file index also refuses overwrite");
            Files.createDirectories(snapshot);
            try (var files = Files.walk(world)) {
                for (var file : files.filter(Files::isRegularFile).toList()) {
                    if (file.getFileName().toString().equals("session.lock")) continue;
                    var to = snapshot.resolve(world.relativize(file)); Files.createDirectories(to.getParent()); Files.copy(file, to);
                }
            }
            require(stateFiles(snapshot).equals(terminal.getList("StateFiles", Tag.TAG_COMPOUND)), "whole copied world matches final stopped disk");
            var complete = completeFiles(world);
            require(complete.equals(completeFiles(snapshot)), "every copied file including non-ledger metadata and evidence matches source");
            var index = new CompoundTag(); index.putString("Phase", phase()); index.putString("World", snapshot.toString());
            index.put("AllCopiedFiles", complete); index.putBoolean("StoppedVerified", true);
            NbtIo.writeCompressed(index, indexPath);
            event(s, "STOPPED_COMPLETE_WORLD_READY", Map.of("phase", phase(), "snapshot", snapshot.toString(), "savedTime", data.getLong("Time"), "sameLinkedWorld", true));
            Sagadyssey.LOGGER.info("NATURAL_CONSUMPTION_RESTART_READY phase={} completeWorld={} stoppedVerified=true", phase(), snapshot);
        } catch (Exception error) {
            failed = true; terminal.putBoolean("StoppedVerified", false); terminal.putString("Failure", error.toString()); write(s, MARKER, terminal);
            failure(s, error); // A GameTest pass alone never authorizes the next phase.
        }
    }

    private static final class Run {
        final GameTestHelper h; final ServerLevel l; final NaturalVillageBuilding b;
        final Set<String> seenActions = new HashSet<>(), seenLife = new HashSet<>();
        final Set<BlockPos> mature = new HashSet<>();
        final long[] ateAt = {-1, -1, -1}; final boolean[] resumeNoAi = new boolean[3];
        long readyAt = -1, start = -1, since, observeAt = -1, lastTime;
        long net; int harvests, plants, meals, bagMeals; int stage;
        BlockState originalRaw; String consumptionKey; CompoundTag consumptionProof, settledProof;
        Run(GameTestHelper helper) {
            h = helper; l = h.getLevel(); since = lastTime = l.getGameTime();
            if (phase().equals(SAVE)) b = NaturalVillageR1GameTests.fixture(h, 256, NaturalVillageLayout.FARM_V3);
            else {
                require(restored != null && startupVerified, "complete original world was checked before case");
                b = NaturalVillageLifeData.decode(restored.getCompound("Village"));
                originalRaw = NbtUtils.readBlockState(l.holderLookup(Registries.BLOCK), restored.getCompound("OriginalRaw"));
                start = restored.getLong("OriginalStart"); consumptionKey = restored.getString("ConsumptionKey");
                consumptionProof = restored.getCompound("ConsumptionProof").copy();
                meals = restored.getInt("ActualMeals"); bagMeals = restored.getInt("ActualBagMeals");
                for (int i = 0; i < 3; i++) { ateAt[i] = restored.getLongArray("LastAteAt")[i]; resumeNoAi[i] = restored.getIntArray("ResumeNoAi")[i] != 0; }
                for (var cell : NaturalVillageLayout.crops().subList(0, 3)) mature.add(NaturalVillageLayout.at(b, cell));
                settledProof = restored.getCompound("BusinessPoint").copy(); stage = 2;
                NaturalVillageR1GameTests.visiting(l, b, true);
            }
        }
        NpcBase npc(int i) { return NaturalVillageR1GameTests.actor(l, b, i); }
        FieldworkData fieldwork() { return FieldworkData.get(l.getServer()); }
        Container store(BlockPos local) { var e = l.getBlockEntity(NaturalVillageLayout.at(b, local)); return e instanceof Container c ? c : null; }
        int pending() { return fieldwork().pendingCargo(NaturalVillageLifeData.body(b, 0)).getOrDefault(ITEM, 0); }
        void tick() {
            long now = l.getGameTime(); require(now >= lastTime && now - lastTime <= 1, "ordinary ticks only"); lastTime = now;
            if (stage == 0) {
                require(h.getTick() < 1200, "formal original initialization before1200");
                var life = NaturalVillageLifeData.get(l.getServer()).village(b.buildingKey());
                if (life == null || !life.getBoolean("Ready")) return;
                if (readyAt < 0) {
                    l.setDayTime(1000); l.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, l.getServer()); readyAt = now;
                }
                if (now - readyAt < 20) return;
                prepare(); return;
            }
            if (stage == 2) {
                require(now - since <= RETURN_WAIT, "original body/region admission within400 ticks, counted inside the same2400 window");
                if (!LodContinuityP8GameTests.near(l, b)) return;
                originals(); require(admitted.size() == 3, "three original admissions verified before work");
                for (int i = 0; i < 3; i++) {
                    var checkpoint = NpcLodData.get(l.getServer()).find(npc(i).getUUID());
                    if (checkpoint == null || checkpoint.unloaded()) return;
                    require(!NpcLodRuntime.review(checkpoint) && npc(i).isNoAi(), "original LOD admission reconciled under declared AI hold");
                }
                observeWork(false); verifyConsumption();
                require(businessPoint().equals(settledProof), "original production/consumption/deposit obligation unchanged before work");
                require(physical() + meals == 12 + net, "original complete physical balance at restart");
                if (phase().equals(RESUME)) {
                    require(store(NaturalVillageLayout.RAW) == null && l.getBlockState(NaturalVillageLayout.at(b, NaturalVillageLayout.RAW)).isAir(), "original RAW remains absent until declared restoration");
                    l.setBlockAndUpdate(NaturalVillageLayout.at(b, NaturalVillageLayout.RAW), originalRaw);
                    require(store(NaturalVillageLayout.RAW) != null && store(NaturalVillageLayout.RAW).isEmpty(), "restore only original EMPTY RAW block; no stock added");
                    event(l.getServer(), "DECLARED_EMPTY_RAW_RESTORATION", Map.of("stockAdded", 0, "gameTime", now, "deadlineFromResume", since + WINDOW));
                    stage = 3;
                } else { stage = 4; observeAt = now; }
                for (int i = 0; i < 3; i++) npc(i).setNoAi(resumeNoAi[i]);
                return;
            }
            originals(); observeWork(true); observeMeals();
            require(12 + net == physical() + meals, "initial12 + all real carrot production = all physical carrots + actual meals");
            if (stage == 1) {
                require(now - start <= WINDOW, "real BAG evidence inside original2400 ticks");
                if (bagMeals == 0) return;
                require(bagMeals == 1 && meals == 1 && harvests == 3 && plants == 3, "exactly3 actual harvests/plants and one genuine BAG meal before save");
                var consumptions = fieldwork().outputConsumptions(npc(0).getUUID());
                require(consumptions.size() == 1, "one actual output consumption record");
                var record = consumptions.getFirst(); consumptionKey = record.key(); consumptionProof = encode(record); verifyConsumption();
                require(recordedDeposits() == 0 && pending() == net - 1 && pending() > 0 && store(NaturalVillageLayout.RAW) == null,
                        "real consumed output checkpoint still has undelivered production and no RAW");
                require(count(npc(0).getPersonalInventory()) == 12 + net - 1, "original12 plus unconsumed actual produce in original bag");
                checkpoint("AFTER_ACTUAL_BAG_BEFORE_ANY_DEPOSIT"); return;
            }
            verifyConsumption();
            require(bagMeals == 1, "original BAG consumption is not replayed; no undeclared second BAG source");
            if (stage == 3) {
                require(now - since <= WINDOW, "original delivery resumes within unchanged2400 ticks, including load/restore");
                require(harvests == 3 && plants == 3, "finite original three-plot production only");
                if (pending() != 0 || fieldwork().pendingDelivery(npc(0).getUUID()).isPresent()
                        || FieldworkDepositTaskExecutor.hasDeliverableCargo(npc(0), fieldwork())) return;
                require(recordedDeposits() == net - 1 && count(npc(0).getPersonalInventory()) == 12, "Q=real consumed1+real deliveredQ-1; original seeds remain12");
                checkpoint("AFTER_NORMAL_ACTUAL_DEPOSIT"); return;
            }
            if (stage == 4) {
                require(now - since <= WINDOW, "bounded second-restart observation");
                require(businessPoint().equals(settledProof), "no repeated output consumption, delivery, or recreated obligation after second restart");
                require(count(npc(0).getPersonalInventory()) == 12, "original seed reserve retained after second restart");
                if (now - observeAt < 200) return;
                checkpoint("SECOND_RESTART_NO_REPLAY_VERIFIED");
            }
        }
        void prepare() {
            originals(); var life = NpcLifeData.get(l.getServer());
            for (int i = 0; i < 3; i++) {
                require(!npc(i).isNoAi() && life.needs(npc(i).getCharacterId()).lastAteAt() < 0, "original AI with no hidden earlier meal");
                ateAt[i] = life.needs(npc(i).getCharacterId()).lastAteAt(); resumeNoAi[i] = npc(i).isNoAi();
            }
            require(count(npc(0).getPersonalInventory()) == 12 && count(store(NaturalVillageLayout.PANTRY)) == 4 && physical() == 16,
                    "finite original12+4 before declared initial condition");
            require(fieldwork().receiptsFor(npc(0).getUUID()).isEmpty() && fieldwork().outputConsumptions(npc(0).getUUID()).isEmpty(), "no prior production/consumption record");
            for (var local : NaturalVillageLayout.crops()) {
                var at = NaturalVillageLayout.at(b, local); require(l.getBlockState(at).is(Blocks.CARROTS), "original planted carrot plot");
                l.setBlockAndUpdate(at, Blocks.CARROTS.defaultBlockState());
            }
            var pantry = store(NaturalVillageLayout.PANTRY);
            for (int slot = 0; slot < pantry.getContainerSize(); slot++) if (pantry.getItem(slot).is(Items.CARROT)) pantry.removeItemNoUpdate(slot);
            pantry.setChanged();
            require(store(NaturalVillageLayout.RAW) != null && store(NaturalVillageLayout.RAW).isEmpty(), "temporarily removed RAW is entirely empty");
            var rawAt = NaturalVillageLayout.at(b, NaturalVillageLayout.RAW); originalRaw = l.getBlockState(rawAt); l.setBlockAndUpdate(rawAt, Blocks.AIR.defaultBlockState());
            for (var local : NaturalVillageLayout.crops().subList(0, 3)) {
                var at = NaturalVillageLayout.at(b, local); l.setBlockAndUpdate(at, Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, 7)); mature.add(at);
            }
            life.setHunger(npc(0).getCharacterId(), 800, l.getGameTime());
            require(physical() == 12 && count(npc(0).getPersonalInventory()) == 12, "only declared removal of original pantry4; original bag untouched");
            require(NaturalFarmRules.foodSlot(npc(0), npc(0).getPersonalInventory(), "BAG", npc(0).blockPosition(), true, l.getGameTime()) < 0, "no emergency BAG permission before actual harvest");
            start = l.getGameTime(); stage = 1;
            event(l.getServer(), "CONTROLLED_INITIAL_BOUNDARY", Map.of("originalBag", 12, "pantry", 0, "originalMaturePlots", 3,
                    "removedInitialPantry", 4, "rawTemporarilyAbsent", true, "firstWindow", WINDOW, "noLaterFoodInjection", true));
        }
        void observeWork(boolean emit) {
            net = 0; harvests = 0; plants = 0;
            for (int i = 0; i < 3; i++) for (var receipt : fieldwork().receiptsFor(NaturalVillageLifeData.body(b, i))) {
                int delta = receipt.inventoryDeltas().stream().filter(d -> d.fingerprint().equals(ITEM)).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();
                net += delta;
                if (i == 0 && receipt.action().operation() == FieldworkAction.Operation.HARVEST) { require(mature.contains(receipt.action().target()), "only declared original mature plot"); harvests++; }
                if (i == 0 && receipt.action().operation() == FieldworkAction.Operation.PLANT) { require(delta == -1, "real one-item replant"); plants++; }
                if (emit && seenActions.add(receipt.action().key())) event(l.getServer(), "REAL_FIELDWORK", Map.of("key", receipt.action().key(), "operation", receipt.action().operation().name(), "delta", delta, "committedAt", receipt.committedAt()));
            }
        }
        void observeMeals() {
            for (int i = 0; i < 3; i++) {
                var needs = NpcLifeData.get(l.getServer()).needs(npc(i).getCharacterId());
                if (needs.lastAteAt() > ateAt[i]) { ateAt[i] = needs.lastAteAt(); meals++; event(l.getServer(), "ACTUAL_MEAL", Map.of("person", npc(i).getCharacterId(), "at", needs.lastAteAt(), "hunger", needs.hunger())); }
                var brain = NpcBrainData.get(l.getServer()); var plans = new ArrayList<>(brain.history(npc(i).getUUID())); brain.active(npc(i).getUUID()).ifPresent(plans::add);
                for (var plan : plans) if (plan.createdAt() >= start && plan.providerId().equals(LifePlanFactory.PROVIDER)) for (var step : plan.steps()) {
                    String key = plan.id() + ":" + step.index();
                    if (!"DONE".equals(LifeActivityData.get(l.getServer()).settlement(key)) || !seenLife.add(key)) continue;
                    if (i == 0 && "EAT".equals(step.params().get("operation")) && "BAG".equals(step.params().get("source"))) {
                        if (restored == null || !key.equals(consumptionKey)) bagMeals++;
                        event(l.getServer(), "NORMAL_BAG_DONE", Map.of("key", key, "originalKey", key.equals(consumptionKey)));
                    }
                }
            }
        }
        void verifyConsumption() {
            require(fieldwork().outputConsumptionReview(NaturalVillageLifeData.body(b, 0), LifeActivityData.get(l.getServer())::settlement).isEmpty(),
                    "no unresolved or contradictory output-consumption/life linkage");
            var original = fieldwork().outputConsumption(consumptionKey).orElseThrow(() -> new IllegalStateException("missing original output-consumption record"));
            require(encode(original).equals(consumptionProof) && original.state().name().equals("COMMITTED")
                    && original.npcId().equals(NaturalVillageLifeData.body(b, 0)) && original.personId().equals(NaturalVillageLifeData.person(b, 0))
                    && original.containerId().equals(ContainerIds.npcPersonal(original.personId())) && original.owner().equals(SubjectRef.character(original.personId()))
                    && original.fingerprint().equals(ITEM) && original.quantity() == 1 && original.outputQuantity() == 1, "same committed actual one-item self-owned output consumption");
            require("DONE".equals(LifeActivityData.get(l.getServer()).settlement(consumptionKey)), "original committed consumption retains its same-key life DONE");
            require(fieldwork().outputConsumptions(original.npcId()).size() == 1, "PRIVATE/HOUSEHOLD meals do not double-charge old delivered output");
        }
        int recordedDeposits() {
            int quantity = 0;
            for (var raw : fieldwork().save(new CompoundTag(), l.registryAccess()).getList("Deliveries", Tag.TAG_COMPOUND)) {
                var row = (CompoundTag) raw;
                if (!row.getUUID("NpcId").equals(NaturalVillageLifeData.body(b, 0)) || !row.getString("Fingerprint").equals(ITEM)) continue;
                var transfer = TransferLedgerData.get(l.getServer()).receipt(row.getUUID("TransferId"));
                require(transfer != null && transfer.successful() && transfer.lawful(), "each output delivery is an actual lawful transfer"); quantity += row.getInt("Quantity");
            }
            return quantity;
        }
        CompoundTag businessPoint() {
            var out = new CompoundTag(); out.putLong("Net", net); out.putInt("Harvests", harvests); out.putInt("Plants", plants);
            out.putInt("Deposited", recordedDeposits()); out.putInt("Pending", pending()); out.putBoolean("PendingDelivery", fieldwork().pendingDelivery(NaturalVillageLifeData.body(b, 0)).isPresent());
            if (consumptionKey != null) out.put("Consumption", encode(fieldwork().outputConsumption(consumptionKey).orElseThrow()));
            var deliveries = new ListTag();
            for (var raw : fieldwork().save(new CompoundTag(), l.registryAccess()).getList("Deliveries", Tag.TAG_COMPOUND)) {
                var row = (CompoundTag) raw; if (row.getUUID("NpcId").equals(NaturalVillageLifeData.body(b, 0))) deliveries.add(row.copy());
            }
            out.put("AllOriginalFarmerDeliveries", deliveries); return out;
        }
        void originals() {
            var home = HouseholdData.get(l.getServer()).byId(NaturalVillageLifeData.home(b)); require(home != null && home.members().size() == 3, "same original household3");
            Map<UUID, Integer> counts = new HashMap<>();
            for (var world : l.getServer().getAllLevels()) for (var e : world.getAllEntities()) if (e instanceof NpcBase actor) for (int i = 0; i < 3; i++) {
                if (actor.getCharacterId().equals(NaturalVillageLifeData.person(b, i))) { require(actor.getUUID().equals(NaturalVillageLifeData.body(b, i)), "no replacement original body"); counts.merge(actor.getCharacterId(), 1, Integer::sum); }
            }
            for (int i = 0; i < 3; i++) require(npc(i) != null && npc(i).isAlive() && npc(i).getCharacterId().equals(NaturalVillageLifeData.person(b, i))
                    && home.contains(npc(i).getCharacterId()) && CharacterRegistryData.get(l.getServer()).byId(npc(i).getCharacterId()).alive()
                    && counts.getOrDefault(npc(i).getCharacterId(), 0) == 1, "one same living original body/person/home " + i);
        }
        int physical() {
            int total = 0; for (var local : NaturalVillageLayout.stores(b)) total += count(store(local));
            for (int i = 0; i < 3; i++) {
                var n = npc(i); require(n != null, "original mobile inventory available for physical accounting");
                total += count(n.getPersonalInventory()) + count(n.getTransportInventory());
                for (var slot : EquipmentSlot.values()) if (n.getItemBySlot(slot).is(Items.CARROT)) total += n.getItemBySlot(slot).getCount();
                for (var stack : n.getInventoryMigrationOverflowSnapshot()) if (stack.is(Items.CARROT)) total += stack.getCount();
            }
            for (var item : l.getEntitiesOfClass(ItemEntity.class, new AABB(b.buildingOrigin()).inflate(48), e -> e.getItem().is(Items.CARROT))) total += item.getItem().getCount();
            return total;
        }
        void checkpoint(String boundary) {
            verifyConsumption(); require(12 + net == physical() + meals, "checkpoint exact per-item balance");
            var marker = new CompoundTag(); marker.putString("Phase", phase()); marker.putString("Boundary", boundary);
            marker.put("Village", NaturalVillageLifeData.get(l.getServer()).village(b.buildingKey())); marker.put("OriginalRaw", NbtUtils.writeBlockState(originalRaw));
            marker.putLong("OriginalStart", start); marker.putString("ConsumptionKey", consumptionKey); marker.put("ConsumptionProof", consumptionProof.copy());
            marker.put("BusinessPoint", businessPoint()); marker.putInt("ActualMeals", meals); marker.putInt("ActualBagMeals", bagMeals); marker.putLongArray("LastAteAt", ateAt);
            marker.putIntArray("ResumeNoAi", new int[]{resumeNoAi[0] ? 1 : 0, resumeNoAi[1] ? 1 : 0, resumeNoAi[2] ? 1 : 0});
            var processes = new ArrayList<Long>(); if (restored != null) for (long prior : restored.getLongArray("Processes")) processes.add(prior); processes.add(ProcessHandle.current().pid());
            marker.putLongArray("Processes", processes.stream().mapToLong(Long::longValue).toArray());
            var bodies = new ListTag();
            for (int i = 0; i < 3; i++) {
                var n = npc(i); n.setNoAi(true); var body = n.saveWithoutId(new CompoundTag());
                body.putUUID("PersonProof", n.getCharacterId()); body.putBoolean("AIProof", n.isNoAi()); body.put("AllItemsProof", LodFingerprints.inventoryTag(n)); bodies.add(body);
            }
            marker.put("Bodies", bodies); marker.put("BoundaryLinks", links(l)); marker.putBoolean("StoppedVerified", false);
            marker.putString("EvidenceScope", "real production/consumption; normal complete process restart; no fabricated PENDING; not crash recovery or LOD acceptance");
            terminal = marker; write(l.getServer(), "consumption-boundary-not-final.nbt", marker);
            event(l.getServer(), boundary, Map.of("net", net, "meals", meals, "pending", pending(), "deposited", recordedDeposits(), "allOriginalItems", true));
            // Remove only test tickets. The normal shutdown saves the actual original bodies and all linked data.
            NaturalVillageR1GameTests.visiting(l, b, false); active = null; h.succeed();
        }
    }

    private static CompoundTag encode(FieldworkCargo.OutputConsumption c) {
        var t = new CompoundTag(); t.putString("Key", c.key()); t.putUUID("Body", c.npcId()); t.putUUID("Person", c.personId());
        t.putUUID("Container", c.containerId()); t.put("Owner", c.owner().save()); t.putString("Fingerprint", c.fingerprint());
        t.putInt("Quantity", c.quantity()); t.putInt("OutputQuantity", c.outputQuantity()); t.putLong("StartedAt", c.startedAt());
        t.putLong("CommittedAt", c.committedAt()); t.putLong("BeforeRevision", c.beforeRevision()); t.putLong("FinalRevision", c.finalRevision()); t.putString("State", c.state().name()); return t;
    }
    private static Map<String, String> dataNames() {
        return Map.ofEntries(Map.entry("NaturalLife", "sagadyssey_natural_village_life_v1"), Map.entry("Economy", "sagadyssey_natural_region_economy_v1"),
                Map.entry("Brain", "sagadyssey_npc_brain_v3"), Map.entry("Activities", "sagadyssey_life_activities_v3"), Map.entry("Lod", "sagadyssey_npc_lod_v3"),
                Map.entry("Life", "sagadyssey_npc_life"), Map.entry("Households", "sagadyssey_households"), Map.entry("Rights", "sagadyssey_asset_rights_v2"),
                Map.entry("Supply", "sagadyssey_supply_flow_v3"), Map.entry("Needs", "sagadyssey_need_requests_v3"), Map.entry("Transfers", "sagadyssey_transfer_ledger_v2"),
                Map.entry("Logistics", "sagadyssey_logistics"), Map.entry("Buildings", "sagadyssey_settlements"), Map.entry("Characters", "sagadyssey_characters"),
                Map.entry("Fieldwork", "sagadyssey_fieldwork_v3"), Map.entry("Polity", "sagadyssey_natural_charters_v1"), Map.entry("Office", "sagadyssey_politics"),
                Map.entry("Exchange", "sagadyssey_regional_exchange_v1"), Map.entry("Conflict", "sagadyssey_natural_disputes_v1"),
                Map.entry("Encounters", "sagadyssey_combat_encounters"), Map.entry("Injuries", "sagadyssey_injuries"));
    }
    private static ListTag stateFiles(Path world) {
        return inventoryFiles(world, true);
    }
    private static ListTag completeFiles(Path world) {
        return inventoryFiles(world, false);
    }
    private static ListTag inventoryFiles(Path world, boolean stateOnly) {
        var list = new ListTag();
        try (var walk = Files.walk(world)) {
            for (var file : walk.filter(Files::isRegularFile).filter(p -> { String n = p.getFileName().toString();
                return !n.equals("session.lock") && (!stateOnly || n.endsWith(".dat") || n.endsWith(".mca") || n.endsWith(".mcc")); }).sorted().toList()) {
                var t = new CompoundTag(); t.putString("Path", world.relativize(file).toString().replace('\\', '/')); t.putLong("Bytes", Files.size(file));
                t.putString("SHA256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))); list.add(t);
            }
        } catch (Exception error) { throw new IllegalStateException("cannot inventory complete world state", error); }
        return list;
    }
    private static CompoundTag read(Path file) { try { return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()); } catch (Exception error) { throw new IllegalStateException("cannot read original evidence " + file, error); } }
    private static void write(MinecraftServer s, String name, CompoundTag t) { try { NbtIo.writeCompressed(t, s.getWorldPath(LevelResource.ROOT).resolve(name)); } catch (Exception error) { throw new IllegalStateException("cannot preserve " + name, error); } }
    private static void event(MinecraftServer s, String name, Object detail) {
        try { Files.writeString(s.getWorldPath(LevelResource.ROOT).resolve("natural-farm-consumption-restart.jsonl"),
                JSON.toJson(Map.of("phase", phase(), "event", name, "detail", detail)) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (Exception error) { throw new IllegalStateException("cannot retain consumption restart trace", error); }
    }
    private static void failure(MinecraftServer s, Exception error) {
        Sagadyssey.LOGGER.error("NATURAL_CONSUMPTION_RESTART_BLOCKED phase={} {}", phase(), error.toString());
        try { var t = new CompoundTag(); t.putString("Outcome", "BLOCKED"); t.putString("Reason", error.toString());
            if (s.overworld() != null) t.put("Links", links(s.overworld())); write(s, "consumption-blocked.nbt", t);
            event(s, "BLOCKED", Map.of("reason", error.toString(), "noCorrectiveMutation", true));
        } catch (Exception saving) { Sagadyssey.LOGGER.error("Consumption restart failure evidence incomplete", saving); }
    }
    private static int count(Container c) { int n = 0; if (c != null) for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.CARROT)) n += c.getItem(i).getCount(); return n; }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }

    private static CompoundTag links(ServerLevel l) {
        var s = l.getServer(); var t = new CompoundTag();
        t.put("NaturalLife", NaturalVillageLifeData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Economy", NaturalVillageEconomyData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Brain", NpcBrainData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Activities", LifeActivityData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Lod", NpcLodData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Life", NpcLifeData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Households", HouseholdData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Rights", RightsStateData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Supply", SupplyFlowData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Needs", NeedRequestData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Transfers", TransferLedgerData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Logistics", LogisticsData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Buildings", SettlementRegistryData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Characters", CharacterRegistryData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Fieldwork", FieldworkData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Polity", NaturalPolityData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Office", PoliticalStateData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Exchange", SettlementExchangeData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Conflict", NaturalConflictData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Encounters", CombatEncounterData.get(s).save(new CompoundTag(), s.registryAccess()));
        t.put("Injuries", InjuryData.get(s).save(new CompoundTag(), s.registryAccess())); return t;
    }
    private static CompoundTag comparable(CompoundTag source) {
        var copy = source.copy();
        for (var entry : copy.getCompound("Transfers").getList("Receipts", Tag.TAG_COMPOUND)) {
            var receipt = (CompoundTag) entry; var sorted = new TreeMap<UUID, CompoundTag>();
            for (var item : receipt.getList("Revisions", Tag.TAG_COMPOUND)) { var r = (CompoundTag) item; require(sorted.putIfAbsent(r.getUUID("Container"), r) == null, "unique revision map entry"); }
            var list = new ListTag(); sorted.values().forEach(list::add); receipt.put("Revisions", list);
        }
        var knowledge = new TreeMap<String, CompoundTag>();
        for (var entry : copy.getCompound("Supply").getList("Known", Tag.TAG_COMPOUND)) {
            var item = (CompoundTag) entry; var actor = SubjectRef.load(item.getCompound("Actor"));
            require(knowledge.putIfAbsent(actor.type() + ":" + actor.id() + ":" + item.getUUID("Point"), item) == null, "unique source knowledge entry");
        }
        var known = new ListTag(); knowledge.values().forEach(known::add); copy.getCompound("Supply").put("Known", known); return copy;
    }

}
