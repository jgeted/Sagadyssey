package com.jgeted.sagadyssey.social.production;

/** 持久生产订单的严格生命周期。 */
public enum ProductionOrderStatus {
    OPEN,
    CLAIMED,
    RESERVED,
    IN_PROGRESS,
    SETTLING,
    BLOCKED,
    COMPLETED,
    CANCELLED,
    FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }

    public boolean canTransitionTo(ProductionOrderStatus next) {
        if (next == null || next == this || terminal()) return false;
        if (next == CANCELLED || next == FAILED) return true;
        return switch (this) {
            case OPEN -> next == CLAIMED || next == BLOCKED;
            case CLAIMED -> next == RESERVED || next == BLOCKED;
            case RESERVED -> next == IN_PROGRESS || next == BLOCKED;
            case IN_PROGRESS -> next == SETTLING || next == BLOCKED;
            case SETTLING -> next == COMPLETED || next == BLOCKED;
            case BLOCKED -> next == OPEN || next == CLAIMED || next == RESERVED
                    || next == IN_PROGRESS || next == SETTLING;
            case COMPLETED, CANCELLED, FAILED -> false;
        };
    }

    public void requireTransitionTo(ProductionOrderStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException("illegal production order transition: " + this + " -> " + next);
        }
    }
}
