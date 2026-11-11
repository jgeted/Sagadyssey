package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.gametest.PortedGameTests.TestFunction;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData;
import com.jgeted.sagadyssey.npc.brain.life.LifeActivityData;
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
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;

import java.nio.file.*;
import java.util.*;

/** A finite v3 CARRYING checkpoint and a separate process continuation, never a replacement fixture on resume. */
@net.neoforged.fml.common.EventBusSubscriber(modid = "sagadyssey")
public final class NaturalFarmRestartGameTests {
    private static final String MARKER = "natural-farm-carry-checkpoint.nbt";
    private static final String SNAPSHOT = "world-natural-farm-carry-snapshot";
    private static final ItemFingerprint CARROT = new ItemFingerprint("minecraft:carrot", 0);
    private static CompoundTag restored;
    private static final Set<UUID> admitted = new HashSet<>();
    private static Run active;
    private static GameTestHelper running;
    private static boolean linkedRestartVerified;
    private NaturalFarmRestartGameTests() { }
    private static String phase() { return System.getProperty("sagadyssey.society.phase", ""); }
    private static boolean enabled() { return phase().equals("natural-carry-save") || phase().equals("natural-carry-resume"); }

    public static Collection<TestFunction> tests(boolean resume) {
        if (!enabled()) return List.of();
        require(resume == phase().equals("natural-carry-resume"), "explicit carry phase must match resume flag");
        return List.of(new TestFunction("natural_farm_restart", resume ? "original_cargo_process_resume" : "actual_unloaded_cargo_checkpoint",
                "sagadyssey_natural_life:empty", resume ? 6000 : 8000, 0, true, h -> {
                    running = h; active = new Run(h, resume);
                    // Wall-clock pacing gives asynchronous chunk unload work time; game ticks remain ordinary ticks.
                    LodContinuityP8GameTests.paced(h.getLevel());
                }));
    }

    @SubscribeEvent public static void admit(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) {
        if (!enabled() || !(e.getLevel() instanceof ServerLevel l) || !(l.getServer() instanceof GameTestServer)
                || !(e.getEntity() instanceof NpcBase n)) return;
        if (phase().equals("natural-carry-save")) { n.setNoAi(true); return; }
        if (admitted.contains(n.getUUID())) return;
        try {
            var saved = restored == null ? NbtIo.readCompressed(l.getServer().getWorldPath(LevelResource.ROOT).resolve(MARKER), NbtAccounter.unlimitedHeap()) : restored;
            var body = MinecraftApi.listOfType(saved, "Bodies", Tag.TAG_COMPOUND).stream().map(t -> (CompoundTag) t)
                    .filter(t -> t.read("UUID", UUIDUtil.CODEC).orElseThrow().equals(n.getUUID())).findFirst().orElseThrow();
            require(n.getCharacterId().equals(body.read("PersonProof", UUIDUtil.CODEC).orElseThrow()) && n.isNoAi() == body.getBooleanOr("AIProof", false), "same original identity and captured AI before any resumed work");
            require(NaturalVillageEconomyData.items(n.getPersonalInventory(), l.registryAccess()).equals(body.get("PersonalProof"))
                    && NaturalVillageEconomyData.items(n.getTransportInventory(), l.registryAccess()).equals(body.get("CargoProof")), "original personal and carried items at entity admission");
            admitted.add(n.getUUID());
            Sagadyssey.LOGGER.info("NATURAL_CARRY_ADMIT body={} person={} originalInventories=true beforeWork=true", n.getUUID(), n.getCharacterId());
        } catch (Exception ex) { throw new IllegalStateException("original checkpoint body required, no replacement", ex); }
    }

