package com.jgeted.sagadyssey.social.industry.common;

import com.jgeted.sagadyssey.worldsim.rights.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndustryToolSwapTest {
    private final UUID bag=UUID.randomUUID();
    private final SubjectRef actor=SubjectRef.character(UUID.randomUUID());
    private final ItemFingerprint axe=new ItemFingerprint("minecraft:iron_axe",917);
    private AssetClaim claim(SubjectRef owner,ItemFingerprint fingerprint,int quantity,Set<String> obligations){
        return new AssetClaim(UUID.randomUUID(),bag,36,fingerprint,quantity,owner,actor,
                obligations,Set.of(),"original fieldwork receipt",7);
    }
    @Test void defaultTitleAndOneMatchingOwnedClaimPermitPhysicalSlotMove(){
        assertTrue(IndustryToolTask.slotClaimsMatch(List.of(),actor,axe,1));
        assertTrue(IndustryToolTask.slotClaimsMatch(List.of(claim(actor,axe,1,Set.of())),actor,axe,1));
    }
    @Test void staleFingerprintQuantityAndDuplicateClaimsNeverBecomeDefaultOwnership(){
        var owned=claim(actor,axe,1,Set.of());
        assertFalse(IndustryToolTask.slotClaimsMatch(List.of(owned),actor,new ItemFingerprint("minecraft:iron_axe",918),1));
        assertFalse(IndustryToolTask.slotClaimsMatch(List.of(owned),actor,axe,2));
        assertFalse(IndustryToolTask.slotClaimsMatch(List.of(claim(actor,axe,2,Set.of())),actor,axe,1));
        assertFalse(IndustryToolTask.slotClaimsMatch(List.of(owned,claim(actor,axe,1,Set.of())),actor,axe,1));
        assertFalse(IndustryToolTask.slotClaimsMatch(List.of(owned),actor,null,0));
        assertTrue(IndustryToolTask.slotClaimsMatch(List.of(),actor,null,0));
    }
    @Test void borrowedIssuedOrReservedToolRemainsWithItsOriginalClaim(){
        var foreign=claim(SubjectRef.character(UUID.randomUUID()),axe,1,Set.of());
        assertFalse(IndustryToolTask.slotClaimsMatch(List.of(foreign),actor,axe,1));
        assertFalse(IndustryToolTask.slotClaimsMatch(List.of(claim(actor,axe,1,Set.of("issued"))),actor,axe,1));
        var reserved=new AssetClaim(UUID.randomUUID(),bag,36,axe,1,actor,actor,Set.of(),Set.of("old-native-plan"),"original",7);
        assertFalse(IndustryToolTask.slotClaimsMatch(List.of(reserved),actor,axe,1));
        assertEquals(Set.of("old-native-plan"),reserved.reservations());
    }
    @Test void locationAndActualWearKeepOriginalIdentityOwnerAndProvenance(){
        var original=claim(actor,axe,1,Set.of());
        var moved=IndustryToolTask.relocate(original,4,axe,9);
        assertEquals(original.claimId(),moved.claimId());assertEquals(4,moved.slot());
        assertEquals(original.provenance(),moved.provenance());
        assertEquals(original.beneficialOwner(),moved.beneficialOwner());assertEquals(original.custodian(),moved.custodian());
        assertEquals(original.containerId(),moved.containerId());assertEquals(1,moved.quantity());
        var worn=IndustryToolTask.relocate(moved,4,new ItemFingerprint("minecraft:iron_axe",918),10);
        assertEquals(original.claimId(),worn.claimId());assertEquals(10,worn.revision());
        assertEquals(moved.encumbrances(),worn.encumbrances());assertEquals(moved.reservations(),worn.reservations());
        assertEquals(918,worn.fingerprint().componentsHash());assertEquals(917,original.fingerprint().componentsHash());
    }
    @Test void allClaimsAtSlotIncludesStaleFingerprintInsteadOfHidingIt(){
        var rights=new RightsStateData();rights.ensureContext(bag,"original bag",actor,actor,AccessPolicy.PRIVATE);
        var old=claim(actor,axe,1,Set.of());rights.putClaim(old);
        assertTrue(rights.claimsAt(bag,36,new ItemFingerprint("minecraft:iron_axe",918)).isEmpty());
        assertEquals(List.of(old),IndustryToolTask.claimsForSlot(rights,bag,36));
        assertFalse(IndustryToolTask.slotClaimsMatch(IndustryToolTask.claimsForSlot(rights,bag,36),actor,new ItemFingerprint("minecraft:iron_axe",918),1));
    }
}
