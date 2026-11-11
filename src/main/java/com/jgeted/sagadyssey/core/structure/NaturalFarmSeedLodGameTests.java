package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.google.gson.Gson;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData;
import com.jgeted.sagadyssey.npc.brain.life.LifeActivityData;
import com.jgeted.sagadyssey.npc.brain.life.LifePlanFactory;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBrainData;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
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
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;

import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * NOT RUN. Supplemental same-process seed-bag continuity, not ordinary-world
 * discovery, autonomous farming acceptance, or the independent carry process restart.
 * Existing remote semantics remain unchanged. No original seed is transferred to RAW.
 * Only test fixture/AI admission/randomTickSpeed/tickets/pacing are controlled.
 * No time jump, crop edit, item/character recreation, rights grant or ledger reset.
 * This class has its own phase-selected generator using the existing natural
 * namespace and existing empty template installer; no production/provider change needed.
 */
@GameTestHolder("sagadyssey_natural_life")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid = "sagadyssey")
public final class NaturalFarmSeedLodGameTests {
    private static final String PHASE = "natural-seed-lod";
    private static final int UNLOAD_LIMIT = 2400, RETURN_LIMIT = 2400, REMOTE_MIN = 2000;
    private static final ItemFingerprint CARROT = new ItemFingerprint("minecraft:carrot", 0);
    private static final Gson JSON = new Gson();
    private static Run active;
    private static GameTestHelper running;
    private NaturalFarmSeedLodGameTests() {}
    private static boolean enabled() { return PHASE.equals(System.getProperty("sagadyssey.society.phase", "")); }

    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        if (!enabled()) return List.of();
        return List.of(new TestFunction("natural_farm_seed_lod", "original_twelve_seeds_true_unload_return",
                "sagadyssey_natural_life:empty", 8600, 0, true, h -> {
                    running = h;
                    active = new Run(h);
                    LodContinuityP8GameTests.paced(h.getLevel());
                }));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void admit(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!enabled() || active == null || !(event.getLevel() instanceof ServerLevel level)
                || level != active.level || !(level.getServer() instanceof GameTestServer)
                || !(event.getEntity() instanceof NpcBase npc)) return;
        if (active.index(npc.getUUID()) < 0) return;
        try {
            if (active.stage == 0) npc.setNoAi(true);
            else if (active.stage == 3) {
                active.admission(npc); // compare original saved state before changing even the test AI hold
                npc.setNoAi(true);
            } else throw new IllegalStateException("Original body admitted outside declared initialization/return boundary");
        } catch (RuntimeException failure) { active.block(failure); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void observe(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!enabled() || active == null || event.getServer() != active.level.getServer()) return;
        try { active.tick(); }
        catch (RuntimeException failure) { if (active != null) active.block(failure); }
    }

    private static final class Run {
        final GameTestHelper h;
        final ServerLevel level;
        final NaturalVillageBuilding village;
        final List<NpcBase> originals = new ArrayList<>();
        final Map<UUID, CompoundTag> frozenBodies = new LinkedHashMap<>();
        final Map<UUID, LodCheckpoint> frozenCheckpoints = new LinkedHashMap<>();
        final Set<UUID> admissions = new HashSet<>();
        final Set<String> meals = new HashSet<>();
        final ListTag events = new ListTag();
        final Map<String, CompoundTag> linkBoundaries = new LinkedHashMap<>();
        int stage;
        long since, remoteSince = -1, epoch = -1, returnAt = -1, lastTime;
        CompoundTag originalHousehold, originalBuilding, originalNaturalLife, frozenTitle;
        ListTag originalBeds;
        boolean blocked;
        boolean nearAuditDeferred;

        Run(GameTestHelper helper) {
            h = helper; level = h.getLevel(); since = lastTime = level.getGameTime();
            village = NaturalVillageR1GameTests.fixture(h, 0, NaturalVillageLayout.FARM_V3);
            // Declared continuity isolation only: preserve the 12 actual young crops rather than aging/replacing them.
            level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, level.getServer());
        }

