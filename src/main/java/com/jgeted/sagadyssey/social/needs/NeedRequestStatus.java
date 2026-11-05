package com.jgeted.sagadyssey.social.needs;

/** Demand lifecycle only: transitions do not move goods or release asset leases. */
public enum NeedRequestStatus {
    OPEN, MATCHED, IN_TRANSIT, DELIVERED,
    WAITING_EXTERNAL, UNREACHABLE, BLOCKED_CYCLE,
    CONSUMED, CLOSED, CANCELLED;

    public boolean terminal() {
        return this == CONSUMED || this == CLOSED || this == CANCELLED;
    }

    /** Quantity, custody, authorization and lease checks belong to the request service. */
    public boolean canTransitionTo(NeedRequestStatus next) {
        if (next == null || next == this || terminal()) return false;
        if (next == CANCELLED) return true;
        return switch (this) {
            case OPEN -> next == MATCHED || next == WAITING_EXTERNAL
                    || next == UNREACHABLE || next == BLOCKED_CYCLE;
            case MATCHED -> next == IN_TRANSIT || next == OPEN
                    || next == WAITING_EXTERNAL || next == UNREACHABLE || next == BLOCKED_CYCLE;
            // An unreachable route must not erase the carrier's custody of real goods.
            case IN_TRANSIT -> next == DELIVERED || next == UNREACHABLE;
            case DELIVERED -> next == CONSUMED || next == CLOSED;
            // Recovery requires explicit revalidation by the service, never an implicit retry.
            case WAITING_EXTERNAL, UNREACHABLE, BLOCKED_CYCLE -> next == OPEN;
            case CONSUMED, CLOSED, CANCELLED -> false;
        };
    }
}
