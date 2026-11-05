package com.jgeted.sagadyssey.worldsim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldSimulationFeatureSetTest {
    @Test
    void featuresAreDisabledByDefault() {
        WorldSimulationFeatureSet features = new WorldSimulationFeatureSet();
        assertTrue(features.snapshot().isEmpty());
    }

    @Test
    void cannotEnableFeatureBeforePrerequisites() {
        WorldSimulationFeatureSet features = new WorldSimulationFeatureSet();
        var change = features.enable(WorldSimulationFeature.RIGHTS_TRANSFER);
        assertFalse(change.applied());
        assertEquals(java.util.Set.of(WorldSimulationFeature.CHARACTER_INVENTORY),
                change.missingPrerequisites());
        assertFalse(features.isEnabled(WorldSimulationFeature.RIGHTS_TRANSFER));
    }

    @Test
    void disablingFoundationCascadesToDependants() {
        WorldSimulationFeatureSet features = new WorldSimulationFeatureSet();
        assertTrue(features.enable(WorldSimulationFeature.CHARACTER_INVENTORY).applied());
        assertTrue(features.enable(WorldSimulationFeature.RIGHTS_TRANSFER).applied());
        assertTrue(features.enable(WorldSimulationFeature.SERVICE_RELATIONSHIPS).applied());
        assertTrue(features.enable(WorldSimulationFeature.KNOWLEDGE_PROJECTION).applied());
        assertTrue(features.enable(WorldSimulationFeature.DIRECT_HANDOVER).applied());

        var change = features.disable(WorldSimulationFeature.CHARACTER_INVENTORY);
        assertTrue(change.applied());
        assertTrue(features.snapshot().isEmpty());
        assertTrue(change.affected().contains(WorldSimulationFeature.DIRECT_HANDOVER));
    }
}