        int index(UUID body) {
            for (int i = 0; i < 3; i++) if (NaturalVillageLifeData.body(village, i).equals(body)) return i;
            return -1;
        }
        NpcBase npc(int i) { return NaturalVillageR1GameTests.actor(level, village, i); }
        CompoundTag row() {
            var result = NaturalVillageEconomyData.get(level.getServer()).row(village.buildingKey());
            require(result != null, "registered economy row required");
            require(!result.getStringOr("Phase", "").equals("REVIEW"), "existing economy requires review: " + result.getStringOr("Reason", ""));
            return result;
        }

        void tick() {
            long now = level.getGameTime();
            require(now >= lastTime && now - lastTime <= 1, "ordinary ticks only; no time jump/catchup injection");
            lastTime = now;
            observeMealCommits();
            if (stage == 0) {
                require(now - since <= 800, "BLOCKED: original formal initialization exceeded 800 ticks");
                if (h.getTick() < 160 || !LodContinuityP8GameTests.near(level, village)) return;
                initializeBoundary();
                return;
            }
            verifyIdentityLinks();
            verifySeedTitle();
            require(count(originals.getFirst().getPersonalInventory()) == 12,
                    "BLOCKED: original 12 seed carrots changed before actual return; no corrective transfer allowed");
            if (stage == 1) {
                require(now - since <= UNLOAD_LIMIT, "BLOCKED: real body/footprint unload exceeded 2400 ticks");
                // A Post event may see an incomplete NEAR view while native unload callbacks run.
                // Do not reload a missing chunk to audit it, or count an incomplete physical view.
                // The original baseline and mandatory first REMOTE balance still cover every delta.
                if (!row().getStringOr("Phase", "").equals("NEAR") || physicalViewAvailable()) accounting();
                else if (!nearAuditDeferred) {
                    nearAuditDeferred = true;
                    event("NEAR_AUDIT_DEFERRED_TO_HANDOFF", Map.of("reason", "native unload transition; no chunk loads or invented stock"));
                }
                if (!actuallyAbsent()) return;
                var region = row();
                require(region.getBooleanOr("Farmer", false), "BLOCKED: ordinary active original farmer was not captured; do not fake remote labor");
                epoch = region.getLongOr("Epoch", 0L);
                for (var original : originals) {
                    var checkpoint = NpcLodData.get(level.getServer()).find(original.getUUID());
                    require(checkpoint != null && checkpoint.unloaded() && !NpcLodRuntime.review(checkpoint), "original unloaded checkpoint required");
                    require(checkpoint.person().equals(original.getCharacterId())
                            && checkpoint.inventoryDigest().equals(LodFingerprints.inventory(original)), "checkpoint covers this actual original inventory");
                    frozenCheckpoints.put(original.getUUID(), checkpoint);
                    CompoundTag proof = MinecraftApi.saveEntity(original);
                    proof.store("PersonProof", UUIDUtil.CODEC, original.getCharacterId()); proof.putBoolean("AIProof", original.isNoAi());
                    proof.put("PersonalProof", NaturalVillageEconomyData.items(original.getPersonalInventory(), level.registryAccess()));
                    proof.put("CargoProof", NaturalVillageEconomyData.items(original.getTransportInventory(), level.registryAccess()));
                    proof.put("AllItemsProof", LodFingerprints.inventoryTag(original));
                    frozenBodies.put(original.getUUID(), proof);
                }
                frozenTitle = personalTitle();
                remoteSince = now; since = now; stage = 2;
                evidence("TRUE_UNLOADED", true);
                return;
            }
            if (stage == 2) {
                require(now - since <= REMOTE_MIN + 400, "BLOCKED: remote 2000-tick observation/catchup boundary exceeded 2400 ticks");
                require(actuallyAbsent(), "BLOCKED: original body or footprint reappeared during remote observation");
                verifyFrozenCheckpoints();
                require(row().getLongOr("Epoch", 0L) == epoch, "single actual handoff epoch; no repeated virtual inventory copy");
                accounting();
                if ((now - remoteSince) % 200 == 0) evidence("REMOTE_SAMPLE", false);
                var clock = RemoteCropClock.load(row().getCompoundOrEmpty("Clock"));
                if (now - remoteSince < REMOTE_MIN || clock.settledAt() < remoteSince + REMOTE_MIN) return;
                require(clock.backlog(now) == 0, "remote clock caught up through ordinary service, not direct advance calls");
                evidence("REMOTE_2000_BEFORE_RETURN", true);
                since = now; stage = 3; // set before loading: admission is synchronous inside native chunk access
                NaturalVillageR1GameTests.visiting(level, village, true);
                return;
            }
            if (stage == 3) {
                require(now - since <= RETURN_LIMIT, "BLOCKED: original return exceeded 2400 ticks");
                if (!LodContinuityP8GameTests.near(level, village)) return;
                require(admissions.size() == 3, "three original deserialized admissions verified before work");
                for (int i = 0; i < 3; i++) {
                    var loaded = npc(i); var checkpoint = NpcLodData.get(level.getServer()).find(loaded.getUUID());
                    require(checkpoint != null && !checkpoint.unloaded() && !NpcLodRuntime.review(checkpoint), "normal LOD admission reconciles the original");
                    require(loaded != originals.get(i), "disk-evicted original is deserialized, not retained object masquerading as unload");
                    require(LodFingerprints.inventoryTag(loaded).equals(frozenBodies.get(loaded.getUUID()).get("AllItemsProof")),
                            "all original personal/cargo/equipment/overflow items survived return");
                    require(loaded.isNoAi(), "post-proof AI hold keeps the 200-tick duplicate observation separate from resumed farming");
                }
                verifyNoDuplicate(true); verifyProjectedStores(); accounting();
                returnAt = now; stage = 4;
                evidence("RETURNED_ORIGINALS", true);
                return;
            }
            if (stage == 4) {
                require(LodContinuityP8GameTests.near(level, village), "returned region remains NEAR");
                verifyNoDuplicate(true); verifyProjectedStores(); accounting();
                for (int i = 0; i < 3; i++) require(LodFingerprints.inventoryTag(npc(i)).equals(
                        frozenBodies.get(npc(i).getUUID()).get("AllItemsProof")), "no duplicate item projection after return");
                if (now - returnAt < 200) return;
                evidence("PASS", true);
                var result = report(); result.putBoolean("SameProcessOnly", true); result.putBoolean("CarryProcessRestartStillSeparate", true);
                write("natural-farm-seed-lod-result.nbt", result);
                level.getServer().saveEverything(true, true, true);
                
                Sagadyssey.LOGGER.info("NATURAL_SEED_LOD_PASS actualUnloaded=true remoteTicks={} originalSeedBag=12 originals=3 titlePreserved=true carrotBalance={} processRestart=false",
                        since - remoteSince, accounting());
                active = null; h.succeed();
            }
        }

