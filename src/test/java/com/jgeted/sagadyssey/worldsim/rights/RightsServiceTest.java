package com.jgeted.sagadyssey.worldsim.rights;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RightsServiceTest {
    @Test
    void ownerMayTransferButCustodianOnlyPossessesByDefault() {
        RightsStateData state = new RightsStateData();
        UUID containerId = UUID.randomUUID();
        SubjectRef owner = SubjectRef.character(UUID.randomUUID());
        SubjectRef custodian = SubjectRef.character(UUID.randomUUID());
        ItemFingerprint fingerprint = ItemFingerprint.of(new ItemStack(Items.IRON_SWORD));
        state.ensureContext(containerId, "test:issued", custodian, owner, AccessPolicy.PRIVATE);
        RightsService service = new RightsService(state);

        assertEquals(RightsVerdict.Status.ALLOW,
                service.can(owner, AssetRight.TRANSFER, containerId, 0, fingerprint, 10L).status());
        assertEquals(RightsVerdict.Status.ALLOW,
                service.can(custodian, AssetRight.POSSESS, containerId, 0, fingerprint, 10L).status());
        assertEquals(RightsVerdict.Status.DENY,
                service.can(custodian, AssetRight.TRANSFER, containerId, 0, fingerprint, 10L).status());
    }

    @Test
    void temporaryGrantExpiresAtItsDeclaredBoundary() {
        RightsStateData state = new RightsStateData();
        UUID containerId = UUID.randomUUID();
        SubjectRef owner = SubjectRef.institution(UUID.randomUUID());
        SubjectRef worker = SubjectRef.character(UUID.randomUUID());
        ItemFingerprint fingerprint = ItemFingerprint.of(new ItemStack(Items.BREAD));
        state.ensureContext(containerId, "test:warehouse", owner, owner, AccessPolicy.INSTITUTION);
        state.grant(new RightGrant(UUID.randomUUID(), containerId, worker,
                Set.of(AssetRight.ACCESS, AssetRight.TRANSFER), "work order", 20L, 40L, true));
        RightsService service = new RightsService(state);

        assertEquals(RightsVerdict.Status.DENY,
                service.can(worker, AssetRight.TRANSFER, containerId, 0, fingerprint, 19L).status());
        assertEquals(RightsVerdict.Status.ALLOW,
                service.can(worker, AssetRight.TRANSFER, containerId, 0, fingerprint, 40L).status());
        assertEquals(RightsVerdict.Status.DENY,
                service.can(worker, AssetRight.TRANSFER, containerId, 0, fingerprint, 41L).status());
    }

    @Test
    void activeAssetReservationsCanBeRenewedWithoutChangingIdentityOrQuantity() {
        RightsStateData state = new RightsStateData();
        UUID containerId = UUID.randomUUID();
        SubjectRef owner = SubjectRef.character(UUID.randomUUID());
        ItemFingerprint fingerprint = ItemFingerprint.of(new ItemStack(Items.IRON_INGOT));
        AssetReservation original = new AssetReservation(UUID.randomUUID(), containerId,
                fingerprint, 3, owner, "production:test", "test", 10L, 30L);
        state.reserve(original, 3, 10L);

        assertEquals(1, state.renewReservations("production:test", 20L, 100L));
        AssetReservation renewed = state.reservationsForToken("production:test", 99L)
                .getFirst();
        assertEquals(original.id(), renewed.id());
        assertEquals(original.createdAt(), renewed.createdAt());
        assertEquals(3, renewed.quantity());
        assertEquals(100L, renewed.expiresAt());
    }
}
