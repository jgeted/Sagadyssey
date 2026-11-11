package com.jgeted.sagadyssey.social.needs;

/** Pure decisions for the existing demand/dependency ledger. No stock, identities or effects are created. */
final class LocalSupplierRules {
    private LocalSupplierRules() { }

    static boolean supplier(NeedRequest need) {
        return need.key().purpose().startsWith("CHARACTER:local-supplier:");
    }

    static boolean eligibleParent(NeedRequest need, long now) {
        return !supplier(need) && !need.status().terminal() && need.status() != NeedRequestStatus.DELIVERED
                && need.status() != NeedRequestStatus.BLOCKED_CYCLE && need.remaining() > 0
                && need.key().deadline() > now && need.reserved() == 0 && need.inTransit() == 0;
    }

    static boolean expireUncommitted(NeedRequest need, boolean productionBound, boolean unresolvedRoute, long now) {
        return supplier(need) && !need.status().terminal() && need.status() != NeedRequestStatus.DELIVERED
                && need.key().deadline() <= now && !productionBound && !unresolvedRoute
                && need.reserved() == 0 && need.inTransit() == 0;
    }

    static boolean blocksNextBatch(NeedRequest child, boolean unresolvedRoute, int originalStock) {
        if (!supplier(child)) return false;
        if (unresolvedRoute || child.reserved() > 0 || child.inTransit() > 0) return true;
        if (child.status() == NeedRequestStatus.DELIVERED) return originalStock > 0;
        return !child.status().terminal();
    }

    static int uncovered(NeedRequest parent, int lawfulFreeStock) {
        if (lawfulFreeStock < 0) throw new IllegalArgumentException("negative stock");
        // remaining() already removes delivered, held and carried amounts; never subtract them twice.
        return Math.max(0, parent.remaining() - lawfulFreeStock);
    }
}