        void initializeBoundary() {
            var server = level.getServer();
            for (int i = 0; i < 3; i++) {
                var original = npc(i);
                require(original != null && original.isAlive() && original.isNoAi(), "original initialization was held before controlled baseline");
                originals.add(original);
            }
            require(count(npc(0).getPersonalInventory()) == 12 && count(store(1)) == 4 && physical() == 16,
                    "unchanged finite original12 personal plus original4 PANTRY; no seed transfer to RAW");
            require(count(store(0)) == 0, "RAW stays empty at initial boundary");
            for (var relative : NaturalVillageLayout.crops()) {
                var state = level.getBlockState(NaturalVillageLayout.at(village, relative));
                require(state.is(Blocks.CARROTS) && state.getValue(CropBlock.AGE) == 0,
                        "BLOCKED: require all12 actual original Age0 seedlings; never reset Age to manufacture premise");
            }
            require(nearNet() == 0 && meals.isEmpty(), "no hidden near production or meal before controlled baseline");
            for (int i = 0; i < 3; i++) require(NpcLifeData.get(server).needs(npc(i).getCharacterId()).lastAteAt() < 0,
                    "no hidden original consumption before initial16 accounting boundary");
            originalHousehold = HouseholdData.get(server).byId(NaturalVillageLifeData.home(village)).save();
            originalBuilding = SettlementRegistryData.get(server).building(village.buildingKey()).save();
            originalNaturalLife = NaturalVillageLifeData.get(server).village(village.buildingKey());
            originalBeds = beds(); frozenTitle = personalTitle();
            verifyIdentityLinks(); verifySeedTitle(); verifyNoDuplicate(true);
            evidence("INITIAL_12_PERSONAL_4_PANTRY_12_YOUNG", true);
            // This release preserves the existing remote worker gate. It does not register virtual labor manually.
            npc(0).setNoAi(false);
            since = level.getGameTime(); stage = 1;
            NaturalVillageR1GameTests.visiting(level, village, false);
        }

