package com.jgeted.sagadyssey.npc.appearance;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** 衣服款式，不提供武器、护甲属性或战斗能力。 */
public enum NpcMilitaryOutfit {
    FOOT_SWORD, FOOT_SPEAR, FOOT_BOW, FOOT_CROSSBOW,
    LIGHT_CAVALRY_SWORD, LIGHT_CAVALRY_SPEAR, LIGHT_CAVALRY_BOW, LIGHT_CAVALRY_CROSSBOW,
    HEAVY_CAVALRY_SWORD, HEAVY_CAVALRY_SPEAR, HEAVY_CAVALRY_BOW, HEAVY_CAVALRY_CROSSBOW;

    private static final java.util.Map<String,NpcMilitaryOutfit> BY_ID =
            Arrays.stream(values()).collect(java.util.stream.Collectors.toUnmodifiableMap(NpcMilitaryOutfit::id,value->value));

    public String id() { return name().toLowerCase(Locale.ROOT); }
    public boolean mounted() { return !name().startsWith("FOOT_"); }
    public boolean heavyCavalry() { return name().startsWith("HEAVY_CAVALRY_"); }
    public boolean bow() { return name().endsWith("_BOW"); }
    public static Optional<NpcMilitaryOutfit> find(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return Optional.ofNullable(BY_ID.get(id));
    }
}
