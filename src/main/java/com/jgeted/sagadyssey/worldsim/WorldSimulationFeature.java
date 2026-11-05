package com.jgeted.sagadyssey.worldsim;

import java.util.Locale;
import java.util.Set;

/**
 * Per-world rollout gates. All v2 features default to disabled so merely installing a
 * development build cannot silently change a legacy world's simulation semantics.
 */
public enum WorldSimulationFeature {
    CHARACTER_INVENTORY,
    RIGHTS_TRANSFER(CHARACTER_INVENTORY),
    SERVICE_RELATIONSHIPS(CHARACTER_INVENTORY),
    KNOWLEDGE_PROJECTION(CHARACTER_INVENTORY, SERVICE_RELATIONSHIPS),
    DIRECT_HANDOVER(RIGHTS_TRANSFER, KNOWLEDGE_PROJECTION),
    REAL_INVENTORY_TRADE(RIGHTS_TRANSFER, KNOWLEDGE_PROJECTION),
    HOUSEHOLD_ECONOMY(RIGHTS_TRANSFER, SERVICE_RELATIONSHIPS),
    LOCAL_MARKET(HOUSEHOLD_ECONOMY, REAL_INVENTORY_TRADE),
    INSTITUTIONS(RIGHTS_TRANSFER, SERVICE_RELATIONSHIPS),
    TACTICAL_COMMAND(RIGHTS_TRANSFER, SERVICE_RELATIONSHIPS, KNOWLEDGE_PROJECTION),
    LOD_SIMULATION(HOUSEHOLD_ECONOMY, INSTITUTIONS, TACTICAL_COMMAND);

    private final Set<WorldSimulationFeature> prerequisites;

    WorldSimulationFeature(WorldSimulationFeature... prerequisites) {
        this.prerequisites = Set.of(prerequisites);
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Set<WorldSimulationFeature> prerequisites() {
        return prerequisites;
    }

    public static WorldSimulationFeature byKey(String key) {
        if (key == null) return null;
        for (WorldSimulationFeature feature : values()) {
            if (feature.key().equals(key.trim().toLowerCase(Locale.ROOT))) return feature;
        }
        return null;
    }
}
