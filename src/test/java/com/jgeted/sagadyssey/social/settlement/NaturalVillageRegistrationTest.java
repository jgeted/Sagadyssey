package com.jgeted.sagadyssey.social.settlement;

import com.jgeted.sagadyssey.core.structure.NaturalVillageBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NaturalVillageRegistrationTest {
    private NaturalVillageBuilding building(String dimension, int startX, int buildingX, Rotation rotation) {
        return new NaturalVillageBuilding(dimension, "sagadyssey:vilg_2", new BlockPos(startX, 0, 0),
                new BlockPos(startX + 8, 64, 8), "sagadyssey:vilg_2", new BlockPos(buildingX, 64, 0), rotation);
    }

    @Test void repeatedChunkCallbacksDoNotCreateExtraRecordsOrRewriteTime() {
        var data = new SettlementRegistryData();
        var b = building("minecraft:overworld", 0, 15, Rotation.NONE);
        assertTrue(data.registerPlacedVillage(b, 10));
        assertFalse(data.registerPlacedVillage(b, 20));
        assertEquals(1, data.allSettlements().size());
        assertEquals(1, data.buildingCount(b.settlementId()));
        assertEquals(10, data.building(b.buildingKey()).registeredGameTime());
    }

    @Test void nearbyStructureInstancesKeepSeparateSettlements() {
        var data = new SettlementRegistryData();
        var a = building("minecraft:overworld", 0, 15, Rotation.NONE);
        var b = building("minecraft:overworld", 16, 31, Rotation.NONE);
        data.registerPlacedVillage(a, 1); data.registerPlacedVillage(b, 1);
        assertNotEquals(a.settlementId(), b.settlementId());
        assertEquals(2, data.allSettlements().size());
        assertEquals(a.settlementId(), data.building(a.buildingKey()).settlementId());
        assertEquals(b.settlementId(), data.building(b.buildingKey()).settlementId());
    }

    @Test void dimensionsDoNotShareIdentity() {
        var data = new SettlementRegistryData();
        var a = building("minecraft:overworld", 0, 15, Rotation.NONE);
        var b = building("minecraft:the_nether", 0, 15, Rotation.NONE);
        data.registerPlacedVillage(a, 1); data.registerPlacedVillage(b, 1);
        assertNotEquals(a.settlementId(), b.settlementId());
        assertNotEquals(a.buildingKey(), b.buildingKey());
        assertEquals(2, data.buildings().size());
    }

    @Test void physicalBuildingsRemainDistinctWithinOneStructure() {
        var data = new SettlementRegistryData();
        var a = building("minecraft:overworld", 0, 1, Rotation.NONE);
        var b = building("minecraft:overworld", 0, 15, Rotation.CLOCKWISE_90);
        data.registerPlacedVillage(a, 1); data.registerPlacedVillage(b, 1);
        assertEquals(a.settlementId(), b.settlementId());
        assertEquals(1, data.allSettlements().size());
        assertEquals(2, data.buildingCount(a.settlementId()));
        assertEquals("house", data.building(a.buildingKey()).category());
    }

    @Test void compressedDiskReloadPreservesIdentityAndDeduplication(@TempDir Path dir) throws Exception {
        var data = new SettlementRegistryData();
        var b = building("minecraft:overworld", -16, -1, Rotation.COUNTERCLOCKWISE_90);
        data.registerPlacedVillage(b, 10);
        Path file = dir.resolve("settlements.dat");
        NbtIo.writeCompressed(data.save(new CompoundTag(), null), file);
        var restored = SettlementRegistryData.load(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()), null);
        assertEquals(data.byId(b.settlementId()), restored.byId(b.settlementId()));
        assertEquals(data.building(b.buildingKey()), restored.building(b.buildingKey()));
        assertEquals(data.building(b.buildingKey()), restored.buildingAt(b.dimension(), b.buildingOrigin(), b.structureId()));
        assertFalse(restored.registerPlacedVillage(b, 100));
        assertEquals(1, restored.buildingCount(b.settlementId()));
    }

    @Test void existingLegacyBuildingAndAffiliationSurviveNaturalRegistration() {
        var legacyId = java.util.UUID.randomUUID();
        var village = new SettlementRecord(legacyId, "Existing", SettlementType.VILLAGE, "minecraft:overworld",
                BlockPos.ZERO, 128, "sagadyssey:kingdom", null, 1);
        var oldBuilding = new BuildingRecord("legacy", "sagadyssey:old_house", "house", "small",
                "minecraft:overworld", new BlockPos(2, 64, 2), legacyId, 1);
        var saved = new CompoundTag();
        var settlements = new net.minecraft.nbt.ListTag(); settlements.add(village.save()); saved.put("Settlements", settlements);
        var buildings = new net.minecraft.nbt.ListTag(); buildings.add(oldBuilding.save()); saved.put("Buildings", buildings);
        var data = SettlementRegistryData.load(saved, null);
        data.registerPlacedVillage(building("minecraft:overworld", 0, 15, Rotation.NONE), 10);
        assertEquals(village, data.byId(legacyId));
        assertEquals(oldBuilding, data.building("legacy"));
        assertEquals(oldBuilding, data.buildingAt("minecraft:overworld", oldBuilding.position(), oldBuilding.structureId()));
        assertEquals(2, data.allSettlements().size());
    }

    @Test void emptyLegacyDataDoesNotInventVillages() {
        var data = SettlementRegistryData.load(new CompoundTag(), null);
        assertTrue(data.allSettlements().isEmpty());
        assertTrue(data.buildings().isEmpty());
    }

    @Test void unsupportedMappingsAndNoncanonicalAnchorsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NaturalVillageBuilding("minecraft:overworld",
                "sagadyssey:vilg_1", BlockPos.ZERO, BlockPos.ZERO, "sagadyssey:vilg_1", BlockPos.ZERO, Rotation.NONE));
        assertThrows(IllegalArgumentException.class, () -> building("minecraft:overworld", 1, 1, Rotation.NONE));
    }
}