        void admission(NpcBase loaded) {
            var proof = frozenBodies.get(loaded.getUUID());
            require(proof != null && loaded.getCharacterId().equals(proof.read("PersonProof", UUIDUtil.CODEC).orElseThrow()), "return uses same body/person");
            require(loaded.isNoAi() == proof.getBooleanOr("AIProof", false), "saved original AI state before declared post-admission hold");
            require(LodFingerprints.inventoryTag(loaded).equals(proof.get("AllItemsProof")), "original complete item payload at admission before work");
            require(admissions.add(loaded.getUUID()), "duplicate admission of an original body");
            require(personalTitle().equals(frozenTitle), "original personal title/claims/grants before return work");
            require(index(loaded.getUUID()) != 0 || count(loaded.getPersonalInventory()) == 12, "original farmer admits exactly12 seed carrots");
            event("ORIGINAL_ADMISSION", Map.of("body", loaded.getUUID(), "person", loaded.getCharacterId(),
                    "fullItemDigest", LodFingerprints.inventory(loaded), "beforeWork", true));
        }

        boolean actuallyAbsent() {
            if (!LodContinuityP8GameTests.away(level, village)) return false;
            if (originals.stream().anyMatch(n -> !n.isRemoved() || n.getRemovalReason() != Entity.RemovalReason.UNLOADED_TO_CHUNK)) return false;
            // Same region envelope as visiting(), including bed/house corners, not only the store/crop columns.
            var a = NaturalVillageLayout.at(village, BlockPos.ZERO);
            var end = NaturalVillageLayout.at(village, new BlockPos(25, 0, 21));
            for (int x = ((Math.min(a.getX(), end.getX()) - 12) >> 4) - 1; x <= ((Math.max(a.getX(), end.getX()) + 12) >> 4) + 1; x++)
                for (int z = ((Math.min(a.getZ(), end.getZ()) - 12) >> 4) - 1; z <= ((Math.max(a.getZ(), end.getZ()) + 12) >> 4) + 1; z++) {
                    if (level.getChunkSource().getChunkNow(x, z) != null
                            || ((NaturalVillageEconomyData.RetainedChunks) (Object) level.getChunkSource().chunkMap).sagadyssey$retained(ChunkPos.asLong(x, z)) != null) return false;
                }
            verifyNoDuplicate(false);
            return true;
        }

        void verifyFrozenCheckpoints() {
            for (var entry : frozenCheckpoints.entrySet()) {
                var before = entry.getValue(); var now = NpcLodData.get(level.getServer()).find(entry.getKey());
                require(now != null && now.unloaded() && !NpcLodRuntime.review(now) && before.body().equals(now.body())
                        && before.person().equals(now.person()) && before.inventoryDigest().equals(now.inventoryDigest())
                        && Objects.equals(before.plan(), now.plan()) && before.planRevision().equals(now.planRevision())
                        && before.lastCommittedStep() == now.lastCommittedStep() && before.assetRevisions().equals(now.assetRevisions())
                        && before.orderRevisions().equals(now.orderRevisions()) && before.reservations().equals(now.reservations()),
                        "remote summary advances needs only; original bag/plan/obligation boundary is unchanged");
            }
            require(personalTitle().equals(frozenTitle), "remote model must not retitle or spend personal seeds");
        }

