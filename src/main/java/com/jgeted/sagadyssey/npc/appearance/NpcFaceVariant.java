package com.jgeted.sagadyssey.npc.appearance;

import net.minecraft.network.chat.Component;

/** 六组可跨男女使用的脸型、年龄感和发型组合。 */
public enum NpcFaceVariant {
    YOUNG("young"),
    BROAD("broad"),
    LONG("long"),
    FAIR("fair"),
    WEATHERED("weathered"),
    ELDER("elder");

    private final String id;

    NpcFaceVariant(String id) {
        this.id = id;
    }

    public String id() { return id; }
    public Component displayName() { return Component.translatable("face.sagadyssey." + id); }

    public static NpcFaceVariant byName(String name) {
        if (name == null || name.isBlank()) return YOUNG;
        try {
            return valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return YOUNG;
        }
    }
}
