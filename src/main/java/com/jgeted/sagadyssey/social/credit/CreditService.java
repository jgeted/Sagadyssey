package com.jgeted.sagadyssey.social.credit;

import com.jgeted.sagadyssey.worldsim.rights.AssetReservation;
import com.jgeted.sagadyssey.worldsim.rights.AssetRight;
import com.jgeted.sagadyssey.worldsim.rights.RightsService;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.RightsVerdict;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Opens a debt only after locking every promised physical collateral line. */
public final class CreditService {
    private CreditService() { }

    public static CreditAgreement issue(CreditLedgerData ledger, RightsStateData rights,
                                        UUID agreementId, UUID settlementId,
                                        SubjectRef creditor, SubjectRef debtor,
                                        CreditPurpose purpose, int principalEmeralds,
                                        long issuedAt, long dueAt,
                                        List<CollateralPledge> collateral,
                                        List<CreditGuarantee> guarantees,
                                        Map<UUID, Container> collateralContainers,
                                        String note) {
        CreditAgreement existing = ledger.agreement(agreementId);
        if (existing != null) return existing;
        collateral = List.copyOf(collateral == null ? List.of() : collateral);
        guarantees = List.copyOf(guarantees == null ? List.of() : guarantees);
        collateralContainers = Map.copyOf(collateralContainers == null ? Map.of() : collateralContainers);
        CreditAgreement agreement = new CreditAgreement(agreementId, settlementId, creditor,
                debtor, purpose, principalEmeralds, principalEmeralds, issuedAt, dueAt,
                issuedAt, CreditStatus.ACTIVE, collateral, guarantees, note, 0);
        Set<UUID> containerIds = new HashSet<>();
        collateral.forEach(value -> containerIds.add(value.containerId()));
        RightsStateData.TransactionSnapshot snapshot = rights.snapshotFor(containerIds);
        RightsService resolver = new RightsService(rights);
        try {
            for (CollateralPledge pledge : collateral) {
                Container container = collateralContainers.get(pledge.containerId());
                if (container == null || rights.context(pledge.containerId()) == null) {
                    throw new IllegalStateException("抵押物容器当前不可用或未登记");
                }
                int available = 0;
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (!pledge.fingerprint().matches(stack)) continue;
                    RightsVerdict verdict = resolver.can(pledge.pledgor(), AssetRight.TRANSFER,
                            pledge.containerId(), slot, pledge.fingerprint(), issuedAt);
                    if (verdict.status() == RightsVerdict.Status.ALLOW) available += stack.getCount();
                }
                AssetReservation reservation = new AssetReservation(UUID.randomUUID(),
                        pledge.containerId(), pledge.fingerprint(), pledge.quantity(),
                        pledge.pledgor(), agreement.reservationToken(),
                        "credit_collateral:" + agreement.id(), issuedAt, Long.MAX_VALUE);
                if (!rights.reserve(reservation, available, issuedAt)) {
                    throw new IllegalStateException("抵押物不足或已承诺给其他事务");
                }
            }
            if (!ledger.add(agreement)) throw new IllegalStateException("债务编号已经存在");
            return agreement;
        } catch (RuntimeException exception) {
            rights.restore(snapshot);
            throw exception;
        }
    }
}
