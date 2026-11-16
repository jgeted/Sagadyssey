package com.jgeted.sagadyssey.npc.appearance;

/** Modest block-style cranium/jaw silhouettes; CLASSIC preserves existing NPCs. */
public enum NpcHeadShape {
    CLASSIC("classic", 1, 1, 1),
    OVAL("oval", .98F, 1.02F, .90F),
    SQUARE("square", 1.035F, .98F, 1),
    LONG("long", .96F, 1.065F, .91F),
    ROUND("round", 1.045F, .96F, .94F),
    ANGULAR("angular", 1, 1.025F, .86F);

    private final String id;
    private final float width, height, jawWidth;
    NpcHeadShape(String id, float width, float height, float jawWidth) {
        this.id=id; this.width=width; this.height=height; this.jawWidth=jawWidth;
    }
    public String id() { return id; }
    public float width() { return width; }
    public float height() { return height; }
    public float jawWidth() { return jawWidth; }
    public static NpcHeadShape byName(String name) {
        try { return valueOf(name.toUpperCase(java.util.Locale.ROOT)); }
        catch (RuntimeException ignored) { return CLASSIC; }
    }
}
