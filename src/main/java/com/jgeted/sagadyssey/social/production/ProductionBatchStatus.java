package com.jgeted.sagadyssey.social.production;

public enum ProductionBatchStatus {
    RESERVED,
    COMPLETED,
    CANCELLED,
    FAILED;

    public boolean terminal() { return this != RESERVED; }
}
