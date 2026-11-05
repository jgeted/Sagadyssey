package com.jgeted.sagadyssey.social.world;

import net.minecraft.network.chat.Component;

/** 新世界的初始社会局势。数值只描述压力，不直接凭空增减任何物资。 */
public enum WorldScenarioType {
    PEACEFUL_REIGN("peaceful_reign", 65, 15, 20, true),
    BORDER_WAR("border_war", 5, 80, 45, true),
    INTERREGNUM("interregnum", -45, 70, 35, false),
    FAILED_HARVEST("failed_harvest", -5, 35, 85, true),
    FREE_MARCHES("free_marches", 20, 45, 30, false);

    private final String id;
    private final int stability;
    private final int conflict;
    private final int scarcity;
    private final boolean sovereignAlive;

    WorldScenarioType(String id, int stability, int conflict, int scarcity, boolean sovereignAlive) {
        this.id = id;
        this.stability = stability;
        this.conflict = conflict;
        this.scarcity = scarcity;
        this.sovereignAlive = sovereignAlive;
    }

    public String id() { return id; }
    public int stability() { return stability; }
    public int conflict() { return conflict; }
    public int scarcity() { return scarcity; }
    public boolean sovereignAlive() { return sovereignAlive; }
    public Component displayName() { return Component.translatable("scenario.sagadyssey." + id); }
    public Component description() { return Component.translatable("scenario.sagadyssey." + id + ".description"); }

    public static WorldScenarioType byName(String name) {
        if (name == null || name.isBlank()) return PEACEFUL_REIGN;
        for (WorldScenarioType value : values()) {
            if (value.name().equalsIgnoreCase(name) || value.id.equalsIgnoreCase(name)) return value;
        }
        return PEACEFUL_REIGN;
    }

    public static WorldScenarioType fromSeed(long seed) {
        long mixed = seed ^ (seed >>> 33) ^ 0x5341474144595353L;
        return values()[Math.floorMod(Long.hashCode(mixed), values().length)];
    }
}
