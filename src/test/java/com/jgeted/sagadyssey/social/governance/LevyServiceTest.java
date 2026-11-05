package com.jgeted.sagadyssey.social.governance;

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

class LevyServiceTest {
    @Test
    void inKindRentIsPaidOnlyWhenWheatReachesPhysicalTreasury() {
        Fixture fixture = fixture(10);
        LevyAssessment rent = LevyService.assess(fixture.institutions, fixture.governance,
                UUID.randomUUID(), fixture.institutionId, fixture.official,
                fixture.settlementId, fixture.tenant, LevyType.RENT,
                ItemFingerprint.of(new ItemStack(Items.WHEAT)), 6,
                0L, 100L, "秋收地租");

        UUID firstPayment = UUID.randomUUID();
        var first = LevyService.collect(fixture.institutions, fixture.governance,
                fixture.rights, fixture.transfers, firstPayment, rent.id(), fixture.official,
                fixture.tenantBagId, fixture.tenantBag, fixture.treasury, 2, 50L);

        assertTrue(first.successful());
        assertEquals(LevyStatus.PARTIALLY_PAID,
                fixture.governance.assessment(rent.id()).status());
        assertEquals(2, count(fixture.treasury, Items.WHEAT));
        assertEquals(8, count(fixture.tenantBag, Items.WHEAT));

        // An idempotent retry returns the same transfer evidence and never pays twice.
        assertTrue(LevyService.collect(fixture.institutions, fixture.governance,
                fixture.rights, fixture.transfers, firstPayment, rent.id(), fixture.official,
                fixture.tenantBagId, fixture.tenantBag, fixture.treasury, 2, 51L).successful());
        assertEquals(2, fixture.governance.assessment(rent.id()).paidQuantity());

        var late = LevyService.collect(fixture.institutions, fixture.governance,
                fixture.rights, fixture.transfers, UUID.randomUUID(), rent.id(), fixture.official,
                fixture.tenantBagId, fixture.tenantBag, fixture.treasury, 4, 101L);
        assertTrue(late.successful());
        assertEquals(LevyStatus.SATISFIED_LATE,
                fixture.governance.assessment(rent.id()).status());
        assertEquals(6, count(fixture.treasury, Items.WHEAT));
        assertEquals(4, count(fixture.tenantBag, Items.WHEAT));
    }

    @Test
    void failedPhysicalCollectionDoesNotAlterTheAssessment() {
        Fixture fixture = fixture(3);
        LevyAssessment rent = LevyService.assess(fixture.institutions, fixture.governance,
                UUID.randomUUID(), fixture.institutionId, fixture.official,
                fixture.settlementId, fixture.tenant, LevyType.RENT,
                ItemFingerprint.of(new ItemStack(Items.WHEAT)), 5,
                0L, 100L, "实物租");

        var receipt = LevyService.collect(fixture.institutions, fixture.governance,
                fixture.rights, fixture.transfers, UUID.randomUUID(), rent.id(), fixture.official,
                fixture.tenantBagId, fixture.tenantBag, fixture.treasury, 5, 10L);

        assertFalse(receipt.successful());
        assertEquals(0, fixture.governance.assessment(rent.id()).paidQuantity());
        assertEquals(3, count(fixture.tenantBag, Items.WHEAT));
        assertEquals(0, count(fixture.treasury, Items.WHEAT));
    }

    private static Fixture fixture(int wheat) {
        InstitutionData institutions = new InstitutionData();
        GovernanceLedgerData governance = new GovernanceLedgerData();
        RightsStateData rights = new RightsStateData();
        TransferLedgerData transfers = new TransferLedgerData();
        UUID institutionId = UUID.randomUUID();
        UUID settlementId = UUID.randomUUID();
        UUID treasuryId = UUID.randomUUID();
        UUID tenantBagId = UUID.randomUUID();
        SubjectRef official = SubjectRef.character(UUID.randomUUID());
        SubjectRef tenant = SubjectRef.character(UUID.randomUUID());
        SimpleContainer tenantBag = new SimpleContainer(4);
        SimpleContainer treasury = new SimpleContainer(8);
        tenantBag.setItem(0, new ItemStack(Items.WHEAT, wheat));
        InstitutionService.establish(institutions, rights, institutionId, "领地家政",
                InstitutionKind.LORD_HOUSEHOLD, settlementId, treasuryId, official,
                Set.of(OfficeAuthority.ASSESS_RENT, OfficeAuthority.COLLECT_LEVY),
                0L, "以收获物缴纳约定地租");
        rights.ensureContext(tenantBagId, "tenant", tenant, tenant, AccessPolicy.PRIVATE);
        return new Fixture(institutions, governance, rights, transfers, institutionId,
                settlementId, treasuryId, tenantBagId, official, tenant, tenantBag, treasury);
    }

    private static int count(SimpleContainer container, net.minecraft.world.item.Item item) {
        int result = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) result += container.getItem(slot).getCount();
        }
        return result;
    }

    private record Fixture(InstitutionData institutions, GovernanceLedgerData governance,
                           RightsStateData rights, TransferLedgerData transfers,
                           UUID institutionId, UUID settlementId, UUID treasuryId,
                           UUID tenantBagId, SubjectRef official, SubjectRef tenant,
                           SimpleContainer tenantBag, SimpleContainer treasury) { }
}
