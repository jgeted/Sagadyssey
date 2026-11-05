package com.jgeted.sagadyssey.social.production;

import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.AssetClaim;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionServiceOwnershipTest {
    @Test
    void unclaimedStackBelongsOnlyToContainerDefaultOwner() {
        RightsStateData rights = new RightsStateData();
        UUID containerId = UUID.randomUUID();
        SubjectRef defaultOwner = SubjectRef.character(UUID.randomUUID());
        SubjectRef otherOwner = SubjectRef.character(UUID.randomUUID());
        ItemFingerprint fingerprint = ItemFingerprint.of(new ItemStack(Items.IRON_PICKAXE));
        rights.ensureContext(containerId, "test:output", defaultOwner, defaultOwner,
                AccessPolicy.PRIVATE);

        assertTrue(ProductionService.isWhollyOwnedBy(rights, containerId, 0,
                fingerprint, defaultOwner, defaultOwner));
        assertFalse(ProductionService.isWhollyOwnedBy(rights, containerId, 0,
                fingerprint, defaultOwner, otherOwner));
    }

    @Test
    void explicitClaimPreventsAuthorizedNonOwnerFromMergingOutput() {
        RightsStateData rights = new RightsStateData();
        UUID containerId = UUID.randomUUID();
        SubjectRef defaultOwner = SubjectRef.character(UUID.randomUUID());
        SubjectRef claimedOwner = SubjectRef.character(UUID.randomUUID());
        SubjectRef producer = SubjectRef.character(UUID.randomUUID());
        ItemFingerprint fingerprint = ItemFingerprint.of(new ItemStack(Items.IRON_PICKAXE));
        rights.ensureContext(containerId, "test:output", defaultOwner, defaultOwner,
                AccessPolicy.PRIVATE);
        rights.putClaim(new AssetClaim(UUID.randomUUID(), containerId, 2, fingerprint, 3,
                claimedOwner, defaultOwner, Set.of(), Set.of(), "test", 0L));

        assertTrue(ProductionService.isWhollyOwnedBy(rights, containerId, 2,
                fingerprint, defaultOwner, claimedOwner));
        assertFalse(ProductionService.isWhollyOwnedBy(rights, containerId, 2,
                fingerprint, defaultOwner, producer));
        assertFalse(ProductionService.isWhollyOwnedBy(rights, containerId, 2,
                fingerprint, defaultOwner, defaultOwner));
    }
}