        void verifyIdentityLinks() {
            var server = level.getServer(); var home = HouseholdData.get(server).byId(NaturalVillageLifeData.home(village));
            require(home != null && home.save().equals(originalHousehold) && home.members().size() == 3, "same original household links");
            require(SettlementRegistryData.get(server).building(village.buildingKey()).save().equals(originalBuilding), "same building registration/T0");
            require(NaturalVillageLifeData.get(server).village(village.buildingKey()).equals(originalNaturalLife), "same original natural-life record, no reschedule");
            require(beds().equals(originalBeds), "same original bed assignments");
            for (int i = 0; i < 3; i++) {
                var person = NaturalVillageLifeData.person(village, i); var body = NaturalVillageLifeData.body(village, i);
                var character = CharacterRegistryData.get(server).byId(person);
                require(character != null && character.alive() && home.contains(person), "original person remains alive and belongs to same home");
                var indexed = NpcLodData.get(server).bodyForPerson(person);
                require(indexed == null || indexed.equals(body), "no replacement body bound to original person");
            }
        }

        void verifyNoDuplicate(boolean loaded) {
            Map<UUID, Integer> counts = new HashMap<>();
            for (var world : level.getServer().getAllLevels()) for (var entity : world.getAllEntities()) {
                if (entity instanceof NpcBase npc) for (int i = 0; i < 3; i++) {
                    var person = NaturalVillageLifeData.person(village, i);
                    if (npc.getCharacterId().equals(person)) {
                        require(npc.getUUID().equals(NaturalVillageLifeData.body(village, i)), "no replacement/duplicate body for original person");
                        counts.merge(person, 1, Integer::sum);
                    }
                }
            }
            for (int i = 0; i < 3; i++) require(counts.getOrDefault(NaturalVillageLifeData.person(village, i), 0) == (loaded ? 1 : 0),
                    loaded ? "exactly one loaded body per original person" : "no original or substitute body remains loaded");
        }

        CompoundTag personalTitle() {
            var rights = RightsStateData.get(level.getServer()); Set<UUID> ids = new HashSet<>();
            for (int i = 0; i < 3; i++) { ids.add(ContainerIds.npcPersonal(NaturalVillageLifeData.person(village, i))); ids.add(ContainerIds.npcCargo(NaturalVillageLifeData.person(village, i))); }
            var out = new CompoundTag(); var contexts = new ListTag(); var claims = new ListTag(); var grants = new ListTag(); var reservations = new ListTag();
            rights.contexts().stream().filter(c -> ids.contains(c.containerId())).sorted(Comparator.comparing(ContainerContext::containerId)).forEach(c -> contexts.add(c.save()));
            rights.claims().stream().filter(c -> ids.contains(c.containerId())).sorted(Comparator.comparing(AssetClaim::claimId)).forEach(c -> claims.add(c.save()));
            rights.grants().stream().filter(c -> ids.contains(c.containerId())).sorted(Comparator.comparing(RightGrant::id)).forEach(c -> grants.add(c.save()));
            rights.reservations().stream().filter(c -> ids.contains(c.containerId())).sorted(Comparator.comparing(AssetReservation::id)).forEach(c -> reservations.add(c.save()));
            out.put("Contexts", contexts); out.put("Claims", claims); out.put("Grants", grants); out.put("Reservations", reservations); return out;
        }

        void verifySeedTitle() {
            var rights = RightsStateData.get(level.getServer()); var person = NaturalVillageLifeData.person(village, 0);
            var id = ContainerIds.npcPersonal(person); var owner = SubjectRef.character(person); var context = rights.context(id);
            require(context != null && context.defaultBeneficialOwner().equals(owner) && context.custodian().equals(owner)
                    && context.accessPolicy() == AccessPolicy.PRIVATE, "original farmer owns the personal seed bag; observer never manufactures missing title");
            Container bag = npc(0) != null ? npc(0).getPersonalInventory() : originals.getFirst().getPersonalInventory();
            var resolver = new RightsService(rights);
            for (int slot = 0; slot < bag.getContainerSize(); slot++) if (bag.getItem(slot).is(Items.CARROT)) {
                require(resolver.can(owner, AssetRight.POSSESS, id, slot, CARROT, level.getGameTime()).status() == RightsVerdict.Status.ALLOW,
                        "original seed ownership resolves through existing rights");
                var claim = rights.claimAt(id, slot, CARROT);
                require(claim == null || claim.beneficialOwner().equals(owner) && claim.quantity() == bag.getItem(slot).getCount(), "seed claim matches actual original stack");
                for (int peer = 1; peer < 3; peer++) require(resolver.can(SubjectRef.character(NaturalVillageLifeData.person(village, peer)),
                        AssetRight.TRANSFER, id, slot, CARROT, level.getGameTime()).status() != RightsVerdict.Status.ALLOW,
                        "household membership is not a new personal seed transfer grant");
            }
        }

