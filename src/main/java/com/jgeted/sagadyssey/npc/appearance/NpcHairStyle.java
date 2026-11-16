package com.jgeted.sagadyssey.npc.appearance;

public enum NpcHairStyle {
    CLASSIC("classic"), NEAT("neat"), PARTED("parted"), TIED("tied");
    private final String id;
    NpcHairStyle(String id) { this.id=id; }
    public String id() { return id; }
    public static NpcHairStyle byName(String name) {
        try { return valueOf(name.toUpperCase(java.util.Locale.ROOT)); }
        catch (RuntimeException ignored) { return CLASSIC; }
    }
}
