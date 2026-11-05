package com.jgeted.sagadyssey.social.credit;

import java.util.List;

/** Explainable pair-and-purpose underwriting; there is deliberately no global credit score. */
public final class CreditPolicy {
    private CreditPolicy() { }

    public static CreditDecision evaluate(CreditProfile profile, int requestedEmeralds,
                                          int relationshipStrength,
                                          List<CollateralPledge> collateral,
                                          List<CreditGuarantee> guarantees) {
        int relationship = Math.max(-100, Math.min(100, relationshipStrength));
        int security = (collateral == null ? 0 : collateral.stream()
                .mapToInt(CollateralPledge::quantity).sum() / 2)
                + (guarantees == null ? 0 : guarantees.stream()
                .mapToInt(CreditGuarantee::maximumEmeralds).sum());
        int limit = Math.max(0, 3 + profile.reliabilityPercent() / 10
                + relationship / 20 + security - profile.currentOutstanding());
        if (profile.defaultedAgreements() > 0 && security < requestedEmeralds) {
            return new CreditDecision(false, limit,
                    "这位债权人与债务人在同类债务上有未覆盖的违约记录。");
        }
        if (requestedEmeralds > limit) {
            return new CreditDecision(false, limit,
                    "按双方在这类债务上的履约、现有欠款和担保，本次额度不足。");
        }
        return new CreditDecision(true, limit,
                "额度来自双方在这类债务上的履约记录、关系与可执行担保。");
    }
}
