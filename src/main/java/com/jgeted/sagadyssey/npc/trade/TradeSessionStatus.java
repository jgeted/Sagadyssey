package com.jgeted.sagadyssey.npc.trade;

public enum TradeSessionStatus {
    OPEN,
    PROPOSED,
    COUNTERED,
    ACCEPTED,
    COMMITTED,
    EXPIRED,
    CANCELLED,
    FAILED;

    public boolean terminal() {
        return this == COMMITTED || this == EXPIRED || this == CANCELLED || this == FAILED;
    }
}
