package com.jgeted.sagadyssey.social.governance;

public enum LevyStatus {
    ASSESSED,
    PARTIALLY_PAID,
    OVERDUE,
    SATISFIED,
    SATISFIED_LATE,
    CANCELLED;

    public boolean payable() {
        return this == ASSESSED || this == PARTIALLY_PAID || this == OVERDUE;
    }

    public boolean terminal() { return this == SATISFIED || this == SATISFIED_LATE || this == CANCELLED; }
}
