package com.jgeted.sagadyssey.worldsim.verifier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable, deterministic read model for operator verification. */
public record VerifierSnapshot(
        String name,
        String worldName,
        long capturedAt,
        int schemaVersion,
        String dimension,
        String scenarioId,
        String scenarioProfile,
        int scenarioRevision,
        Map<String, ContainerState> containers,
        Map<String, Long> assetTotals,
        Map<String, Long> domainCounts,
        List<String> coverageWarnings,
        String digest
) {
    public VerifierSnapshot {
        name = clean(name);
        worldName = worldName == null ? "" : worldName;
        dimension = dimension == null ? "" : dimension;
        scenarioId = scenarioId == null ? "" : scenarioId;
        scenarioProfile = scenarioProfile == null ? "" : scenarioProfile;
        containers = immutableSorted(containers);
        assetTotals = immutableSorted(assetTotals);
        domainCounts = immutableSorted(domainCounts);
        coverageWarnings = coverageWarnings == null ? List.of()
                : coverageWarnings.stream().sorted().toList();
        digest = digest == null ? "" : digest;
    }

    public static VerifierSnapshot create(String name, String worldName, long capturedAt,
                                           int schemaVersion, String dimension,
                                           String scenarioId, String scenarioProfile,
                                           int scenarioRevision,
                                           Map<String, ContainerState> containers,
                                           Map<String, Long> assetTotals,
                                           Map<String, Long> domainCounts,
                                           List<String> coverageWarnings) {
        VerifierSnapshot withoutDigest = new VerifierSnapshot(name, worldName, capturedAt,
                schemaVersion, dimension, scenarioId, scenarioProfile, scenarioRevision,
                containers, assetTotals, domainCounts, coverageWarnings, "");
        return new VerifierSnapshot(withoutDigest.name(), withoutDigest.worldName(),
                withoutDigest.capturedAt(), withoutDigest.schemaVersion(), withoutDigest.dimension(),
                withoutDigest.scenarioId(), withoutDigest.scenarioProfile(),
                withoutDigest.scenarioRevision(), withoutDigest.containers(),
                withoutDigest.assetTotals(), withoutDigest.domainCounts(),
                withoutDigest.coverageWarnings(), calculateDigest(withoutDigest));
    }

    public String shortDigest() {
        return digest.length() <= 12 ? digest : digest.substring(0, 12);
    }

    public long totalItemCount() {
        return assetTotals.values().stream().mapToLong(Long::longValue).sum();
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        tag.putString("World", worldName);
        tag.putLong("CapturedAt", capturedAt);
        tag.putInt("SchemaVersion", schemaVersion);
        tag.putString("Dimension", dimension);
        tag.putString("ScenarioId", scenarioId);
        tag.putString("ScenarioProfile", scenarioProfile);
        tag.putInt("ScenarioRevision", scenarioRevision);
        tag.putString("Digest", digest);

        ListTag containerList = new ListTag();
        containers.forEach((id, state) -> {
            CompoundTag container = new CompoundTag();
            container.putString("Id", id);
            container.putString("Kind", state.kind());
            container.putString("Locator", state.locator());
            container.putLong("Revision", state.revision());
            container.putInt("Size", state.size());
            container.putInt("NonEmptySlots", state.nonEmptySlots());
            container.put("Assets", saveLongMap(state.assets()));
            containerList.add(container);
        });
        tag.put("Containers", containerList);
        tag.put("AssetTotals", saveLongMap(assetTotals));
        tag.put("DomainCounts", saveLongMap(domainCounts));

        ListTag warningList = new ListTag();
        coverageWarnings.forEach(value -> warningList.add(StringTag.valueOf(value)));
        tag.put("CoverageWarnings", warningList);
        return tag;
    }

    static VerifierSnapshot load(CompoundTag tag) {
        Map<String, ContainerState> containers = new TreeMap<>();
        ListTag containerList = tag.getList("Containers", Tag.TAG_COMPOUND);
        for (int index = 0; index < containerList.size(); index++) {
            CompoundTag container = containerList.getCompound(index);
            String id = container.getString("Id");
            if (id.isBlank()) continue;
            containers.put(id, new ContainerState(container.getString("Kind"),
                    container.getString("Locator"), container.getLong("Revision"),
                    container.getInt("Size"), container.getInt("NonEmptySlots"),
                    loadLongMap(container.getCompound("Assets"))));
        }

        List<String> warnings = new java.util.ArrayList<>();
        ListTag warningList = tag.getList("CoverageWarnings", Tag.TAG_STRING);
        for (int index = 0; index < warningList.size(); index++) {
            warnings.add(warningList.getString(index));
        }
        return create(tag.getString("Name"), tag.getString("World"), tag.getLong("CapturedAt"),
                tag.getInt("SchemaVersion"), tag.getString("Dimension"),
                tag.getString("ScenarioId"), tag.getString("ScenarioProfile"),
                tag.getInt("ScenarioRevision"), containers,
                loadLongMap(tag.getCompound("AssetTotals")),
                loadLongMap(tag.getCompound("DomainCounts")), warnings);
    }

    private static CompoundTag saveLongMap(Map<String, Long> values) {
        CompoundTag tag = new CompoundTag();
        values.forEach(tag::putLong);
        return tag;
    }

    private static Map<String, Long> loadLongMap(CompoundTag tag) {
        Map<String, Long> values = new TreeMap<>();
        tag.getAllKeys().forEach(key -> values.put(key, tag.getLong(key)));
        return values;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("blank snapshot name");
        if (!value.matches("[a-zA-Z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("snapshot name must be 1-32 letters, digits, _ or -");
        }
        return value;
    }

    private static <T> Map<String, T> immutableSorted(Map<String, T> source) {
        TreeMap<String, T> sorted = new TreeMap<>();
        if (source != null) sorted.putAll(source);
        return Collections.unmodifiableMap(sorted);
    }

    private static String calculateDigest(VerifierSnapshot value) {
        StringBuilder canonical = new StringBuilder()
                .append(value.worldName()).append('|').append(value.capturedAt()).append('|')
                .append(value.schemaVersion()).append('|').append(value.dimension()).append('|')
                .append(value.scenarioId()).append('|').append(value.scenarioProfile()).append('|')
                .append(value.scenarioRevision()).append('\n');
        value.containers().forEach((id, container) -> {
            canonical.append("container=").append(id).append('|').append(container.kind())
                    .append('|').append(container.locator()).append('|').append(container.revision())
                    .append('|').append(container.size()).append('|').append(container.nonEmptySlots())
                    .append('\n');
            container.assets().forEach((asset, count) -> canonical.append(" item=")
                    .append(asset).append('|').append(count).append('\n'));
        });
        value.assetTotals().forEach((asset, count) -> canonical.append("asset=")
                .append(asset).append('|').append(count).append('\n'));
        value.domainCounts().forEach((domain, count) -> canonical.append("domain=")
                .append(domain).append('|').append(count).append('\n'));
        value.coverageWarnings().forEach(warning -> canonical.append("warning=")
                .append(warning).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record ContainerState(String kind, String locator, long revision, int size,
                                 int nonEmptySlots, Map<String, Long> assets) {
        public ContainerState {
            kind = kind == null ? "unknown" : kind;
            locator = locator == null ? "" : locator;
            size = Math.max(0, size);
            nonEmptySlots = Math.max(0, Math.min(size, nonEmptySlots));
            assets = immutableSorted(assets);
        }

        public long totalItems() {
            return assets.values().stream().mapToLong(Long::longValue).sum();
        }
    }
}
