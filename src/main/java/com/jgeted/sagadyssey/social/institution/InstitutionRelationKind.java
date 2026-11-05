package com.jgeted.sagadyssey.social.institution;

public enum InstitutionRelationKind {
    ALLIANCE(true),
    TRADE_COMPACT(true),
    HOSTILE(true),
    VASSALAGE(false),
    OVERLORD(false),
    JURISDICTION_DISPUTE(true);

    private final boolean symmetric;
    InstitutionRelationKind(boolean symmetric) { this.symmetric = symmetric; }
    public boolean symmetric() { return symmetric; }
}
