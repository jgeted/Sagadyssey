package com.jgeted.sagadyssey.social.commons;

import com.jgeted.sagadyssey.social.institution.InstitutionData;
import com.jgeted.sagadyssey.social.institution.InstitutionKind;
import com.jgeted.sagadyssey.social.institution.InstitutionMembership;
import com.jgeted.sagadyssey.social.institution.InstitutionService;
import com.jgeted.sagadyssey.social.institution.MembershipRank;
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

class CommonsServiceTest {
    @Test
    void membershipQuotaAndMaintenanceReserveConstrainPhysicalWithdrawals() {
        InstitutionData institutions = new InstitutionData();
        CommonsData commons = new CommonsData();
        RightsStateData rights = new RightsStateData();
        TransferLedgerData transfers = new TransferLedgerData();
        UUID institutionId = UUID.randomUUID();
        UUID settlementId = UUID.randomUUID();
        UUID commonStoreId = UUID.randomUUID();
        UUID memberBagId = UUID.randomUUID();
        SubjectRef official = SubjectRef.character(UUID.randomUUID());
        SubjectRef member = SubjectRef.character(UUID.randomUUID());
        SimpleContainer commonStore = new SimpleContainer(4);
        SimpleContainer memberBag = new SimpleContainer(4);
        commonStore.setItem(0, new ItemStack(Items.OAK_LOG, 10));
        InstitutionService.establish(institutions, rights, institutionId, "林地公会",
                InstitutionKind.SETTLEMENT_COUNCIL, settlementId, commonStoreId, official,
                Set.of(OfficeAuthority.SET_COMMONS_QUOTA), 0L, "成员定额取用林木");
        rights.ensureContext(memberBagId, "member", member, member, AccessPolicy.PRIVATE);
        InstitutionMembership membership = new InstitutionMembership(UUID.randomUUID(),
                institutionId, member, MembershipRank.RESIDENT, 50, 0L, true, "本地居民");
        assertTrue(institutions.addMembership(membership));
        UUID policyId = UUID.randomUUID();
        CommonsService.createPolicy(institutions, commons, rights, policyId, institutionId,
                official, settlementId, commonStoreId,
                ItemFingerprint.of(new ItemStack(Items.OAK_LOG)), 3, 4, 25,
                0L, 0L, "住宅修缮木材");

        assertTrue(CommonsService.withdraw(institutions, commons, rights, transfers,
                UUID.randomUUID(), policyId, member, commonStore, memberBagId,
                memberBag, 3, 10L).successful());
        assertEquals(7, count(commonStore, Items.OAK_LOG));
        assertEquals(3, count(memberBag, Items.OAK_LOG));
        assertThrows(IllegalArgumentException.class, () -> CommonsService.withdraw(
                institutions, commons, rights, transfers, UUID.randomUUID(), policyId,
                member, commonStore, memberBagId, memberBag, 1, 11L));

        assertTrue(CommonsService.withdraw(institutions, commons, rights, transfers,
                UUID.randomUUID(), policyId, member, commonStore, memberBagId,
                memberBag, 3, 24_010L).successful());
        assertEquals(4, count(commonStore, Items.OAK_LOG));
        assertThrows(IllegalStateException.class, () -> CommonsService.withdraw(
                institutions, commons, rights, transfers, UUID.randomUUID(), policyId,
                member, commonStore, memberBagId, memberBag, 1, 48_010L));

        institutions.putMembership(membership.suspended("破坏幼林"));
        assertThrows(IllegalStateException.class, () -> CommonsService.withdraw(
                institutions, commons, rights, transfers, UUID.randomUUID(), policyId,
                member, commonStore, memberBagId, memberBag, 1, 72_010L));
    }

    private static int count(SimpleContainer container, net.minecraft.world.item.Item item) {
        int result = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) result += container.getItem(slot).getCount();
        }
        return result;
    }
}
