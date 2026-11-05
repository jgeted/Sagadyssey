package com.jgeted.sagadyssey.npc.handover;

import javax.annotation.Nullable;
import java.util.UUID;

public record HandoverDecision(Status status, @Nullable HandoverIntent intent,
                               int quantity, String reason, @Nullable UUID transferId) {
    public HandoverDecision {
        quantity = Math.max(0, quantity);
        reason = reason == null ? "" : reason;
    }

    public boolean accepted() { return status == Status.ACCEPTED || status == Status.SHOWN; }
    public enum Status { ACCEPTED, REFUSED, NEEDS_CLARIFICATION, SHOWN }
}
