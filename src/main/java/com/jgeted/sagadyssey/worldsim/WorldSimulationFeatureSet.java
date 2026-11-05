package com.jgeted.sagadyssey.worldsim;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Dependency-aware mutable feature set used by persisted per-world settings. */
public final class WorldSimulationFeatureSet {
    private final EnumSet<WorldSimulationFeature> enabled;

    public WorldSimulationFeatureSet() {
        this(EnumSet.noneOf(WorldSimulationFeature.class));
    }

    public WorldSimulationFeatureSet(Set<WorldSimulationFeature> enabled) {
        this.enabled = enabled.isEmpty()
                ? EnumSet.noneOf(WorldSimulationFeature.class)
                : EnumSet.copyOf(enabled);
    }

    public boolean isEnabled(WorldSimulationFeature feature) {
        return enabled.contains(feature);
    }

    public Set<WorldSimulationFeature> snapshot() {
        return Collections.unmodifiableSet(EnumSet.copyOf(enabled));
    }

    public FeatureChange enable(WorldSimulationFeature feature) {
        EnumSet<WorldSimulationFeature> missing = EnumSet.noneOf(WorldSimulationFeature.class);
        for (WorldSimulationFeature prerequisite : feature.prerequisites()) {
            if (!enabled.contains(prerequisite)) missing.add(prerequisite);
        }
        if (!missing.isEmpty()) return new FeatureChange(false, Set.of(), Set.copyOf(missing));
        if (!enabled.add(feature)) return new FeatureChange(false, Set.of(), Set.of());
        return new FeatureChange(true, Set.of(feature), Set.of());
    }

    public FeatureChange disable(WorldSimulationFeature feature) {
        if (!enabled.contains(feature)) return new FeatureChange(false, Set.of(), Set.of());
        EnumSet<WorldSimulationFeature> removed = EnumSet.of(feature);
        boolean changed;
        do {
            changed = false;
            for (WorldSimulationFeature candidate : WorldSimulationFeature.values()) {
                if (enabled.contains(candidate) && !removed.contains(candidate)
                        && candidate.prerequisites().stream().anyMatch(removed::contains)) {
                    removed.add(candidate);
                    changed = true;
                }
            }
        } while (changed);
        enabled.removeAll(removed);
        return new FeatureChange(true, Set.copyOf(removed), Set.of());
    }

    public record FeatureChange(boolean applied, Set<WorldSimulationFeature> affected,
                                Set<WorldSimulationFeature> missingPrerequisites) { }
}
