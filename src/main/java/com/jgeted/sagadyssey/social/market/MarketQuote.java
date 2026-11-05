package com.jgeted.sagadyssey.social.market;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;

import java.util.UUID;

/** Fixed-point emerald quote derived only from completed, public local transactions. */
public record MarketQuote(UUID settlementId, ItemFingerprint fingerprint,
                          int unitEmeraldMilli, int lowEmeraldMilli, int highEmeraldMilli,
                          int tradedQuantity, int sampleCount, long lastObservedAt,
                          boolean stale) {
    public MarketQuote {
        if (settlementId == null || fingerprint == null || unitEmeraldMilli <= 0
                || lowEmeraldMilli <= 0 || highEmeraldMilli < lowEmeraldMilli
                || tradedQuantity <= 0 || sampleCount <= 0) {
            throw new IllegalArgumentException("invalid market quote");
        }
    }
}
