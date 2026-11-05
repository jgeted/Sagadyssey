package com.jgeted.sagadyssey.worldsim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Deterministic, read-only summary used before and after every world migration. */
public record MigrationBaselineReport(
        String worldName,
        int sourceSchemaVersion,
        int loadedNpcCount,
        int uniqueNpcCount,
        int registeredCharacterCount,
        List<ContainerSnapshot> containers,
        Map<String, Long> assetTotals,
        List<String> issues,
        String digest
) {
    public static MigrationBaselineReport inspect(String worldName, int sourceSchemaVersion,
                                                   List<UUID> loadedNpcIds,
                                                   int registeredCharacterCount,
                                                   List<ContainerSnapshot> containers,
                                                   Map<String, Long> assetTotals) {
        List<String> issues = new ArrayList<>();
        Set<UUID> uniqueNpcIds = new HashSet<>(loadedNpcIds);
        if (uniqueNpcIds.size() != loadedNpcIds.size()) issues.add("duplicate_npc_uuid");
        if (!WorldSimulationSchema.canRead(sourceSchemaVersion)) issues.add("unsupported_schema");
        if (registeredCharacterCount < uniqueNpcIds.size()) issues.add("unregistered_loaded_npc");

        Set<String> containerIds = new HashSet<>();
        for (ContainerSnapshot container : containers) {
            if (!containerIds.add(container.id())) issues.add("duplicate_container:" + container.id());
            if (container.revision() < 0) issues.add("negative_revision:" + container.id());
            if (container.size() < 0 || container.nonEmptySlots() < 0
                    || container.nonEmptySlots() > container.size()) {
                issues.add("invalid_slot_count:" + container.id());
            }
        }
        assetTotals.forEach((key, count) -> {
            if (key == null || key.isBlank()) issues.add("blank_asset_key");
            if (count == null || count < 0) issues.add("negative_asset_count:" + key);
        });

        List<ContainerSnapshot> sortedContainers = containers.stream()
                .sorted(Comparator.comparing(ContainerSnapshot::id)).toList();
        Map<String, Long> sortedAssets = Collections.unmodifiableMap(new TreeMap<>(assetTotals));
        List<String> sortedIssues = issues.stream().sorted().toList();
        String digest = digest(worldName, sourceSchemaVersion, loadedNpcIds,
                registeredCharacterCount, sortedContainers, sortedAssets, sortedIssues);
        return new MigrationBaselineReport(worldName, sourceSchemaVersion, loadedNpcIds.size(),
                uniqueNpcIds.size(), registeredCharacterCount, sortedContainers, sortedAssets,
                sortedIssues, digest);
    }

    public boolean valid() {
        return issues.isEmpty();
    }

    private static String digest(String worldName, int sourceSchemaVersion, List<UUID> npcIds,
                                 int registeredCharacters, List<ContainerSnapshot> containers,
                                 Map<String, Long> assets, List<String> issues) {
        StringBuilder canonical = new StringBuilder()
                .append(worldName).append('|').append(sourceSchemaVersion).append('|')
                .append(registeredCharacters).append('\n');
        npcIds.stream().map(UUID::toString).sorted().forEach(id -> canonical.append("npc=").append(id).append('\n'));
        containers.forEach(container -> canonical.append("container=").append(container.id())
                .append('|').append(container.revision()).append('|').append(container.size())
                .append('|').append(container.nonEmptySlots()).append('\n'));
        assets.forEach((key, count) -> canonical.append("asset=").append(key).append('|').append(count).append('\n'));
        issues.forEach(issue -> canonical.append("issue=").append(issue).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record ContainerSnapshot(String id, long revision, int size, int nonEmptySlots) { }
}