        ListTag beds() {
            var result = new ListTag(); var life = NpcLifeData.get(level.getServer());
            for (int i = 0; i < 3; i++) {
                var record = life.record(NaturalVillageLifeData.person(village, i));
                require(record != null && record.bedPosition() != null, "original bed registration exists");
                var tag = new CompoundTag(); tag.store("Person", UUIDUtil.CODEC, record.npcId()); tag.putString("Dimension", record.dimension());
                tag.putLong("Bed", record.bedPosition().asLong()); result.add(tag);
            }
            return result;
        }

        void observeMealCommits() {
            var brain = NpcBrainData.get(level.getServer());
            for (int i = 0; i < 3; i++) {
                var body = NaturalVillageLifeData.body(village, i); List<NpcPlan> plans = new ArrayList<>(brain.history(body));
                brain.active(body).ifPresent(plans::add);
                for (var plan : plans) if (plan.providerId().equals(LifePlanFactory.PROVIDER)) for (var step : plan.steps()) {
                    String operation = step.params().get("operation"), key = plan.id() + ":" + step.index();
                    if (Set.of("EAT", "CARE").contains(operation == null ? "" : operation)
                            && "DONE".equals(LifeActivityData.get(level.getServer()).settlement(key)) && meals.add(key))
                        event("NEAR_ACTUAL_MEAL", Map.of("key", key, "body", body, "source", step.params().getOrDefault("source", "BAG")));
                }
            }
        }

        long nearNet() {
            long result = 0;
            for (int i = 0; i < 3; i++) for (var receipt : FieldworkData.get(level.getServer()).receiptsFor(NaturalVillageLifeData.body(village, i)))
                for (var delta : receipt.inventoryDeltas()) if (delta.fingerprint().equals("minecraft:carrot#0")) result += delta.quantity();
            return result;
        }

        CompoundTag accounting() {
            var region = row(); long produced = 0, planted = 0, remoteMeals = region.getLongOr("Meals", 0L);
            if (region.contains("Clock")) {
                var clock = RemoteCropClock.load(region.getCompoundOrEmpty("Clock")); produced = clock.produced(); planted = clock.planted();
                long receiptOutputs = 0, receiptSeeds = 0, receiptMeals = 0;
                for (var raw : MinecraftApi.listOfType(region, "Receipts", Tag.TAG_COMPOUND)) {
                    var receipt = (CompoundTag) raw; String kind = receipt.getStringOr("Kind", "");
                    if (kind.equals("HARVEST_REPLANT") || kind.equals("PLANT")) {
                        receiptOutputs += receipt.getIntOr("Output", 0); receiptSeeds += receipt.getIntOr("Input", 0);
                    } else if (kind.equals("MEAL")) { require(receipt.getIntOr("Input", 0) == 1, "one real carrot per remote meal receipt"); receiptMeals++; }
                }
                require(receiptOutputs == produced && receiptSeeds == planted && receiptMeals == remoteMeals,
                        "remote gross/seed/meal counters each match actual existing receipts");
            }
            long stored;
            if (region.getStringOr("Phase", "").equals("NEAR")) stored = physical();
            else {
                require(Set.of("REMOTE", "RETURNING").contains(region.getStringOr("Phase", "")), "one recognized region authority");
                stored = LodContinuityP8GameTests.virtual(level, region);
                for (int i = 0; i < 3; i++) stored += mobile(npc(i) == null ? originals.get(i) : npc(i));
            }
            long net = nearNet();
            require(16 + net + produced - planted == stored + meals.size() + remoteMeals,
                    "BLOCKED: exact carrot balance initial16 + nearNet + remoteGross - seeds != authoritative stock + near/remote actual meals");
            var out = new CompoundTag(); out.putInt("InitialStoredCarrots", 16); out.putLong("NearFieldworkNet", net);
            out.putLong("RemoteProduced", produced); out.putLong("RemotePlanted", planted); out.putLong("RemoteMeals", remoteMeals);
            out.putInt("NearMealCommits", meals.size()); out.putLong("AuthoritativeStored", stored); out.putString("Authority", region.getStringOr("Phase", "")); return out;
        }

