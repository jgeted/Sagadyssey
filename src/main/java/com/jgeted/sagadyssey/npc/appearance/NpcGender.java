package com.jgeted.sagadyssey.npc.appearance;

import net.minecraft.network.chat.Component;

/** NPC 的人物外观性别；只影响外观和称呼，不改变属性、职业或装备能力。 */
public enum NpcGender {
    MALE("male"),
    FEMALE("female");

    private final String id;

    NpcGender(String id) {
        this.id = id;
    }

    public String id() { return id; }
    public Component displayName() { return Component.translatable("gender.sagadyssey." + id); }

    public static NpcGender byName(String name) {
        if (name == null || name.isBlank()) return MALE;
        try {
            return valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MALE;
        }
    }
}
