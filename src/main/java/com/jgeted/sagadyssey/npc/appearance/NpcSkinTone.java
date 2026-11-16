package com.jgeted.sagadyssey.npc.appearance;

/** ORIGINAL is the unchanged resource path, retained when an old save has no skin field. */
public enum NpcSkinTone {
    ORIGINAL("original"), LIGHT("light"), ROSY("rosy"), OLIVE("olive"), BROWN("brown"), DEEP("deep");
    private final String id;
    NpcSkinTone(String id) { this.id=id; }
    public String id() { return id; }
    public static NpcSkinTone byName(String name) {
        try { return valueOf(name.toUpperCase(java.util.Locale.ROOT)); }
        catch (RuntimeException ignored) { return ORIGINAL; }
    }
}
