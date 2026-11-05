package com.jgeted.sagadyssey.social.life;

/** NPC 当前唯一生效的工作权威；出生地和住宅不属于工作授权。 */
public enum NpcWorkAuthority {
    NONE,
    INSTITUTION,
    SERVICE,
    /** @deprecated old saves are migrated to SERVICE while loading. */
    @Deprecated
    PLAYER,
    CONTRACT;

    public static NpcWorkAuthority parse(String value) {
        if (value == null || value.isBlank()) return NONE;
        if ("PLAYER".equals(value)) return SERVICE;
        try { return valueOf(value); }
        catch (IllegalArgumentException ignored) { return NONE; }
    }
}
