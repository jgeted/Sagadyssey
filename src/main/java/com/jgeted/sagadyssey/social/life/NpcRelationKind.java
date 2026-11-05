package com.jgeted.sagadyssey.social.life;

public enum NpcRelationKind {
    FAMILY("亲属"), FRIEND("朋友"), RIVAL("对手"), DEPENDENT("受其照料"), MENTOR("师徒");

    private final String displayName;
    NpcRelationKind(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }

    public static NpcRelationKind parse(String value) {
        if (value == null || value.isBlank()) return FRIEND;
        try { return valueOf(value); }
        catch (IllegalArgumentException ignored) { return FRIEND; }
    }
}
