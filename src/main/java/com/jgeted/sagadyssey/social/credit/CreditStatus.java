package com.jgeted.sagadyssey.social.credit;

public enum CreditStatus {
    ACTIVE,
    PARTIALLY_PAID,
    SETTLED,
    SETTLED_LATE,
    OVERDUE,
    DEFAULTED,
    CANCELLED;

    public boolean payable() {
        return this == ACTIVE || this == PARTIALLY_PAID || this == OVERDUE || this == DEFAULTED;
    }

    public boolean terminal() { return this == SETTLED || this == SETTLED_LATE || this == CANCELLED; }
}
