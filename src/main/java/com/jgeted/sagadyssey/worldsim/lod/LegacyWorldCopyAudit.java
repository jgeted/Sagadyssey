package com.jgeted.sagadyssey.worldsim.lod;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.*;

import java.nio.file.*;
import java.util.*;

/** Explicit development-only audit of a complete old-world COPY. No synthetic actors or work. */
@net.neoforged.fml.common.EventBusSubscriber(modid = "sagadyssey")
public final class LegacyWorldCopyAudit {
    private static final String PHASE = System.getProperty("sagadyssey.phase10.copyAudit", "");
    private static final boolean ENABLED = Set.of("apply", "restart", "rollback").contains(PHASE);
    private static final Map<UUID, CompoundTag> ORIGINALS = new LinkedHashMap<>();
    private static final Map<ResourceKey<net.minecraft.world.level.Level>, Set<Long>> ADDED_CHUNKS = new HashMap<>();
    private static boolean finished;
    private static long started;
    private static CompoundTag previous;

    private static boolean enabled() { return ENABLED; }

    @SubscribeEvent
    public static void prepare(ServerAboutToStartEvent event) throws Exception {
        if (!enabled()) return;
        var root = event.getServer().getWorldPath(LevelResource.ROOT).toRealPath();
        if (!root.getFileName().toString().equals("world")
                || !root.getParent().getParent().getFileName().toString().equals("phase10-migration")
                || event.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) {
            throw new IllegalStateException("Copy audit requires an isolated full-world normal server, never GameTest or run/saves");
        }
        var input = NbtIo.readCompressed(root.resolve("phase10-copy-manifest.nbt"), NbtAccounter.unlimitedHeap());
        if (Path.of(input.getString("Source")).toRealPath().equals(root)) throw new IllegalStateException("Cannot audit source in place");
        ORIGINALS.clear(); ADDED_CHUNKS.clear(); finished = false;
        for (var raw : input.getList("Originals", Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) raw;
            if (ORIGINALS.put(row.getUUID("Body"), row) != null) throw new IllegalArgumentException("Duplicate source body");
        }
        if (ORIGINALS.isEmpty() || ORIGINALS.size() > 100) throw new IllegalArgumentException("Require 1..100 actual source originals");
        previous = PHASE.equals("apply") ? null
                : NbtIo.readCompressed(root.resolve("phase10-copy-audit.nbt"), NbtAccounter.unlimitedHeap());
        Sagadyssey.LOGGER.info("PHASE10_COPY_AUDIT_START phase={} originals={} source={} copy={}", PHASE, ORIGINALS.size(), input.getString("Source"), root);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void freeze(EntityJoinLevelEvent event) {
        if (enabled() && !event.getLevel().isClientSide() && event.getEntity() instanceof NpcBase n
                && ORIGINALS.containsKey(n.getUUID())) n.setNoAi(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void freezePhysicalTick(EntityTickEvent.Pre event) {
        // Explicit paused copy: prevent incidental physics, combat or asset work while comparing
        // migration. This is not a production-performance or physical-work test.
        if (enabled() && event.getEntity() instanceof NpcBase n && !n.level().isClientSide()
                && ORIGINALS.containsKey(n.getUUID())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        if (!enabled()) return;
        started = System.nanoTime();
        var server = event.getServer();
        for (var row : ORIGINALS.values()) {
            var level = server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.parse(row.getString("Dimension"))));
            if (level == null) throw new IllegalStateException("Original dimension unavailable");
            var pos = row.getCompound("SourceEntity").getList("Pos", Tag.TAG_DOUBLE);
            var center = new ChunkPos(BlockPos.containing(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2)));
            var added = ADDED_CHUNKS.computeIfAbsent(level.dimension(), key -> new HashSet<>());
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                int x = center.x + dx, z = center.z + dz;
                if (!level.getForcedChunks().contains(ChunkPos.asLong(x, z))) {
                    added.add(ChunkPos.asLong(x, z)); level.setChunkForced(x, z, true);
                }
                level.getChunk(x, z);
            }
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!enabled() || finished || started == 0L) return;
        var server = event.getServer();
        try {
            var actors = new ArrayList<NpcBase>();
            for (var row : ORIGINALS.values()) {
                NpcBase found = null;
                for (var level : server.getAllLevels()) if (level.getEntity(row.getUUID("Body")) instanceof NpcBase n) found = n;
                if (found == null) {
                    if (System.nanoTime() - started > 90_000_000_000L) throw new IllegalStateException("Original body failed to load: " + row.getUUID("Body"));
                    return;
                }
                require(found.getCharacterId().equals(row.getUUID("Person")), "Original person identity changed");
                require(LodFingerprints.inventory(found).equals(expectedInventory(found, row.getCompound("SourceEntity"))),
                        "Original source inventory/components changed: " + found.getUUID());
                require(found.isNoAi(), "Explicit paused originals only");
                if (NpcBrainRuntime.isPlanned(found)) require(NpcLodRuntime.loaded(found), "Original planned checkpoint requires review");
                actors.add(found);
            }
            var targets = actors.stream().filter(n -> ORIGINALS.get(n.getUUID()).getBoolean("Selected")).toList();
            require(targets.size() == 3, "Three original legacy production professions required");
            for (var n : actors) if (!targets.contains(n)) require(NpcBrainRuntime.mode(n).name()
                    .equals(ORIGINALS.get(n.getUUID()).getString("OriginalMode")), "Unselected compatibility mode changed");
            var report = new CompoundTag();
            if (PHASE.equals("apply")) {
                for (var n : targets) require(NpcBrainRuntime.mode(n) == NpcBehaviorMode.LEGACY, "Do not convert replacement or already migrated targets");
                var before = LodMigration.counts(server, targets);
                var revision = LodMigration.dryRun(server, targets);
                require(revision.equals(LodMigration.dryRun(server, targets)), "Dry-run must be stable and read-only");
                var receipt = LodMigration.apply(server, targets, revision);
                var row = LodMigrationData.get(server).find(receipt);
                require(before.equals(row.getCompound("BeforeCounts")) && before.equals(row.getCompound("AfterCounts")), "Migration counts changed");
                report.putUUID("Receipt", receipt); report.put("Applied", row);
                report.putString("SelectedRevision", LodMigration.dryRun(server, targets));
            } else {
                UUID receipt = previous.getUUID("Receipt");
                var row = LodMigrationData.get(server).find(receipt);
                require(row != null && row.getString("State").equals("APPLIED"), "Original APPLIED receipt missing after real JVM restart");
                for (var n : targets) require(NpcBrainRuntime.isPlanned(n) && NpcBrainRuntime.data(server).active(n.getUUID()).isEmpty(), "Migrated original mode/idle boundary lost");
                require(row.getCompound("AfterCounts").equals(LodMigration.counts(server, targets)), "Reloaded counts differ");
                require(previous.getString("SelectedRevision").equals(LodMigration.dryRun(server, targets)), "Reloaded migration revision differs");
                report = previous.copy();
                if (PHASE.equals("rollback")) {
                    LodMigration.rollback(server, receipt);
                    require(LodMigrationData.get(server).find(receipt).getString("State").equals("ROLLED_BACK"), "Rollback receipt not retained");
                    for (var n : targets) require(NpcBrainRuntime.mode(n) == NpcBehaviorMode.LEGACY, "Original mode not restored");
                    report.put("RolledBack", LodMigrationData.get(server).find(receipt));
                }
            }
            for (var n : actors) require(LodFingerprints.inventory(n).equals(expectedInventory(n,
                    ORIGINALS.get(n.getUUID()).getCompound("SourceEntity"))), "Migration altered physical assets");
            report.putInt("OriginalBodies", actors.size()); report.putString("LastVerifiedPhase", PHASE);
            report.put("SelectedCounts", LodMigration.counts(server, targets));
            NbtIo.writeCompressed(report, server.getWorldPath(LevelResource.ROOT).resolve("phase10-copy-audit.nbt"));
            // Remove only tickets this harness added; preserve pre-existing source force-loads.
            for (var entry : ADDED_CHUNKS.entrySet()) for (long packed : entry.getValue()) {
                var chunk = new ChunkPos(packed); server.getLevel(entry.getKey()).setChunkForced(chunk.x, chunk.z, false);
            }
            if (PHASE.equals("rollback")) for (var n : actors) n.setNoAi(ORIGINALS.get(n.getUUID()).getBoolean("OriginalNoAI"));
            server.saveEverything(true, true, true);
            net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            finished = true;
            Sagadyssey.LOGGER.info("PHASE10_COPY_AUDIT_PASS phase={} originals={} counts={} receipt={}", PHASE, actors.size(), report.getCompound("SelectedCounts"), report.getUUID("Receipt"));
            server.halt(false);
        } catch (Exception exception) {
            finished = true;
            Sagadyssey.LOGGER.error("PHASE10_COPY_AUDIT_FAILED phase=" + PHASE, exception);
            server.halt(false);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static String expectedInventory(NpcBase npc, CompoundTag source) {
        var slots = new ListTag();
        var bag = NonNullList.withSize(36, ItemStack.EMPTY);
        var cargo = NonNullList.withSize(27, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(source, bag, npc.registryAccess());
        ContainerHelper.loadAllItems(source.getCompound("TransportCargo"), cargo, npc.registryAccess());
        bag.forEach(stack -> slots.add(saved(npc, stack))); cargo.forEach(stack -> slots.add(saved(npc, stack)));
        for (var slot : EquipmentSlot.values()) {
            Tag raw;
            if (slot == EquipmentSlot.BODY) raw = source.getCompound("body_armor_item");
            else {
                var list = source.getList(slot.getType() == EquipmentSlot.Type.HAND ? "HandItems" : "ArmorItems", Tag.TAG_COMPOUND);
                raw = slot.getIndex() < list.size() ? list.getCompound(slot.getIndex()) : new CompoundTag();
            }
            slots.add(saved(npc, raw instanceof CompoundTag compound && compound.isEmpty()
                    ? ItemStack.EMPTY : ItemStack.parse(npc.registryAccess(), raw).orElse(ItemStack.EMPTY)));
        }
        for (var raw : source.getList("InventoryMigrationOverflow", Tag.TAG_COMPOUND))
            slots.add(saved(npc, ItemStack.parse(npc.registryAccess(), raw).orElse(ItemStack.EMPTY)));
        var tag = new CompoundTag(); tag.put("OrderedSlots", slots); return LodFingerprints.of(tag);
    }

    private static Tag saved(NpcBase npc, ItemStack stack) {
        return stack.isEmpty() ? new CompoundTag() : stack.save(npc.registryAccess());
    }
}
