package com.jgeted.sagadyssey.social.justice;

public enum DisputeStatus {
    FILED,
    UNDER_REVIEW,
    DECIDED,
    DISMISSED;

    public boolean open() { return this == FILED || this == UNDER_REVIEW; }
}
