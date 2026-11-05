package com.jgeted.sagadyssey.worldsim.verifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Pure before/after comparison. A net asset change is reported, not automatically called a bug. */
public record VerifierDiff(
        VerifierSnapshot before,
        VerifierSnapshot after,
        Map<String, Long> assetDeltas,
        Map<String, Long> domainDeltas,
        List<ContainerChange> containerChanges,
        List<String> warnings
) {
    public VerifierDiff {
        assetDeltas = immutableSorted(assetDeltas);
        domainDeltas = immutableSorted(domainDeltas);
        containerChanges = containerChanges == null ? List.of() : List.copyOf(containerChanges);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static VerifierDiff between(VerifierSnapshot before, VerifierSnapshot after) {
        if (before == null || after == null) throw new IllegalArgumentException("snapshots required");
        Map<String, Long> assetDeltas = delta(before.assetTotals(), after.assetTotals());
        Map<String, Long> domainDeltas = delta(before.domainCounts(), after.domainCounts());
        List<ContainerChange> containerChanges = new ArrayList<>();
        Set<String> containerIds = new TreeSet<>();
        containerIds.addAll(before.containers().keySet());
        containerIds.addAll(after.containers().keySet());
        for (String id : containerIds) {
            VerifierSnapshot.ContainerState oldState = before.containers().get(id);
            VerifierSnapshot.ContainerState newState = after.containers().get(id);
            if (oldState == null) {
                containerChanges.add(new ContainerChange(id, ChangeKind.ADDED, -1L,
                        newState.revision(), Map.copyOf(newState.assets())));
                continue;
            }
            if (newState == null) {
                Map<String, Long> removed = new TreeMap<>();
                oldState.assets().forEach((asset, count) -> removed.put(asset, -count));
                containerChanges.add(new ContainerChange(id, ChangeKind.REMOVED,
                        oldState.revision(), -1L, removed));
                continue;
            }
            Map<String, Long> contents = delta(oldState.assets(), newState.assets());
            if (!contents.isEmpty() || oldState.revision() != newState.revision()
                    || !oldState.locator().equals(newState.locator())) {
                containerChanges.add(new ContainerChange(id, ChangeKind.CHANGED,
                        oldState.revision(), newState.revision(), contents));
            }
        }

        List<String> warnings = new ArrayList<>();
        if (!before.worldName().equals(after.worldName())) warnings.add("world_changed");
        if (before.schemaVersion() != after.schemaVersion()) warnings.add("schema_changed");
        if (!before.coverageWarnings().isEmpty() || !after.coverageWarnings().isEmpty()) {
            warnings.add("incomplete_container_coverage");
        }
        if (containerChanges.stream().anyMatch(change -> change.kind() == ChangeKind.REMOVED)) {
            warnings.add("captured_container_disappeared");
        }
        return new VerifierDiff(before, after, assetDeltas, domainDeltas,
                containerChanges, warnings);
    }

    public boolean physicallyUnchanged() {
        return assetDeltas.isEmpty();
    }

    public long elapsedTicks() {
        return after.capturedAt() - before.capturedAt();
    }

    private static Map<String, Long> delta(Map<String, Long> before, Map<String, Long> after) {
        Set<String> keys = new TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        Map<String, Long> result = new TreeMap<>();
        for (String key : keys) {
            long change = after.getOrDefault(key, 0L) - before.getOrDefault(key, 0L);
            if (change != 0L) result.put(key, change);
        }
        return result;
    }

    private static <T> Map<String, T> immutableSorted(Map<String, T> source) {
        return Collections.unmodifiableMap(new TreeMap<>(source));
    }

    public enum ChangeKind { ADDED, REMOVED, CHANGED }

    public record ContainerChange(String containerId, ChangeKind kind, long beforeRevision,
                                  long afterRevision, Map<String, Long> assetDeltas) {
        public ContainerChange {
            assetDeltas = immutableSorted(assetDeltas);
        }
    }
}
