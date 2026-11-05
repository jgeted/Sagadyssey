package com.jgeted.sagadyssey.worldsim;

/** Central schema versions for the world-simulation v2 migration. */
public final class WorldSimulationSchema {
    public static final int LEGACY_VERSION = 1;
    public static final int CURRENT_VERSION = 2;

    private WorldSimulationSchema() { }

    public static boolean canRead(int version) {
        return version >= LEGACY_VERSION && version <= CURRENT_VERSION;
    }

    public static boolean needsMigration(int version) {
        return version < CURRENT_VERSION;
    }
}
