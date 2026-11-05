package com.jgeted.sagadyssey.worldsim.transfer;

import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.AssetClaim;
import com.jgeted.sagadyssey.worldsim.rights.AssetReservation;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConsumptionServiceTest {
    @Test
    void consumptionShrinksPhysicalStackAndItsExceptionalClaimTogether() {
        RightsStateData rights = new RightsStateData();
        UUID containerId = UUID.randomUUID();
        SubjectRef owner = SubjectRef.character(UUID.randomUUID());
        SimpleContainer pantry = new SimpleContainer(2);
        pantry.setItem(0, new ItemStack(Items.BREAD, 3));
        ItemFingerprint bread = ItemFingerprint.of(pantry.getItem(0));
        rights.ensureContext(containerId, "test:pantry", owner, owner, AccessPolicy.PRIVATE);
        UUID claimId = UUID.randomUUID();
        rights.putClaim(new AssetClaim(claimId, containerId, 0, bread, 3, owner, owner,
                Set.of("family_ration"), Set.of(), "test", 0L));

        ConsumptionService.Result result = new ConsumptionService(rights).consume(containerId,
                pantry, owner, bread, 1, "meal:test", 10L);

        assertTrue(result.successful());
        assertEquals(2, pantry.getItem(0).getCount());
        AssetClaim claim = rights.claimAt(containerId, 0, bread);
        assertNotNull(claim);
        assertEquals(claimId, claim.claimId());
        assertEquals(2, claim.quantity());
        assertEquals(1L, claim.revision());
        assertEquals(1L, result.finalRevision());
    }

    @Test
    void productionReservationBlocksUnrelatedMealWithoutSideEffects() {
        RightsStateData rights = new RightsStateData();
        UUID containerId = UUID.randomUUID();
        SubjectRef owner = SubjectRef.character(UUID.randomUUID());
        SimpleContainer pantry = new SimpleContainer(1);
        pantry.setItem(0, new ItemStack(Items.BREAD, 2));
        ItemFingerprint bread = ItemFingerprint.of(pantry.getItem(0));
        rights.ensureContext(containerId, "test:pantry", owner, owner, AccessPolicy.PRIVATE);
        assertTrue(rights.reserve(new AssetReservation(UUID.randomUUID(), containerId, bread,
                2, owner, "production:test", "packed lunch", 0L, 100L), 2, 0L));

        ConsumptionService.Result result = new ConsumptionService(rights).consume(containerId,
                pantry, owner, bread, 1, "meal:test", 10L);

        assertEquals(ConsumptionService.Status.RESERVED, result.status());
        assertEquals(2, pantry.getItem(0).getCount());
        assertEquals(0L, rights.context(containerId).revision());
    }

    @Test
    void oneOwnersReservationDoesNotLockAnotherOwnersIdenticalFood() {
        RightsStateData rights = new RightsStateData();
        UUID containerId = UUID.randomUUID();
        SubjectRef firstOwner = SubjectRef.character(UUID.randomUUID());
        SubjectRef secondOwner = SubjectRef.character(UUID.randomUUID());
        SimpleContainer pantry = new SimpleContainer(2);
        pantry.setItem(0, new ItemStack(Items.BREAD, 2));
        pantry.setItem(1, new ItemStack(Items.BREAD, 2));
        ItemFingerprint bread = ItemFingerprint.of(pantry.getItem(0));
        rights.ensureContext(containerId, "test:mixed", firstOwner, firstOwner, AccessPolicy.PRIVATE);
        rights.putClaim(new AssetClaim(UUID.randomUUID(), containerId, 1, bread, 2,
                secondOwner, firstOwner, Set.of(), Set.of(), "second-owner", 0L));
        assertTrue(rights.reserve(new AssetReservation(UUID.randomUUID(), containerId, bread,
                2, firstOwner, "production:first", "first owner's batch", 0L, 100L), 4, 0L));

        ConsumptionService.Result result = new ConsumptionService(rights).consume(containerId,
                pantry, secondOwner, bread, 1, "meal:second", 10L);

        assertTrue(result.successful());
        assertEquals(2, pantry.getItem(0).getCount());
        assertEquals(1, pantry.getItem(1).getCount());
        assertEquals(2, rights.reservedQuantity(containerId, bread, firstOwner, null, 10L));
    }

    @Test
    void identicalGoodsCanBeReservedIndependentlyByTheirBeneficialOwners() {
        RightsStateData rights = new RightsStateData();
        UUID containerId = UUID.randomUUID();
        SubjectRef firstOwner = SubjectRef.character(UUID.randomUUID());
        SubjectRef secondOwner = SubjectRef.character(UUID.randomUUID());
        ItemFingerprint iron = ItemFingerprint.of(new ItemStack(Items.IRON_INGOT));
        rights.ensureContext(containerId, "test:mixed", firstOwner, firstOwner, AccessPolicy.PRIVATE);

        assertTrue(rights.reserve(new AssetReservation(UUID.randomUUID(), containerId, iron,
                2, firstOwner, "production:first", "first", 0L, 100L), 2, 0L));
        assertTrue(rights.reserve(new AssetReservation(UUID.randomUUID(), containerId, iron,
                2, secondOwner, "production:second", "second", 0L, 100L), 2, 0L));
        assertEquals(4, rights.reservedQuantity(containerId, iron, null, 10L));
    }
}
