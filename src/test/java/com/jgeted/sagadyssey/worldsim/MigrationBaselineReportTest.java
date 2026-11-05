package com.jgeted.sagadyssey.worldsim;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MigrationBaselineReportTest {
    private static final UUID NPC_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NPC_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void digestIsIndependentOfScanOrder() {
        var firstContainers = List.of(
                new MigrationBaselineReport.ContainerSnapshot("b", 2, 36, 3),
                new MigrationBaselineReport.ContainerSnapshot("a", 1, 27, 1));
        var secondContainers = List.of(firstContainers.get(1), firstContainers.get(0));
        Map<String, Long> firstAssets = new LinkedHashMap<>();
        firstAssets.put("minecraft:arrow", 8L);
        firstAssets.put("minecraft:bread", 3L);
        Map<String, Long> secondAssets = new LinkedHashMap<>();
        secondAssets.put("minecraft:bread", 3L);
        secondAssets.put("minecraft:arrow", 8L);

        var first = MigrationBaselineReport.inspect("fixture", 1, List.of(NPC_A, NPC_B),
                2, firstContainers, firstAssets);
        var second = MigrationBaselineReport.inspect("fixture", 1, List.of(NPC_B, NPC_A),
                2, secondContainers, secondAssets);

        assertTrue(first.valid());
        assertEquals(first.digest(), second.digest());
    }

    @Test
    void detectsIdentityContainerAndQuantityViolations() {
        var report = MigrationBaselineReport.inspect("broken", 1, List.of(NPC_A, NPC_A), 0,
                List.of(
                        new MigrationBaselineReport.ContainerSnapshot("same", -1, 9, 10),
                        new MigrationBaselineReport.ContainerSnapshot("same", 0, 9, 0)),
                Map.of("minecraft:bread", -1L));

        assertFalse(report.valid());
        assertTrue(report.issues().contains("duplicate_npc_uuid"));
        assertTrue(report.issues().contains("unregistered_loaded_npc"));
        assertTrue(report.issues().contains("duplicate_container:same"));
        assertTrue(report.issues().contains("negative_revision:same"));
        assertTrue(report.issues().contains("invalid_slot_count:same"));
        assertTrue(report.issues().contains("negative_asset_count:minecraft:bread"));
    }
}
