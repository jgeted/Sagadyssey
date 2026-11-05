package com.jgeted.sagadyssey.social.credit;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;

/** Historical evidence for one creditor, one debtor, and one purpose only. */
public record CreditProfile(SubjectRef creditor, SubjectRef debtor, CreditPurpose purpose,
                            int agreements, int principalEmeralds, int repaidEmeralds,
                            int currentOutstanding, int overdueAgreements,
                            int defaultedAgreements) {
    public int reliabilityPercent() {
        if (agreements == 0) return 50;
        int repayment = principalEmeralds == 0 ? 0 : repaidEmeralds * 100 / principalEmeralds;
        return Math.max(0, Math.min(100, repayment - overdueAgreements * 8
                - defaultedAgreements * 25 + 10));
    }
}
