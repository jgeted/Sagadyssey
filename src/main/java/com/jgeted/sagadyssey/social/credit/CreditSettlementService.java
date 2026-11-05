package com.jgeted.sagadyssey.social.credit;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.transfer.ExpectedRevision;
import com.jgeted.sagadyssey.worldsim.transfer.TransferEndpoint;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLeg;
import com.jgeted.sagadyssey.worldsim.transfer.TransferReceipt;
import com.jgeted.sagadyssey.worldsim.transfer.TransferRequest;
import com.jgeted.sagadyssey.worldsim.transfer.TransferService;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Repayment is valid only when the corresponding emerald items move atomically. */
public final class CreditSettlementService {
    private CreditSettlementService() { }

    public static TransferReceipt repay(CreditLedgerData credits, RightsStateData rights,
                                        TransferLedgerData transfers, UUID paymentId,
                                        UUID agreementId, int emeralds,
                                        UUID debtorContainerId, Container debtorContainer,
                                        UUID creditorContainerId, Container creditorContainer,
                                        long now) {
        CreditPaymentRecord replayedPayment = credits.payment(paymentId);
        if (replayedPayment != null) {
            TransferReceipt replayedReceipt = transfers.receipt(paymentId);
            if (replayedReceipt != null) return replayedReceipt;
            throw new IllegalStateException("还款记录存在，但实体转移凭据缺失");
        }
        // A payment arriving after the deadline must not bypass delinquency merely because no
        // periodic ledger review happened earlier in this tick.
        credits.reviewDue(now);
        CreditAgreement agreement = credits.agreement(agreementId);
        if (agreement == null || !agreement.status().payable()) {
            throw new IllegalStateException("债务不存在或已经结束");
        }
        if (emeralds <= 0 || emeralds > agreement.outstandingEmeralds()) {
            throw new IllegalArgumentException("还款金额超出未偿余额");
        }
        if (rights.context(debtorContainerId) == null || rights.context(creditorContainerId) == null) {
            throw new IllegalStateException("还款容器没有权利上下文");
        }
        TransferRequest request = new TransferRequest(paymentId, agreement.debtor(),
                TransferIntent.PAYMENT,
                List.of(new TransferLeg(debtorContainerId, creditorContainerId,
                        ItemFingerprint.of(new ItemStack(Items.EMERALD)), emeralds)),
                List.of(new ExpectedRevision(debtorContainerId,
                                rights.context(debtorContainerId).revision()),
                        new ExpectedRevision(creditorContainerId,
                                rights.context(creditorContainerId).revision())),
                "credit_payment:" + agreementId, now,
                Map.of(debtorContainerId, agreement.debtor()));
        TransferReceipt receipt = new TransferService(rights, transfers).submit(request,
                Map.of(debtorContainerId, new TransferEndpoint(debtorContainerId, debtorContainer),
                        creditorContainerId, new TransferEndpoint(creditorContainerId,
                                creditorContainer)), now);
        if (receipt.successful() && credits.payment(paymentId) == null) {
            credits.recordPayment(paymentId, agreementId, emeralds, now);
            CreditAgreement updated = credits.agreement(agreementId);
            if (updated != null && updated.status().terminal()) {
                rights.releaseReservations(updated.reservationToken());
            }
        }
        return receipt;
    }
}
