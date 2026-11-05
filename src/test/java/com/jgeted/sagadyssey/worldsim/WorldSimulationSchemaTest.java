package com.jgeted.sagadyssey.worldsim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldSimulationSchemaTest {
    @Test
    void acceptsLegacyAndCurrentButRejectsUnknownVersions() {
        assertTrue(WorldSimulationSchema.canRead(WorldSimulationSchema.LEGACY_VERSION));
        assertTrue(WorldSimulationSchema.canRead(WorldSimulationSchema.CURRENT_VERSION));
        assertTrue(WorldSimulationSchema.needsMigration(WorldSimulationSchema.LEGACY_VERSION));
        assertFalse(WorldSimulationSchema.needsMigration(WorldSimulationSchema.CURRENT_VERSION));
        assertFalse(WorldSimulationSchema.canRead(0));
        assertFalse(WorldSimulationSchema.canRead(WorldSimulationSchema.CURRENT_VERSION + 1));
    }
}
