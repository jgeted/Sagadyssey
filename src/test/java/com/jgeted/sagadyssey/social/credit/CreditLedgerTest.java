package com.jgeted.sagadyssey.social.credit;

import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
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
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CreditLedgerTest {
    @Test
    void collateralBlocksOtherTransfersUntilPhysicalEmeraldDebtIsFullyRepaid() {
        CreditLedgerData credits = new CreditLedgerData();
        RightsStateData rights = new RightsStateData();
        TransferLedgerData transfers = new TransferLedgerData();
        SubjectRef creditor = SubjectRef.character(UUID.randomUUID());
        SubjectRef debtor = SubjectRef.character(UUID.randomUUID());
        UUID debtorBagId = UUID.randomUUID();
        UUID creditorBagId = UUID.randomUUID();
        SimpleContainer debtorBag = new SimpleContainer(3);
        SimpleContainer creditorBag = new SimpleContainer(3);
        debtorBag.setItem(0, new ItemStack(Items.DIAMOND));
        debtorBag.setItem(1, new ItemStack(Items.EMERALD, 5));
        rights.ensureContext(debtorBagId, "debtor", debtor, debtor, AccessPolicy.PRIVATE);
        rights.ensureContext(creditorBagId, "creditor", creditor, creditor, AccessPolicy.PRIVATE);
        ItemFingerprint diamond = ItemFingerprint.of(new ItemStack(Items.DIAMOND));
        UUID agreementId = UUID.randomUUID();
        CreditAgreement agreement = CreditService.issue(credits, rights, agreementId,
                UUID.randomUUID(), creditor, debtor, CreditPurpose.TRADE_PURCHASE,
                5, 0L, 24_000L,
                List.of(new CollateralPledge(debtorBagId, diamond, 1, debtor)),
                List.of(), Map.of(debtorBagId, debtorBag), "赊购铁具");

        TransferRequest attemptedSale = new TransferRequest(UUID.randomUUID(), debtor,
                TransferIntent.GIFT,
                List.of(new TransferLeg(debtorBagId, creditorBagId, diamond, 1)),
                List.of(new ExpectedRevision(debtorBagId, 0L),
                        new ExpectedRevision(creditorBagId, 0L)), "unrelated-sale", 10L);
        TransferReceipt blocked = new TransferService(rights, transfers).submit(attemptedSale,
                Map.of(debtorBagId, new TransferEndpoint(debtorBagId, debtorBag),
                        creditorBagId, new TransferEndpoint(creditorBagId, creditorBag)), 10L);
        assertEquals(TransferReceipt.Status.RIGHTS_DENIED, blocked.status());

        UUID firstPayment = UUID.randomUUID();
        assertTrue(CreditSettlementService.repay(credits, rights, transfers, firstPayment,
                agreementId, 2, debtorBagId, debtorBag, creditorBagId, creditorBag, 20L).successful());
        assertEquals(CreditStatus.PARTIALLY_PAID, credits.agreement(agreementId).status());
        assertEquals(3, credits.agreement(agreementId).outstandingEmeralds());
        assertEquals(1, rights.reservedQuantity(debtorBagId, diamond, debtor, null, 21L));
        assertEquals(2, creditorBag.getItem(0).getCount());

        // Retrying the same payment id replays its transfer receipt and never reduces debt twice.
        assertTrue(CreditSettlementService.repay(credits, rights, transfers, firstPayment,
                agreementId, 2, debtorBagId, debtorBag, creditorBagId, creditorBag, 21L).successful());
        assertEquals(3, credits.agreement(agreementId).outstandingEmeralds());

        assertTrue(CreditSettlementService.repay(credits, rights, transfers, UUID.randomUUID(),
                agreementId, 3, debtorBagId, debtorBag, creditorBagId, creditorBag, 30L).successful());
        assertEquals(CreditStatus.SETTLED, credits.agreement(agreementId).status());
        assertEquals(0, rights.reservedQuantity(debtorBagId, diamond, debtor, null, 31L));
        assertEquals(5, creditorBag.getItem(0).getCount());
        assertEquals(agreement, CreditAgreement.load(agreement.save()));
    }

    @Test
    void overdueHistoryIsScopedToCounterpartyAndPurpose() {
        CreditLedgerData credits = new CreditLedgerData();
        RightsStateData rights = new RightsStateData();
        SubjectRef creditor = SubjectRef.character(UUID.randomUUID());
        SubjectRef debtor = SubjectRef.character(UUID.randomUUID());
        CreditService.issue(credits, rights, UUID.randomUUID(), UUID.randomUUID(), creditor,
                debtor, CreditPurpose.TRADE_PURCHASE, 8, 0L, 100L,
                List.of(), List.of(), Map.of(), "trade debt");

        CreditProfile trade = credits.profile(creditor, debtor,
                CreditPurpose.TRADE_PURCHASE, 101L);
        CreditProfile transport = credits.profile(creditor, debtor,
                CreditPurpose.TRANSPORT, 101L);
        CreditProfile otherCreditor = credits.profile(SubjectRef.character(UUID.randomUUID()),
                debtor, CreditPurpose.TRADE_PURCHASE, 101L);

        assertEquals(1, trade.overdueAgreements());
        assertEquals(8, trade.currentOutstanding());
        assertEquals(0, transport.agreements());
        assertEquals(0, otherCreditor.agreements());
        assertNotEquals(trade.reliabilityPercent(), transport.reliabilityPercent());
    }

    @Test
    void defaultRequiresSecurityOrAThirdPartyGuaranteeForNewCredit() {
        SubjectRef creditor = SubjectRef.character(UUID.randomUUID());
        SubjectRef debtor = SubjectRef.character(UUID.randomUUID());
        CreditProfile defaulted = new CreditProfile(creditor, debtor,
                CreditPurpose.TRADE_PURCHASE, 2, 12, 4, 8, 0, 1);

        assertFalse(CreditPolicy.evaluate(defaulted, 4, 20,
                List.of(), List.of()).accepted());
        assertTrue(CreditPolicy.evaluate(defaulted, 4, 20, List.of(),
                List.of(new CreditGuarantee(SubjectRef.character(UUID.randomUUID()),
                        10, "行会担保"))).accepted());
    }

    @Test
    void payingAfterDeadlineSettlesAssetsButPreservesLateHistory() {
        CreditLedgerData credits = new CreditLedgerData();
        RightsStateData rights = new RightsStateData();
        TransferLedgerData transfers = new TransferLedgerData();
        SubjectRef creditor = SubjectRef.character(UUID.randomUUID());
        SubjectRef debtor = SubjectRef.character(UUID.randomUUID());
        UUID debtorBagId = UUID.randomUUID();
        UUID creditorBagId = UUID.randomUUID();
        SimpleContainer debtorBag = new SimpleContainer(2);
        SimpleContainer creditorBag = new SimpleContainer(2);
        debtorBag.setItem(0, new ItemStack(Items.EMERALD, 5));
        rights.ensureContext(debtorBagId, "debtor", debtor, debtor, AccessPolicy.PRIVATE);
        rights.ensureContext(creditorBagId, "creditor", creditor, creditor, AccessPolicy.PRIVATE);
        UUID agreementId = UUID.randomUUID();
        CreditService.issue(credits, rights, agreementId, UUID.randomUUID(), creditor, debtor,
                CreditPurpose.TRADE_PURCHASE, 5, 0L, 100L,
                List.of(), List.of(), Map.of(), "late purchase");

        var receipt = CreditSettlementService.repay(credits, rights, transfers, UUID.randomUUID(),
                agreementId, 5, debtorBagId, debtorBag, creditorBagId, creditorBag, 101L);

        assertTrue(receipt.successful());
        assertEquals(CreditStatus.SETTLED_LATE, credits.agreement(agreementId).status());
        CreditProfile profile = credits.profile(creditor, debtor,
                CreditPurpose.TRADE_PURCHASE, 102L);
        assertEquals(1, profile.overdueAgreements());
        assertEquals(1, profile.defaultedAgreements());
        assertEquals(0, profile.currentOutstanding());
        assertEquals(5, profile.repaidEmeralds());
    }
}
