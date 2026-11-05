package com.jgeted.sagadyssey.social.commons;

import com.jgeted.sagadyssey.social.institution.InstitutionData;
import com.jgeted.sagadyssey.social.institution.InstitutionMembership;
import com.jgeted.sagadyssey.social.institution.JurisdictionClaim;
import com.jgeted.sagadyssey.social.institution.OfficeAuthority;
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

public final class CommonsService {
    private CommonsService() { }

    public static CommonsPolicy createPolicy(InstitutionData institutions, CommonsData commons,
                                             RightsStateData rights, UUID policyId,
                                             UUID institutionId, SubjectRef official,
                                             UUID settlementId, UUID sourceContainerId,
                                             com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint asset,
                                             int dailyQuota, int maintenanceReserve,
                                             int minimumStanding, long startsAt, long endsAt,
                                             String purpose) {
        var authority = institutions.authority(official, institutionId,
                OfficeAuthority.SET_COMMONS_QUOTA, startsAt);
        if (!authority.allowed()) throw new IllegalStateException(authority.reason());
        if (institutions.jurisdictionContested(settlementId,
                OfficeAuthority.SET_COMMONS_QUOTA, startsAt)) {
            throw new IllegalStateException("公地管理权存在同级冲突");
        }
        List<JurisdictionClaim> claims = institutions.jurisdiction(settlementId,
                OfficeAuthority.SET_COMMONS_QUOTA, startsAt);
        if (!claims.isEmpty() && !claims.getFirst().institutionId().equals(institutionId)) {
            throw new IllegalStateException("存在优先级更高的公地管理者");
        }
        var context = rights.context(sourceContainerId);
        if (context == null || !context.defaultBeneficialOwner()
                .equals(SubjectRef.institution(institutionId))) {
            throw new IllegalStateException("公共仓储不属于管理机构");
        }
        CommonsPolicy policy = new CommonsPolicy(policyId, institutionId, settlementId,
                sourceContainerId, asset, dailyQuota, maintenanceReserve, minimumStanding,
                startsAt, endsAt, true, purpose);
        if (!commons.addPolicy(policy)) throw new IllegalStateException("公地规则已经存在");
        return policy;
    }

    public static TransferReceipt withdraw(InstitutionData institutions, CommonsData commons,
                                           RightsStateData rights, TransferLedgerData transfers,
                                           UUID withdrawalId, UUID policyId, SubjectRef member,
                                           Container source, UUID destinationContainerId,
                                           Container destination, int quantity, long now) {
        CommonsWithdrawal replay = commons.withdrawal(withdrawalId);
        if (replay != null) {
            TransferReceipt receipt = transfers.receipt(replay.transferId());
            if (receipt == null) throw new IllegalStateException("公地领取记录缺少实体转移凭据");
            return receipt;
        }
        CommonsPolicy policy = commons.policy(policyId);
        if (policy == null || !policy.activeAt(now)) throw new IllegalStateException("公地规则无效");
        InstitutionMembership membership = institutions.membership(policy.institutionId(), member);
        if (membership == null || membership.standing() < policy.minimumStanding()) {
            throw new IllegalStateException("不是合格成员或成员信誉不足");
        }
        int used = commons.usedToday(policyId, member, now);
        if (quantity <= 0 || used + quantity > policy.dailyQuota()) {
            throw new IllegalArgumentException("超过个人当日配额");
        }
        int physical = 0;
        for (int slot = 0; slot < source.getContainerSize(); slot++) {
            if (policy.asset().matches(source.getItem(slot))) physical += source.getItem(slot).getCount();
        }
        int reserved = rights.reservedQuantity(policy.sourceContainerId(), policy.asset(), null, now);
        if (physical - reserved - policy.maintenanceReserve() < quantity) {
            throw new IllegalStateException("领取将侵占维护储备或已预留物资");
        }
        if (rights.context(destinationContainerId) == null) {
            throw new IllegalStateException("领取者容器没有权利上下文");
        }
        SubjectRef institution = SubjectRef.institution(policy.institutionId());
        TransferRequest request = new TransferRequest(withdrawalId, member, TransferIntent.DELIVERY,
                List.of(new TransferLeg(policy.sourceContainerId(), destinationContainerId,
                        policy.asset(), quantity)),
                List.of(new ExpectedRevision(policy.sourceContainerId(),
                                rights.context(policy.sourceContainerId()).revision()),
                        new ExpectedRevision(destinationContainerId,
                                rights.context(destinationContainerId).revision())),
                "commons:" + policy.id(), now,
                Map.of(policy.sourceContainerId(), institution));
        TransferReceipt receipt = new TransferService(rights, transfers).submit(request,
                Map.of(policy.sourceContainerId(), new TransferEndpoint(policy.sourceContainerId(), source),
                        destinationContainerId, new TransferEndpoint(destinationContainerId, destination)), now);
        if (receipt.successful()) commons.record(new CommonsWithdrawal(withdrawalId, policyId,
                member, quantity, Math.floorDiv(now, 24_000L), now, withdrawalId));
        return receipt;
    }
}
