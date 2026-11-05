package com.jgeted.sagadyssey.social.justice;

import com.jgeted.sagadyssey.social.governance.GovernanceLedgerData;
import com.jgeted.sagadyssey.social.governance.LevyAssessment;
import com.jgeted.sagadyssey.social.governance.LevyService;
import com.jgeted.sagadyssey.social.governance.LevyType;
import com.jgeted.sagadyssey.social.institution.InstitutionData;
import com.jgeted.sagadyssey.social.institution.InstitutionMembership;
import com.jgeted.sagadyssey.social.institution.InstitutionRecord;
import com.jgeted.sagadyssey.social.institution.OfficeAuthority;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;

import java.util.List;
import java.util.UUID;

public final class AdjudicationService {
    private AdjudicationService() { }

    public static DisputeRecord file(JusticeData justice, InstitutionData institutions,
                                     UUID disputeId, UUID forumId, UUID settlementId,
                                     SubjectRef claimant, SubjectRef respondent,
                                     DisputeType type, String claim, long now) {
        InstitutionRecord forum = institutions.institution(forumId);
        if (forum == null || forum.settlementId() == null
                || !forum.settlementId().equals(settlementId)) {
            throw new IllegalStateException("受理机构不在该管辖地");
        }
        DisputeRecord dispute = new DisputeRecord(disputeId, forumId, settlementId,
                claimant, respondent, type, claim, List.of(), now, DisputeStatus.FILED,
                null, 0L, "", JudgmentSanction.NONE, null, 0);
        if (!justice.file(dispute)) throw new IllegalStateException("案件已经存在");
        return dispute;
    }

    public static DisputeRecord submitEvidence(JusticeData justice, UUID disputeId,
                                               EvidenceRef evidence) {
        DisputeRecord dispute = requiredOpen(justice, disputeId);
        DisputeRecord updated = dispute.withEvidence(evidence);
        justice.update(updated);
        return updated;
    }

    public static ProtectionOrder decideProtection(JusticeData justice,
                                                   InstitutionData institutions,
                                                   UUID disputeId, SubjectRef judge,
                                                   long now, long expiresAt, String terms) {
        DisputeRecord dispute = requiredOpen(justice, disputeId);
        requireJudge(institutions, dispute, judge, now);
        if (dispute.evidence().isEmpty()) throw new IllegalStateException("不得在无证据时裁决");
        ProtectionOrder order = new ProtectionOrder(UUID.randomUUID(), dispute.id(),
                dispute.forumInstitutionId(), dispute.settlementId(), dispute.claimant(),
                dispute.respondent(), judge, now, expiresAt, terms);
        justice.addProtection(order);
        justice.update(dispute.decide(judge, now, terms,
                JudgmentSanction.PROTECTION_ORDER, order.id()));
        return order;
    }

    public static LevyAssessment decideFine(JusticeData justice, InstitutionData institutions,
                                             GovernanceLedgerData governance, UUID disputeId,
                                             SubjectRef judge, ItemFingerprint asset,
                                             int quantity, long now, long dueAt, String finding) {
        DisputeRecord dispute = requiredOpen(justice, disputeId);
        requireJudge(institutions, dispute, judge, now);
        if (dispute.evidence().isEmpty()) throw new IllegalStateException("不得在无证据时裁决");
        UUID assessmentId = UUID.randomUUID();
        LevyAssessment fine = LevyService.assess(institutions, governance, assessmentId,
                dispute.forumInstitutionId(), judge, dispute.settlementId(),
                dispute.respondent(), LevyType.FINE, asset, quantity, now, dueAt, finding);
        justice.update(dispute.decide(judge, now, finding,
                JudgmentSanction.FINE, assessmentId));
        return fine;
    }

    public static DisputeRecord decideMembershipSuspension(JusticeData justice,
                                                            InstitutionData institutions,
                                                            UUID disputeId, SubjectRef judge,
                                                            long now, String finding) {
        DisputeRecord dispute = requiredOpen(justice, disputeId);
        requireJudge(institutions, dispute, judge, now);
        if (dispute.evidence().isEmpty()) throw new IllegalStateException("不得在无证据时裁决");
        InstitutionMembership membership = institutions.membership(
                dispute.forumInstitutionId(), dispute.respondent());
        if (membership == null) throw new IllegalStateException("被申诉者不是该组织成员");
        institutions.putMembership(membership.suspended(finding));
        DisputeRecord decided = dispute.decide(judge, now, finding,
                JudgmentSanction.MEMBERSHIP_SUSPENSION, membership.id());
        justice.update(decided);
        return decided;
    }

    private static DisputeRecord requiredOpen(JusticeData justice, UUID id) {
        DisputeRecord dispute = justice.dispute(id);
        if (dispute == null || !dispute.status().open()) throw new IllegalStateException("案件不存在或已经结束");
        return dispute;
    }

    private static void requireJudge(InstitutionData institutions, DisputeRecord dispute,
                                     SubjectRef judge, long now) {
        var authority = institutions.authority(judge, dispute.forumInstitutionId(),
                OfficeAuthority.ADJUDICATE, now);
        if (!authority.allowed()) throw new IllegalStateException(authority.reason());
        if (institutions.jurisdictionContested(dispute.settlementId(),
                OfficeAuthority.ADJUDICATE, now)) {
            throw new IllegalStateException("裁判管辖权存在同级冲突");
        }
    }
}
