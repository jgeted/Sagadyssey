package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;

import java.util.*;

/** Real vanilla Jigsaw generation/placement in an isolated test world; no manual registration. */
@GameTestHolder("sagadyssey_natural")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid = "sagadyssey")
public final class NaturalVillageRegistrationGameTests {
    private static final String CHECKPOINT = "vilg2-registration-checkpoint.nbt";
    private static final ResourceLocation TEMPLATE = ResourceLocation.parse("sagadyssey_natural:empty");

    @net.neoforged.bus.api.SubscribeEvent
    public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        if (!(event.getServer() instanceof GameTestServer)) return;
        var level = event.getServer().overworld();
        try {
            level.getStructureManager().getOrCreate(TEMPLATE).load(level.holderLookup(Registries.BLOCK),
                    TagParser.parseTag("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        if (System.getProperty("sagadyssey.naturalRegistration.phase", "baseline").equals("restart")) {
            return List.of(test("disk_restart_retains_identity", NaturalVillageRegistrationGameTests::restart));
        }
        return List.of(test("actual_jigsaw_chunks_and_checkpoint", NaturalVillageRegistrationGameTests::placement),
                test("candidates_failed_placements_and_scope", NaturalVillageRegistrationGameTests::negative),
                test("legacy_starts_stay_excluded", NaturalVillageRegistrationGameTests::legacy));
    }

    private static TestFunction test(String name, java.util.function.Consumer<GameTestHelper> body) {
        return new TestFunction("natural_registration", name, TEMPLATE.toString(), 300, 0, true, body);
    }

    private static StructureStart generate(GameTestHelper h, String id, int offset) {
        var l = h.getLevel();
        var structure = l.registryAccess().registryOrThrow(Registries.STRUCTURE).get(ResourceLocation.parse(id));
        var generator = l.getChunkSource().getGenerator();
        var chunk = new ChunkPos(h.absolutePos(new BlockPos(2048 + offset, 0, 2048)));
        // Explicitly select a site in the flat test world; use the shipped Jigsaw definition and templates.
        // This verifies placement/registration, not random-spread or biome distribution.
        var start = structure.generate(l.registryAccess(), generator, generator.getBiomeSource(),
                l.getChunkSource().randomState(), l.getStructureManager(), 7019L, chunk, 0, l, biome -> true);
        h.assertTrue(start.isValid() && start.getPieces().size() == 1, "supported vilg template is one physical building");
        return start;
    }

    private static NaturalVillageBuilding identity(ServerLevel l, StructureStart start) {
        var p = (PoolElementStructurePiece) start.getPieces().getFirst();
        var anchor = start.getChunkPos().getWorldPosition();
        return new NaturalVillageBuilding(l.dimension().location().toString(), "sagadyssey:vilg_2", anchor,
                new BlockPos(anchor.getX() + 8, p.getBoundingBox().minY(), anchor.getZ() + 8),
                NaturalVillageR1GameTests.template(l,start).toString(), p.getPosition(), p.getRotation());
    }

    private static void placeChunk(ServerLevel l, StructureStart start, ChunkPos chunk) {
        l.getChunk(chunk.x, chunk.z);
        var box = new BoundingBox(chunk.getMinBlockX(), l.getMinBuildHeight(), chunk.getMinBlockZ(),
                chunk.getMaxBlockX(), l.getMaxBuildHeight() - 1, chunk.getMaxBlockZ());
        start.placeInChunk(l, l.structureManager(), l.getChunkSource().getGenerator(), RandomSource.create(19), box, chunk);
    }

    private static List<ChunkPos> footprint(StructureStart start) {
        var box = start.getBoundingBox();
        var chunks = new ArrayList<ChunkPos>();
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) chunks.add(new ChunkPos(x, z));
        }
        return chunks;
    }

    private static void placement(GameTestHelper h) {
        var l = h.getLevel(); var data = SettlementRegistryData.get(l.getServer());
        var start = generate(h, "sagadyssey:vilg_2", 0);
        var piece = (PoolElementStructurePiece) start.getPieces().getFirst();
        // Move the generated piece so its actual footprint necessarily crosses a chunk boundary.
        piece.move(15 - (piece.getPosition().getX() & 15), 0, 15 - (piece.getPosition().getZ() & 15));
        var b = identity(l, start);
        h.assertTrue(data.building(b.buildingKey()) == null, "generation candidate is not registered");
        var originChunk = new ChunkPos(b.buildingOrigin());
        var chunks = footprint(start);
        h.assertTrue(chunks.size() > 1, "cross-chunk fixture");
        for (var chunk : chunks) if (!chunk.equals(originChunk)) placeChunk(l, start, chunk);
        h.assertTrue(data.building(b.buildingKey()) == null, "non-origin chunk callbacks do not invent extra buildings");
        placeChunk(l, start, originChunk);
        h.assertTrue(data.building(b.buildingKey()) != null, "successful real template placement registers the building");
        for (var chunk : chunks) placeChunk(l, start, chunk);
        h.assertTrue(data.buildingCount(b.settlementId()) == 1, "one physical building across callbacks");
        h.assertTrue(data.building(b.buildingKey()).category().equals("house"), "building is not the village container");
        h.assertTrue(data.byId(b.settlementId()) != null, "separate village identity exists");
        try {
            var marker = new CompoundTag();
            marker.putString("BuildingKey", b.buildingKey()); marker.putUUID("Settlement", b.settlementId());
            marker.put("Building", data.building(b.buildingKey()).save());
            marker.put("Village", data.byId(b.settlementId()).save());
            marker.put("Start", start.createTag(StructurePieceSerializationContext.fromLevel(l), start.getChunkPos()));
            h.assertTrue(marker.getCompound("Start").getInt(NaturalVillageRegistration.ELIGIBILITY_TAG) == 1,
                    "new start eligibility persists before future chunk placement");
            l.getServer().saveEverything(true, true, true);
            NbtIo.writeCompressed(marker, l.getServer().getWorldPath(LevelResource.ROOT).resolve(CHECKPOINT));
        } catch (Exception e) { throw new IllegalStateException(e); }
        h.succeed();
    }

    private static void negative(GameTestHelper h) {
        var l = h.getLevel(); var data = SettlementRegistryData.get(l.getServer());
        var start = generate(h, "sagadyssey:vilg_2", 256); var b = identity(l, start);
        var piece = (PoolElementStructurePiece) start.getPieces().getFirst();
        var cp = new ChunkPos(piece.getPosition()); l.getChunk(cp.x, cp.z);
        // Direct placement has no actual registered StructureStart context.
        piece.place(l, l.structureManager(), l.getChunkSource().getGenerator(), RandomSource.create(1),
                new BoundingBox(cp.getMinBlockX(), l.getMinBuildHeight(), cp.getMinBlockZ(),
                        cp.getMaxBlockX(), l.getMaxBuildHeight() - 1, cp.getMaxBlockZ()), piece.getPosition(), false);
        h.assertTrue(data.building(b.buildingKey()) == null, "unscoped template placement is not natural registration");
        var template = l.getStructureManager().getOrCreate(ResourceLocation.parse(b.templateId()));
        var original = template.save(new CompoundTag());
        try {
            template.load(l.holderLookup(Registries.BLOCK), TagParser.parseTag("{size:[0,0,0],entities:[],blocks:[],palette:[]}"));
            placeChunk(l, start, cp);
            h.assertTrue(data.building(b.buildingKey()) == null, "failed vanilla placement does not register");
        } catch (Exception e) { throw new IllegalStateException(e); }
        finally { template.load(l.holderLookup(Registries.BLOCK), original); }
        var other = generate(h, "sagadyssey:vilg_1", 512);
        int count = data.buildings().size();
        for (var chunk : footprint(other)) placeChunk(l, other, chunk);
        h.assertTrue(data.buildings().size() == count, "vilg_1 is outside this release");
        h.succeed();
    }

    private static void legacy(GameTestHelper h) {
        var l = h.getLevel(); var data = SettlementRegistryData.get(l.getServer());
        var context = StructurePieceSerializationContext.fromLevel(l);
        var start = generate(h, "sagadyssey:vilg_2", 768); var b = identity(l, start);
        var saved = start.createTag(context, start.getChunkPos());
        var pending = StructureStart.loadStaticStart(context, saved, 7019L);
        h.assertTrue(pending != null && pending.createTag(context, pending.getChunkPos())
                .getInt(NaturalVillageRegistration.ELIGIBILITY_TAG) == 1, "new pending starts survive serialization");
        saved.remove(NaturalVillageRegistration.ELIGIBILITY_TAG);
        var old = StructureStart.loadStaticStart(context, saved, 7019L);
        h.assertTrue(old != null && !old.createTag(context, old.getChunkPos())
                .contains(NaturalVillageRegistration.ELIGIBILITY_TAG), "saving an old start does not silently opt it in");
        for (var chunk : footprint(old)) placeChunk(l, old, chunk);
        h.assertTrue(data.building(b.buildingKey()) == null, "unmarked old structures are not backfilled");
        for (var chunk : footprint(pending)) placeChunk(l, pending, chunk);
        h.assertTrue(data.building(b.buildingKey()) != null, "eligible pending structure can finish after reload");
        h.succeed();
    }

    private static void restart(GameTestHelper h) {
        var l = h.getLevel(); var data = SettlementRegistryData.get(l.getServer());
        try {
            var marker = NbtIo.readCompressed(l.getServer().getWorldPath(LevelResource.ROOT).resolve(CHECKPOINT), NbtAccounter.unlimitedHeap());
            var id = marker.getUUID("Settlement"); var key = marker.getString("BuildingKey");
            h.assertTrue(data.byId(id) != null && data.byId(id).save().equals(marker.getCompound("Village")),
                    "village identity and ownership facts loaded from actual server SavedData");
            var loadedBuilding = data.building(key);
            h.assertTrue(loadedBuilding != null && loadedBuilding.equals(data.buildingAt(
                    loadedBuilding.dimension(), loadedBuilding.position(), loadedBuilding.structureId())),
                    "existing coordinate query can find a natural building after restart");
            h.assertTrue(data.building(key) != null && data.building(key).save().equals(marker.getCompound("Building")),
                    "building identity, position and affiliation survive a separate JVM");
            var start = StructureStart.loadStaticStart(StructurePieceSerializationContext.fromLevel(l), marker.getCompound("Start"), 7019L);
            h.assertTrue(start != null, "saved original structure start decodes");
            for (var chunk : footprint(start)) placeChunk(l, start, chunk);
            h.assertTrue(data.buildingCount(id) == 1 && data.building(key).save().equals(marker.getCompound("Building")),
                    "post-restart callbacks do not replace records or duplicate buildings");
        } catch (Exception e) { throw new IllegalStateException(e); }
        h.succeed();
    }
}
