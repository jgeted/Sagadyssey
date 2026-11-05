package com.jgeted.sagadyssey.social.justice;

import com.jgeted.sagadyssey.social.governance.GovernanceLedgerData;
import com.jgeted.sagadyssey.social.governance.LevyService;
import com.jgeted.sagadyssey.social.governance.LevyStatus;
import com.jgeted.sagadyssey.social.institution.InstitutionData;
import com.jgeted.sagadyssey.social.institution.InstitutionKind;
import com.jgeted.sagadyssey.social.institution.InstitutionService;
import com.jgeted.sagadyssey.social.institution.OfficeAuthority;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdjudicationServiceTest {
    @Test
    void onlyARealJudgeCanIssueAnEvidenceBackedExpiringProtectionOrder() {
        Fixture fixture = fixture(Set.of(OfficeAuthority.ADJUDICATE));
        JusticeData justice = new JusticeData();
        UUID disputeId = UUID.randomUUID();
        AdjudicationService.file(justice, fixture.institutions, disputeId,
                fixture.courtId, fixture.settlementId, fixture.claimant,
                fixture.respondent, DisputeType.ASSAULT, "持续威胁", 1L);

        assertThrows(IllegalStateException.class, () -> AdjudicationService.decideProtection(
                justice, fixture.institutions, disputeId, fixture.judge,
                2L, 100L, "禁止接近住处"));
        AdjudicationService.submitEvidence(justice, disputeId,
                new EvidenceRef(UUID.randomUUID(), EvidenceKind.WITNESS_STATEMENT,
                        UUID.randomUUID(), fixture.claimant, 3L, "邻居证言"));
        assertThrows(IllegalStateException.class, () -> AdjudicationService.decideProtection(
                justice, fixture.institutions, disputeId,
                SubjectRef.character(UUID.randomUUID()), 4L, 100L, "无权命令"));

        ProtectionOrder order = AdjudicationService.decideProtection(justice,
                fixture.institutions, disputeId, fixture.judge, 5L, 100L, "禁止接近住处");
        assertTrue(order.activeAt(99L));
        assertFalse(order.activeAt(100L));
        assertEquals(1, justice.activeProtections(fixture.claimant, 50L).size());
        assertEquals(DisputeStatus.DECIDED, justice.dispute(disputeId).status());
        assertEquals(JudgmentSanction.PROTECTION_ORDER, justice.dispute(disputeId).sanction());
    }

    @Test
    void aFineBecomesACollectiblePhysicalObligationRatherThanDeletingEmeralds() {
        Fixture fixture = fixture(Set.of(OfficeAuthority.ADJUDICATE,
                OfficeAuthority.COLLECT_LEVY));
        JusticeData justice = new JusticeData();
        GovernanceLedgerData governance = new GovernanceLedgerData();
        TransferLedgerData transfers = new TransferLedgerData();
        UUID respondentBagId = UUID.randomUUID();
        SimpleContainer respondentBag = new SimpleContainer(3);
        SimpleContainer courtTreasury = new SimpleContainer(3);
        respondentBag.setItem(0, new ItemStack(Items.EMERALD, 3));
        fixture.rights.ensureContext(respondentBagId, "respondent", fixture.respondent,
                fixture.respondent, AccessPolicy.PRIVATE);
        UUID disputeId = UUID.randomUUID();
        AdjudicationService.file(justice, fixture.institutions, disputeId,
                fixture.courtId, fixture.settlementId, fixture.claimant,
                fixture.respondent, DisputeType.CONTRACT, "拒绝交付", 1L);
        AdjudicationService.submitEvidence(justice, disputeId,
                new EvidenceRef(UUID.randomUUID(), EvidenceKind.CONTRACT, UUID.randomUUID(),
                        fixture.claimant, 2L, "签署的交付契约"));

        var fine = AdjudicationService.decideFine(justice, fixture.institutions,
                governance, disputeId, fixture.judge,
                ItemFingerprint.of(new ItemStack(Items.EMERALD)), 2,
                3L, 100L, "违约罚金");
        assertEquals(0, fine.paidQuantity());
        assertEquals(3, count(respondentBag, Items.EMERALD));

        var receipt = LevyService.collect(fixture.institutions, governance, fixture.rights,
                transfers, UUID.randomUUID(), fine.id(), fixture.judge,
                respondentBagId, respondentBag, courtTreasury, 2, 4L);
        assertTrue(receipt.successful());
        assertEquals(LevyStatus.SATISFIED, governance.assessment(fine.id()).status());
        assertEquals(1, count(respondentBag, Items.EMERALD));
        assertEquals(2, count(courtTreasury, Items.EMERALD));
    }

    private static Fixture fixture(Set<OfficeAuthority> powers) {
        InstitutionData institutions = new InstitutionData();
        RightsStateData rights = new RightsStateData();
        UUID courtId = UUID.randomUUID();
        UUID settlementId = UUID.randomUUID();
        SubjectRef judge = SubjectRef.character(UUID.randomUUID());
        SubjectRef claimant = SubjectRef.character(UUID.randomUUID());
        SubjectRef respondent = SubjectRef.character(UUID.randomUUID());
        InstitutionService.establish(institutions, rights, courtId, "河湾法庭",
                InstitutionKind.COURT, settlementId, UUID.randomUUID(), judge,
                powers, 0L, "证据、答辩与公开裁决");
        return new Fixture(institutions, rights, courtId, settlementId,
                judge, claimant, respondent);
    }

    private static int count(SimpleContainer container, net.minecraft.world.item.Item item) {
        int result = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) result += container.getItem(slot).getCount();
        }
        return result;
    }

    private record Fixture(InstitutionData institutions, RightsStateData rights,
                           UUID courtId, UUID settlementId, SubjectRef judge,
                           SubjectRef claimant, SubjectRef respondent) { }
}
