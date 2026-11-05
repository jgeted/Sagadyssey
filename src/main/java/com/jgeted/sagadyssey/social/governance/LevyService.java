package com.jgeted.sagadyssey.social.governance;

import com.jgeted.sagadyssey.social.institution.InstitutionData;
import com.jgeted.sagadyssey.social.institution.InstitutionRecord;
import com.jgeted.sagadyssey.social.institution.JurisdictionClaim;
import com.jgeted.sagadyssey.social.institution.OfficeAuthority;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ExpectedRevision;
import com.jgeted.sagadyssey.worldsim.transfer.TransferEndpoint;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLeg;
import com.jgeted.sagadyssey.worldsim.transfer.TransferReceipt;
import com.jgeted.sagadyssey.worldsim.transfer.TransferRequest;
import com.jgeted.sagadyssey.worldsim.transfer.TransferService;
import net.minecraft.world.Container;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Legal authority creates an obligation; only a successful inventory transfer discharges it. */
public final class LevyService {
    private LevyService() { }

    public static LevyAssessment assess(InstitutionData institutions,
                                        GovernanceLedgerData ledger, UUID assessmentId,
                                        UUID institutionId, SubjectRef assessor,
                                        UUID settlementId, SubjectRef debtor, LevyType type,
                                        ItemFingerprint asset, int quantity,
                                        long now, long dueAt, String basis) {
        var authority = institutions.authority(assessor, institutionId,
                type.assessmentAuthority(), now);
        if (!authority.allowed()) throw new IllegalStateException(authority.reason());
        InstitutionRecord institution = institutions.institution(institutionId);
        if (institution == null || institution.settlementId() == null
                || !institution.settlementId().equals(settlementId)) {
            throw new IllegalStateException("该机构不属于征收地");
        }
        List<JurisdictionClaim> claims = institutions.jurisdiction(settlementId,
                type.assessmentAuthority(), now);
        if (institutions.jurisdictionContested(settlementId, type.assessmentAuthority(), now)) {
            throw new IllegalStateException("征收管辖权存在同级冲突，需先裁决");
        }
        if (!claims.isEmpty() && !claims.getFirst().institutionId().equals(institutionId)) {
            throw new IllegalStateException("存在优先级更高的征收管辖权");
        }
        LevyAssessment assessment = new LevyAssessment(assessmentId, institutionId,
                settlementId, debtor, type, asset, quantity, 0, now, dueAt,
                LevyStatus.ASSESSED, basis, 0);
        if (!ledger.add(assessment)) throw new IllegalStateException("征收项目已经存在");
        return assessment;
    }

    public static TransferReceipt collect(InstitutionData institutions,
                                          GovernanceLedgerData governance,
                                          RightsStateData rights, TransferLedgerData transfers,
                                          UUID paymentId, UUID assessmentId, SubjectRef collector,
                                          UUID debtorContainerId, Container debtorContainer,
                                          Container treasuryContainer, int quantity, long now) {
        LevyPaymentRecord replay = governance.payment(paymentId);
        if (replay != null) {
            TransferReceipt receipt = transfers.receipt(replay.transferId());
            if (receipt == null) throw new IllegalStateException("缴纳记录缺少实体转移凭据");
            return receipt;
        }
        governance.reviewDue(now);
        LevyAssessment assessment = governance.assessment(assessmentId);
        if (assessment == null || !assessment.status().payable()) {
            throw new IllegalStateException("征收项目不存在或已结束");
        }
        if (quantity <= 0 || quantity > assessment.outstanding()) {
            throw new IllegalArgumentException("缴纳数量超出未缴余额");
        }
        var authority = institutions.authority(collector, assessment.institutionId(),
                OfficeAuthority.COLLECT_LEVY, now);
        if (!authority.allowed()) throw new IllegalStateException(authority.reason());
        InstitutionRecord institution = institutions.institution(assessment.institutionId());
        if (institution == null) throw new IllegalStateException("征收机构不存在");
        UUID treasuryId = institution.treasuryContainerId();
        if (rights.context(debtorContainerId) == null || rights.context(treasuryId) == null) {
            throw new IllegalStateException("缴纳容器没有权利上下文");
        }
        TransferRequest request = new TransferRequest(paymentId, assessment.debtor(),
                TransferIntent.PAYMENT,
                List.of(new TransferLeg(debtorContainerId, treasuryId,
                        assessment.asset(), quantity)),
                List.of(new ExpectedRevision(debtorContainerId,
                                rights.context(debtorContainerId).revision()),
                        new ExpectedRevision(treasuryId, rights.context(treasuryId).revision())),
                "levy:" + assessment.id(), now,
                Map.of(debtorContainerId, assessment.debtor()));
        TransferReceipt receipt = new TransferService(rights, transfers).submit(request,
                Map.of(debtorContainerId, new TransferEndpoint(debtorContainerId, debtorContainer),
                        treasuryId, new TransferEndpoint(treasuryId, treasuryContainer)), now);
        if (receipt.successful()) governance.recordPayment(paymentId, assessmentId, quantity, now);
        return receipt;
    }
}
