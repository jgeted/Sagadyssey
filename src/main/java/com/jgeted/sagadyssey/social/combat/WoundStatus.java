package com.jgeted.sagadyssey.social.combat;

public enum WoundStatus {
    ACTIVE,
    STABILIZED,
    HEALING,
    SCARRED,
    HEALED,
    FATAL;

    public boolean active() {
        return this == ACTIVE || this == STABILIZED || this == HEALING;
    }
}