        int physical() {
            int total = 0;
            for (int i = 0; i < NaturalVillageLayout.stores(village).size(); i++) total += count(store(i));
            for (int i = 0; i < 3; i++) { var n = npc(i); require(n != null, "physical accounting needs each original loaded body"); total += mobile(n); }
            // The declared fieldwork paths put output into bags. A dropped carrot is a new accounting branch, not silently ignored stock.
            for (var entity : level.getAllEntities()) if (entity instanceof ItemEntity item && item.getItem().is(Items.CARROT)
                    && item.blockPosition().distSqr(village.buildingOrigin()) < 128 * 128)
                throw new IllegalStateException("BLOCKED: unexpected loose carrot stack; retain evidence, do not erase or infer consumption");
            return total;
        }
        boolean physicalViewAvailable() {
            for (var relative : NaturalVillageLayout.stores(village)) {
                var at = NaturalVillageLayout.at(village, relative);
                var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
                if (chunk == null || !(chunk.getBlockEntity(at) instanceof Container)) return false;
            }
            for (int i = 0; i < 3; i++) if (npc(i) == null) return false;
            return true;
        }
        int mobile(NpcBase n) {
            int total = count(n.getPersonalInventory()) + count(n.getTransportInventory());
            for (var slot : EquipmentSlot.values()) if (n.getItemBySlot(slot).is(Items.CARROT)) total += n.getItemBySlot(slot).getCount();
            for (var stack : n.getInventoryMigrationOverflowSnapshot()) if (stack.is(Items.CARROT)) total += stack.getCount();
            return total;
        }
        Container store(int index) {
            var at = NaturalVillageLayout.at(village, NaturalVillageLayout.stores(village).get(index));
            require(level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) != null, "physical audit never loads a missing store chunk");
            require(level.getBlockEntity(at) instanceof Container, "actual original store exists");
            return (Container) level.getBlockEntity(at);
        }
        void verifyProjectedStores() {
            var region = row();
            for (int i = 0; i < NaturalVillageLayout.stores(village).size(); i++) require(NaturalVillageEconomyData.items(store(i), level.registryAccess())
                    .equals(MinecraftApi.listOfType(region, "Stores", Tag.TAG_COMPOUND).getCompoundOrEmpty(i).get("Items")), "one actual final warehouse projection, every item type preserved");
            require(count(npc(0).getPersonalInventory()) == 12, "return has the original12 personal seeds, separate from remote warehouse projection");
            require(personalTitle().equals(frozenTitle), "personal rights are unchanged by return projection");
        }

        void evidence(String name, boolean includeLinks) {
            var tag = new CompoundTag(); tag.putString("Event", name); tag.putLong("GameTime", level.getGameTime());
            tag.putInt("Stage", stage); tag.put("Region", row()); tag.put("Accounting", accounting());
            tag.putBoolean("ActualAbsence", stage == 2 && actuallyAbsent()); tag.put("PersonalTitle", personalTitle());
            tag.putLong("ActualRemoteTicks", remoteSince < 0 ? 0 : level.getGameTime() - remoteSince);
            var needs = new ListTag(); for (int i = 0; i < 3; i++) needs.add(NpcLifeData.get(level.getServer()).needs(NaturalVillageLifeData.person(village, i)).save());
            tag.put("Needs", needs); events.add(tag);
            if (includeLinks) {
                var state = comparable(links(level)); require(state.keySet().size() == 21, "all21 linked categories captured");
                linkBoundaries.put(name, state); write("natural-farm-seed-lod-" + name.toLowerCase(Locale.ROOT) + "-links.nbt", state);
            }
            write("natural-farm-seed-lod-events.nbt", report());
            event(name, Map.of("gameTime", level.getGameTime(), "stage", stage, "regionPhase", row().getStringOr("Phase", ""),
                    "accounting", tag.getCompoundOrEmpty("Accounting").toString(), "actualRemoteTicks", tag.getLongOr("ActualRemoteTicks", 0L)));
        }
        CompoundTag report() {
            var out = new CompoundTag(); out.put("Village", NaturalVillageLifeData.get(level.getServer()).village(village.buildingKey()));
            out.put("Events", events.copy()); var bodies = new ListTag(); frozenBodies.values().forEach(t -> bodies.add(t.copy())); out.put("FrozenBodies", bodies);
            if (frozenTitle != null) out.put("FrozenPersonalTitle", frozenTitle.copy());
            var boundaries = new CompoundTag(); linkBoundaries.forEach((name, state) -> boundaries.put(name, state.copy())); out.put("LinkedBoundaries", boundaries);
            out.putString("Scope", "original12 personal seeds; same-process true unload/2000remote/return; no extension of remote labor/inventory semantics; carry restart remains separate");
            return out;
        }
        void block(RuntimeException failure) {
            if (blocked) return; blocked = true;
            try {
                var out = report(); out.putString("Outcome", "BLOCKED"); out.putString("Reason", failure.toString());
                out.put("FailureLinks", comparable(links(level))); write("natural-farm-seed-lod-blocked.nbt", out);
                event("BLOCKED", Map.of("reason", failure.toString(), "stage", stage, "noCorrectiveMutation", true));
                level.getServer().saveEverything(true, true, true);
            } catch (Exception saving) { Sagadyssey.LOGGER.error("Seed LOD blocked evidence could not be fully saved", saving); }
            Sagadyssey.LOGGER.error("NATURAL_SEED_LOD_BLOCKED {}", failure.toString()); active = null;
            running.runAtTickTime(running.getTick() + 1, () -> { running.fail(failure.getMessage()); });
        }
        void event(String name, Object detail) {
            try { Files.writeString(level.getServer().getWorldPath(LevelResource.ROOT).resolve("natural-farm-seed-lod.jsonl"),
                    JSON.toJson(Map.of("event", name, "gameTime", level.getGameTime(), "detail", detail)) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (Exception error) { throw new IllegalStateException("cannot retain seed LOD evidence", error); }
        }
        void write(String name, CompoundTag data) {
            try { NbtIo.writeCompressed(data, level.getServer().getWorldPath(LevelResource.ROOT).resolve(name)); }
            catch (Exception error) { throw new IllegalStateException("cannot retain " + name, error); }
        }
    }

    /** Same21 state families as carry restart; snapshots are evidence, never reloaded into live state. */
    private static CompoundTag links(ServerLevel level) {
        var s = level.getServer(); var t = new CompoundTag();
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
    /** Same map-order normalization as carry, not an excuse to drop domain fields. */
    private static CompoundTag comparable(CompoundTag source) {
        var copy = source.copy();
        for (var entry : MinecraftApi.listOfType(copy.getCompoundOrEmpty("Transfers"), "Receipts", Tag.TAG_COMPOUND)) {
            var receipt = (CompoundTag) entry; var sorted = new TreeMap<UUID, CompoundTag>();
            for (var item : MinecraftApi.listOfType(receipt, "Revisions", Tag.TAG_COMPOUND)) { var r = (CompoundTag) item; require(sorted.putIfAbsent(r.read("Container", UUIDUtil.CODEC).orElseThrow(), r) == null, "unique revision map entry"); }
            var list = new ListTag(); sorted.values().forEach(list::add); receipt.put("Revisions", list);
        }
        var knowledge = new TreeMap<String, CompoundTag>();
        for (var entry : MinecraftApi.listOfType(copy.getCompoundOrEmpty("Supply"), "Known", Tag.TAG_COMPOUND)) {
            var item = (CompoundTag) entry; var actor = SubjectRef.load(item.getCompoundOrEmpty("Actor"));
            require(knowledge.putIfAbsent(actor.type() + ":" + actor.id() + ":" + item.read("Point", UUIDUtil.CODEC).orElseThrow(), item) == null, "unique source knowledge entry");
        }
        var known = new ListTag(); knowledge.values().forEach(known::add); copy.getCompoundOrEmpty("Supply").put("Known", known); return copy;
    }
    private static int count(Container c) { int n = 0; for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.CARROT)) n += c.getItem(i).getCount(); return n; }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
}
