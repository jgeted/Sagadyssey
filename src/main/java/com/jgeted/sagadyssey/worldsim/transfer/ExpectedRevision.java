package com.jgeted.sagadyssey.worldsim.transfer;

import java.util.UUID;

public record ExpectedRevision(UUID containerId, long revision) {
    public ExpectedRevision {
        if (containerId == null || revision < 0L) throw new IllegalArgumentException("invalid revision");
    }
}