    @SubscribeEvent public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e) {
        if (!enabled() || !(e.getServer() instanceof GameTestServer)) return;
        active = null; running = null; restored = null; admitted.clear(); linkedRestartVerified = false;
        if (!phase().equals("natural-carry-resume")) return;
        var s = e.getServer(); var l = s.overworld();
        try {
            restored = NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve(MARKER), NbtAccounter.unlimitedHeap());
            require(ProcessHandle.current().pid() != restored.getLongOr("SavedProcess", 0L), "independent process, not an in-memory round trip");
            var expected = restored.getCompoundOrEmpty("Links").copy();
            for (var entry : MinecraftApi.listOfType(expected.getCompoundOrEmpty("Economy"), "Regions", Tag.TAG_COMPOUND)) {
                var row = (CompoundTag) entry; row.putBoolean("ObservedAway", true); row.putBoolean("ReturnRequested", false);
            }
            var actual = links(l);
            write(l, "natural-farm-carry-before-first-tick.nbt", actual);
            require(comparable(actual).equals(comparable(expected)), "complete linked state survives process restart before first tick");
            var disk = NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"), NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
            require(disk.getLongOr("Time", 0L) == restored.getLongOr("SavedTime", 0L), "disk game time matches the associated checkpoint");
            var rules = disk.getCompoundOrEmpty("game_rules");
            require(rules.equals(restored.getCompoundOrEmpty("SavedRules")), "disk rules match frozen checkpoint rules");
            MinecraftApi.loadRules(l, rules);
            s.getWorldData().overworldData().setGameTime(disk.getLongOr("Time", 0L));
            s.getWorldData().overworldData().setDayTime(disk.getLongOr("DayTime", 0L));
            require(MinecraftApi.saveRules(l).equals(rules) && l.getGameTime() == disk.getLongOr("Time", 0L)
                    && l.getDayTime() == disk.getLongOr("DayTime", 0L), "only actual saved time and rules restored; no offline wall-clock catchup");
            linkedRestartVerified = true;
            Sagadyssey.LOGGER.info("NATURAL_CARRY_RESTART sameLinkedWorld=true savedProcess={} currentProcess={} restoredTime={} restoredDay={} offlineCatchup=false",
                    restored.getLongOr("SavedProcess", 0L), ProcessHandle.current().pid(), l.getGameTime(), l.getDayTime());
        } catch (Exception ex) { throw new IllegalStateException("complete original carrying world required", ex); }
    }

    @SubscribeEvent public static void observe(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if (!enabled() || active == null) return;
        try { active.tick(); }
        catch (RuntimeException ex) {
            active = null; write(e.getServer().overworld(), "natural-farm-carry-failure-links.nbt", links(e.getServer().overworld()));
            Sagadyssey.LOGGER.error("NATURAL_CARRY_FAILURE {}", ex.toString());
            running.runAtTickTime(running.getTick() + 1, () -> { running.fail(ex.getMessage()); });
        }
    }

    private static final class Run {
        final GameTestHelper h; final ServerLevel l; final boolean resume; final NaturalVillageBuilding b;
        final ListTag events = new ListTag(); final List<NpcBase> departing = new ArrayList<>();
        int stage; long since, deliveredAt = -1; UUID routeId, setupTransfer; CompoundTag frozenPoint; ListTag savedBodies;
        Run(GameTestHelper helper, boolean resume) {
            h = helper; l = h.getLevel(); this.resume = resume; since = l.getGameTime();
            if (resume) {
                require(restored != null && linkedRestartVerified, "startup comparison completed before resumed case");
                b = NaturalVillageLifeData.decode(restored.getCompoundOrEmpty("Village")); routeId = restored.read("Route", UUIDUtil.CODEC).orElseThrow();
                setupTransfer = restored.read("SetupTransfer", UUIDUtil.CODEC).orElseThrow(); frozenPoint = restored.getCompoundOrEmpty("Point").copy();
                savedBodies = MinecraftApi.listOfType(restored, "Bodies", Tag.TAG_COMPOUND).copy(); events.addAll(MinecraftApi.listOfType(restored, "Events", Tag.TAG_COMPOUND));
                require(LodContinuityP8GameTests.away(l, b), "resumed world still has genuinely absent village entities and footprint");
                require(point().equals(frozenPoint), "original in-transit route and obligation before reload");
                event("RESUMED_STILL_UNLOADED"); NaturalVillageR1GameTests.visiting(l, b, true); stage = 4;
            } else b = NaturalVillageR1GameTests.fixture(h, 0, NaturalVillageLayout.FARM_V3);
        }
        NpcBase npc(int i) { return NaturalVillageR1GameTests.actor(l, b, i); }
        SupplyFlowData.Route route() {
            if (routeId != null) return SupplyFlowData.get(l.getServer()).route(routeId);
            return SupplyFlowData.get(l.getServer()).routes().stream()
                    .filter(r -> r.principal().equals(SubjectRef.household(NaturalVillageLifeData.home(b)))).findFirst().orElse(null);
        }
        CompoundTag point() {
            var r = route(); var t = new CompoundTag(); if (r == null) return t;
            var n = NeedRequestData.get(l.getServer()).request(r.need());
            t.store("Route", UUIDUtil.CODEC, r.id()); t.store("Need", UUIDUtil.CODEC, r.need()); t.putString("Stage", r.stage().name());
            t.putInt("Quantity", r.quantity()); t.putInt("Reserved", n.reserved()); t.putInt("Transit", n.inTransit()); t.putInt("Delivered", n.delivered());
            t.putBoolean("Pending", r.pending() != null);
            t.putInt("Cargo", npc(1) == null ? frozenPoint.getIntOr("Cargo", 0) : count(npc(1).getTransportInventory()));
            return t;
        }
        void event(String name) {
            var t = new CompoundTag(); t.putString("Event", name); t.putLong("Tick", l.getGameTime()); t.put("Point", point());
            var region = NaturalVillageEconomyData.get(l.getServer()).row(b.buildingKey()); if (region != null) t.put("Region", region);
            t.putBoolean("ActuallyAbsent", LodContinuityP8GameTests.away(l, b)); events.add(t);
            var trace = new CompoundTag(); trace.put("Events", events.copy()); write(l, "natural-farm-carry-events.nbt", trace);
            Sagadyssey.LOGGER.info("NATURAL_CARRY_EVENT name={} point={} actuallyAbsent={}", name, t.get("Point"), t.getBooleanOr("ActuallyAbsent", false));
        }
        void tick() {
            var s = l.getServer(); long now = l.getGameTime(); var economy = NaturalVillageEconomyData.get(s);
            if (stage == 0) {
                require(now - since < 800, "bounded formal initialization wait");
                if (h.getTick() < 160 || !LodContinuityP8GameTests.near(l, b)) return;
                originals(); require(count(npc(0).getPersonalInventory()) == 12 && count(store(1)) == 4
                        && LodContinuityP8GameTests.physical(l, b) == 16, "unchanged v3 natural initial twelve plus four");
                for (int i = 0; i < 3; i++) require(npc(i).isNoAi(), "initial admission held original AI before controlled setup");
                l.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, s); l.setDayTime(1000);
                var farmer = npc(0); var actor = SubjectRef.character(farmer.getCharacterId()); var rights = RightsStateData.get(s);
                UUID personal = ContainerIds.npcPersonal(farmer.getCharacterId()), raw = NaturalVillageEconomyData.storeId(b, 0);
                rights.ensureContext(personal, "npc:" + farmer.getCharacterId() + ":personal", actor, actor, AccessPolicy.PRIVATE);
                setupTransfer = UUID.randomUUID();
                var request = new TransferRequest(setupTransfer, actor, TransferIntent.GIFT, List.of(new TransferLeg(personal, raw, CARROT, 12)),
                        List.of(new ExpectedRevision(personal, rights.context(personal).revision()), new ExpectedRevision(raw, rights.context(raw).revision())),
                        "controlled initial redistribution: original twelve carrots from farmer personal bag to same farmer RAW; no added food", now);
                var receipt = new TransferService(rights, TransferLedgerData.get(s)).submit(request,
                        Map.of(personal, new TransferEndpoint(personal, farmer.getPersonalInventory()), raw, new TransferEndpoint(raw, store(0))), now);
                require(receipt.successful() && receipt.lawful() && count(store(0)) == 12 && count(store(1)) == 4
                        && count(farmer.getPersonalInventory()) == 0 && LodContinuityP8GameTests.physical(l, b) == 16, "real legal finite initial move");
                npc(1).setNoAi(false); since = now; stage = 1;
                event("INITIAL_12_RAW_4_PANTRY_COURIER_RELEASED");
            }
            if (stage == 1) {
                require(now - since < 2400, "ordinary original courier must actually pick up within frozen 2400 ticks");
                var r = route(); if (r == null) return;
                require(r.quantity() == 12, "original shortage and route remain exactly twelve"); routeId = r.id();
                if (r.stage() != SupplyFlowData.Stage.CARRYING) return;
                require(r.pending() == null && r.carrierEntity().equals(npc(1).getUUID()) && r.carrierCharacter().equals(npc(1).getCharacterId())
                        && count(npc(1).getTransportInventory()) == 12 && count(store(0)) == 0 && count(store(1)) == 4, "normal actual pickup into original courier cargo");
                npc(1).setNoAi(true); frozenPoint = point(); savedBodies = LodContinuityP8GameTests.bodies(l, b);
                departing.clear(); for (int i = 0; i < 3; i++) departing.add(npc(i));
                event("ACTUAL_CARRYING_BEFORE_TICKET_RELEASE"); NaturalVillageR1GameTests.visiting(l, b, false); since = now; stage = 2;
            }
            if (stage == 2) {
                require(now - since < 2400, "all original chunks and bodies must really unload within 2400 ticks");
                if (!LodContinuityP8GameTests.away(l, b) || departing.stream().anyMatch(n -> !n.isRemoved() || n.getRemovalReason() != Entity.RemovalReason.UNLOADED_TO_CHUNK)) return;
                require(point().equals(frozenPoint), "unload preserves original route, cargo and obligation");
                var row = economy.row(b.buildingKey()); require(!row.getBooleanOr("Farmer", false), "NoAI original farmer was not captured as virtual labor");
                for (var n : departing) { var checkpoint = NpcLodData.get(s).find(n.getUUID()); require(checkpoint != null && checkpoint.unloaded(), "original body has an unloaded checkpoint"); }
                event("TRUE_UNLOADED_ORIGINALS"); since = now; stage = 3;
            }
            if (stage == 3) {
                require(now - since < 2400, "bounded remote checkpoint observation");
                require(LodContinuityP8GameTests.away(l, b) && point().equals(frozenPoint), "real absence continues without mutating original obligation");
                var row = economy.row(b.buildingKey()); var clock = RemoteCropClock.load(row.getCompoundOrEmpty("Clock"));
                require(!row.getBooleanOr("Farmer", false) && clock.produced() == 0 && clock.planted() == 0 && row.getLongOr("Meals", 0L) == 0,
                        "this continuity case introduces no virtual produce, seed deduction or unobserved meals");
                require(LodContinuityP8GameTests.virtual(l, row) == 4 && frozenPoint.getIntOr("Cargo", 0) == 12, "one authoritative warehouse four plus frozen cargo twelve");
                if (now - since < 200 || economy.backlog(now) > 2) return;
                event("CHECKPOINT_READY"); var checkpoint = new CompoundTag();
                checkpoint.put("Village", NaturalVillageLifeData.get(s).village(b.buildingKey())); checkpoint.store("Route", UUIDUtil.CODEC, routeId);
                checkpoint.store("SetupTransfer", UUIDUtil.CODEC, setupTransfer); checkpoint.put("Point", frozenPoint.copy()); checkpoint.put("Bodies", savedBodies.copy()); checkpoint.put("Events", events.copy());
                checkpoint.putString("ControlledConditions", "real v3 fixture; original12 moved atomically into ownRAW, pantry4; no new food; farmer/worker NoAI throughout; courier normal until realCARRYING, then NoAI for unload; randomTickSpeed0; initialDay1000; daylight unchanged; fixture mob spawning disabled; tick rate200 wall pacing; no offline wallclock catchup");
                snapshot(l, checkpoint); active = null; h.succeed(); return;
            }
            if (stage == 4) {
                require(resume && now - since < 2400, "original return within frozen 2400 tick deadline");
                if (!LodContinuityP8GameTests.near(l, b)) return;
                for (int i = 0; i < 3; i++) { var checkpoint = NpcLodData.get(s).find(npc(i).getUUID()); if (checkpoint == null || checkpoint.unloaded()) return; require(!NpcLodRuntime.review(checkpoint), "normal entity admission did not enter LOD review"); }
                originals(); require(admitted.size() == 3 && point().equals(frozenPoint) && LodContinuityP8GameTests.physical(l, b) == 16, "same three admitted originals and exact physical inventory on return");
                for (int i = 0; i < 3; i++) {
                    require(npc(i).isNoAi() && NaturalVillageEconomyData.items(npc(i).getPersonalInventory(), s.registryAccess()).equals(savedBodies.getCompoundOrEmpty(i).get("PersonalProof"))
                            && NaturalVillageEconomyData.items(npc(i).getTransportInventory(), s.registryAccess()).equals(savedBodies.getCompoundOrEmpty(i).get("CargoProof")), "same original saved bags and cargo before releasing courier");
                }
                event("ORIGINALS_RETURNED_BEFORE_WORK"); npc(1).setNoAi(false); since = now; stage = 5;
            }
            if (stage == 5) {
                require(now - since < 2400, "normal original delivery must finish within frozen 2400 ticks");
                originals(); var r = route(); if (r.stage() != SupplyFlowData.Stage.DONE) return;
                var need = NeedRequestData.get(s).request(r.need());
                require(r.pending() == null && need.delivered() == 12 && need.reserved() == 0 && need.inTransit() == 0
                        && count(npc(1).getTransportInventory()) == 0 && count(store(0)) == 0 && count(store(1)) == 16
                        && LodContinuityP8GameTests.physical(l, b) == 16, "original actual twelve delivery, pantry sixteen, no cargo double counting");
                require(RightsStateData.get(s).reservationsForToken(r.token(), now).isEmpty(), "no orphan original reservation after real delivery");
                require(SupplyFlowData.get(s).routes().stream().filter(x -> x.principal().equals(r.principal())).count() == 1, "original route was not replaced by a new order");
                if (deliveredAt < 0) { deliveredAt = now; event("NORMAL_ORIGINAL_ROUTE_DONE"); }
                if (now - deliveredAt < 200) return;
                var result = new CompoundTag(); result.put("Village", NaturalVillageLifeData.get(s).village(b.buildingKey())); result.put("Events", events.copy()); result.put("Links", links(l));
                result.put("Bodies", LodContinuityP8GameTests.bodies(l, b)); result.putInt("PhysicalCarrots", 16); result.putInt("Delivered", 12);
                result.putBoolean("IndependentProcess", true); result.putBoolean("ActualUnloading", true); result.putLong("SavedProcess", restored.getLongOr("SavedProcess", 0L)); result.putLong("ResumedProcess", ProcessHandle.current().pid());
                write(l, "natural-farm-carry-result.nbt", result);
                s.saveEverything(true, true, true); 
                Sagadyssey.LOGGER.info("NATURAL_CARRY_PASS actualUnloaded=true fullProcessRestart=true originalRoute={} delivered=12 pantry=16 raw=0 cargo=0 reservations=0 duplicateObservation=200", r.id());
                active = null; h.succeed();
            }
        }
        Container store(int index) { return (Container) l.getBlockEntity(NaturalVillageLayout.at(b, NaturalVillageLayout.stores(b).get(index))); }
        void originals() {
            var home = HouseholdData.get(l.getServer()).byId(NaturalVillageLifeData.home(b));
            require(home != null && home.members().size() == 3 && b.settlementId().equals(home.settlementId()), "same original household and settlement");
            for (int i = 0; i < 3; i++) require(npc(i) != null && npc(i).isAlive() && npc(i).getCharacterId().equals(NaturalVillageLifeData.person(b, i))
                    && home.contains(npc(i).getCharacterId()) && CharacterRegistryData.get(l.getServer()).byId(npc(i).getCharacterId()).alive(), "same original living resident " + i);
        }
    }

    private static void snapshot(ServerLevel l, CompoundTag marker) {
        try {
            var s = l.getServer(); marker.putLong("SavedTime", l.getGameTime()); marker.putLong("SavedProcess", ProcessHandle.current().pid());
            marker.put("SavedRules", MinecraftApi.saveRules(l)); marker.put("Links", links(l)); write(l, MARKER, marker);
            s.saveEverything(true, true, true); 
            var world = s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(); var target = world.resolveSibling(SNAPSHOT);
            require(Files.notExists(target), "checkpoint destination must be new; never overwrite a prior world");
            Files.createDirectories(target);
            try (var files = Files.walk(world)) {
                for (var file : files.filter(Files::isRegularFile).toList()) {
                    if (file.getFileName().toString().equals("session.lock")) continue;
                    var destination = target.resolve(world.relativize(file)); Files.createDirectories(destination.getParent()); Files.copy(file, destination);
                }
            }
            Sagadyssey.LOGGER.info("NATURAL_CARRY_FULL_WORLD_CHECKPOINT path={} sameRoute={} savedTime={} process={} actualUnloaded=true", target, marker.read("Route", UUIDUtil.CODEC).orElseThrow(), marker.getLongOr("SavedTime", 0L), marker.getLongOr("SavedProcess", 0L));
        } catch (Exception ex) { throw new IllegalStateException("new associated checkpoint copy failed", ex); }
    }
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
    private static void write(ServerLevel l, String name, CompoundTag value) {
        try { NbtIo.writeCompressed(value, l.getServer().getWorldPath(LevelResource.ROOT).resolve(name)); }
        catch (Exception ex) { throw new IllegalStateException("cannot retain carry evidence " + name, ex); }
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
}
