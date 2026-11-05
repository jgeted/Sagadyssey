package com.jgeted.sagadyssey.social.world;

/** 会对真实政治、口粮或 NPC 状态产生影响的世界事件。 */
public enum DynamicWorldEventType {
    BORDER_RAID("边境袭扰"),
    TRUCE("临时停战"),
    FAMINE("饥荒"),
    PLAGUE("疫病"),
    MARKET_FAIR("集市年"),
    SUCCESSION_CRISIS("继承危机");

    private final String displayName;
    DynamicWorldEventType(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }

    public static DynamicWorldEventType byName(String name) {
        try { return valueOf(name.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException | NullPointerException ignored) { return MARKET_FAIR; }
    }
}
