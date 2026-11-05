package com.jgeted.sagadyssey.social.politics;

public enum PoliticalRelation {
    ALLIED(true), FRIENDLY(true), NEUTRAL(true), TENSE(true), WAR(true),
    VASSAL(false), OVERLORD(false);

    private final boolean symmetric;

    PoliticalRelation(boolean symmetric) { this.symmetric = symmetric; }
    public boolean symmetric() { return symmetric; }

    public static PoliticalRelation byName(String name) {
        if (name == null || name.isBlank()) return NEUTRAL;
        try {
            return valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return NEUTRAL;
        }
    }
}
