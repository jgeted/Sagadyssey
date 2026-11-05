package com.jgeted.sagadyssey.worldsim.verifier;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifierSnapshotTest {
    @Test
    void snapshotIsDeterministicAndDefensivelyImmutable() {
        Map<String, VerifierSnapshot.ContainerState> containers = new java.util.HashMap<>();
        containers.put("b", state("warehouse", 2L, Map.of("minecraft:bread@0", 4L)));
        containers.put("a", state("npc_personal", 1L, Map.of("minecraft:emerald@0", 2L)));
        Map<String, Long> assets = new java.util.HashMap<>(Map.of(
                "minecraft:bread@0", 4L, "minecraft:emerald@0", 2L));

        VerifierSnapshot first = snapshot("before", 10L, containers, assets,
                Map.of("beliefs", 3L), List.of("z", "a"));
        VerifierSnapshot second = snapshot("before", 10L, containers, assets,
                Map.of("beliefs", 3L), List.of("z", "a"));

        assertEquals(List.of("a", "b"), first.containers().keySet().stream().toList());
        assertEquals(List.of("a", "z"), first.coverageWarnings());
        assertEquals(6L, first.totalItemCount());
        assertEquals(first.digest(), second.digest());
        assertThrows(UnsupportedOperationException.class,
                () -> first.assetTotals().put("minecraft:wheat@0", 1L));
    }

    @Test
    void diffReportsAssetDomainAndContainerChangesInAfterMinusBeforeDirection() {
        VerifierSnapshot before = snapshot("before", 100L,
                Map.of("merchant", state("npc_personal", 3L,
                                Map.of("minecraft:bread@0", 8L)),
                        "granary", state("warehouse", 7L,
                                Map.of("minecraft:bread@0", 4L))),
                Map.of("minecraft:bread@0", 12L, "minecraft:arrow@0", 5L),
                Map.of("transfer_audits", 2L, "beliefs", 4L), List.of());
        VerifierSnapshot after = snapshot("after", 160L,
                Map.of("merchant", state("npc_personal", 4L,
                                Map.of("minecraft:bread@0", 4L)),
                        "granary", state("warehouse", 8L,
                                Map.of("minecraft:bread@0", 8L))),
                Map.of("minecraft:bread@0", 12L, "minecraft:arrow@0", 3L),
                Map.of("transfer_audits", 3L, "beliefs", 6L), List.of());

        VerifierDiff diff = VerifierDiff.between(before, after);

        assertEquals(60L, diff.elapsedTicks());
        assertEquals(Map.of("minecraft:arrow@0", -2L), diff.assetDeltas());
        assertEquals(1L, diff.domainDeltas().get("transfer_audits"));
        assertEquals(2L, diff.domainDeltas().get("beliefs"));
        assertEquals(2, diff.containerChanges().size());
        assertEquals(-4L, change(diff, "merchant").assetDeltas().get("minecraft:bread@0"));
        assertEquals(4L, change(diff, "granary").assetDeltas().get("minecraft:bread@0"));
        assertFalse(diff.physicallyUnchanged());
    }

    @Test
    void movedGoodsKeepGlobalConservationEvenWhenContainersChange() {
        VerifierSnapshot before = snapshot("before", 10L,
                Map.of("cargo", state("npc_cargo", 1L, Map.of("minecraft:bread@0", 6L)),
                        "warehouse", state("warehouse", 1L, Map.of())),
                Map.of("minecraft:bread@0", 6L), Map.of(), List.of());
        VerifierSnapshot after = snapshot("after", 20L,
                Map.of("cargo", state("npc_cargo", 2L, Map.of()),
                        "warehouse", state("warehouse", 2L, Map.of("minecraft:bread@0", 6L))),
                Map.of("minecraft:bread@0", 6L), Map.of(), List.of());

        VerifierDiff diff = VerifierDiff.between(before, after);

        assertTrue(diff.physicallyUnchanged());
        assertEquals(2, diff.containerChanges().size());
    }

    @Test
    void removedContainerProducesNegativeAssetsAndCoverageWarning() {
        VerifierSnapshot before = snapshot("before", 10L,
                Map.of("drop", state("world_drop", -1L, Map.of("minecraft:wheat@0", 3L))),
                Map.of("minecraft:wheat@0", 3L), Map.of(), List.of());
        VerifierSnapshot after = snapshot("after", 11L, Map.of(), Map.of(), Map.of(), List.of());

        VerifierDiff diff = VerifierDiff.between(before, after);

        assertEquals(-3L, diff.assetDeltas().get("minecraft:wheat@0"));
        assertEquals(VerifierDiff.ChangeKind.REMOVED, diff.containerChanges().getFirst().kind());
        assertTrue(diff.warnings().contains("captured_container_disappeared"));
    }

    @Test
    void unsafeSnapshotNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> snapshot("../outside", 1L,
                Map.of(), Map.of(), Map.of(), List.of()));
    }

    @Test
    void snapshotRoundTripsThroughPersistentNbt() {
        VerifierSnapshot original = snapshot("before", 420L,
                Map.of("merchant", state("npc_personal", 9L,
                        Map.of("minecraft:bread@0", 12L, "minecraft:paper@0", 3L))),
                Map.of("minecraft:bread@0", 12L, "minecraft:paper@0", 3L),
                Map.of("characters", 3L, "trade_sessions", 1L),
                List.of("unloaded_warehouse:test"));

        VerifierSnapshot restored = VerifierSnapshot.load(original.save());

        assertEquals(original, restored);
        assertEquals(original.digest(), restored.digest());
    }

    private static VerifierDiff.ContainerChange change(VerifierDiff diff, String id) {
        return diff.containerChanges().stream().filter(value -> value.containerId().equals(id))
                .findFirst().orElseThrow();
    }

    private static VerifierSnapshot.ContainerState state(String kind, long revision,
                                                          Map<String, Long> assets) {
        return new VerifierSnapshot.ContainerState(kind, kind + ":locator", revision,
                Math.max(1, assets.size()), assets.isEmpty() ? 0 : assets.size(), assets);
    }

    private static VerifierSnapshot snapshot(String name, long time,
                                             Map<String, VerifierSnapshot.ContainerState> containers,
                                             Map<String, Long> assets, Map<String, Long> domains,
                                             List<String> warnings) {
        return VerifierSnapshot.create(name, "test", time, 2, "minecraft:overworld",
                "grain_road_lab", "shortage", 1, containers, assets, domains, warnings);
    }
}
