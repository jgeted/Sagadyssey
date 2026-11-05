package com.jgeted.sagadyssey.npc.trade;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;

public record TradeCatalogEntry(ItemFingerprint fingerprint, int owned, int available,
                                int reservedHere, String knownName, String reason) {
    public TradeCatalogEntry {
        if (fingerprint == null || owned <= 0 || available <= 0 || available > owned
                || reservedHere < 0 || reservedHere > available) {
            throw new IllegalArgumentException("invalid catalog quantities");
        }
        knownName = knownName == null ? "" : knownName;
        reason = reason == null ? "" : reason;
    }
}
