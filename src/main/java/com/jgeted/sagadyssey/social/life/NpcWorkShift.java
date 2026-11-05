package com.jgeted.sagadyssey.social.life;

/** 固定排班只给生活决策提供基线，战斗和紧急命令仍可打断它。 */
public enum NpcWorkShift {
    DAY,
    NIGHT,
    FLEXIBLE;

    public static NpcWorkShift parse(String value) {
        if (value == null || value.isBlank()) return DAY;
        try { return valueOf(value); }
        catch (IllegalArgumentException ignored) { return DAY; }
    }
}
