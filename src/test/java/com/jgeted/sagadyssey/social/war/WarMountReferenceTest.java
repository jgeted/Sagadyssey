package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarMountReference;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarMountReferenceCodec;
import com.jgeted.sagadyssey.social.war.spi.WarMobilityPorts.Pose;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarMountReferenceTest {
    private static final WarKey DIM=WarKey.parse("minecraft:overworld");
    private static WarMountReference ref(){return new WarMountReference(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),DIM);}
    @Test void onlyAnEmptyPairOrIdenticalPairIsInitiallyUsable(){
        var r=ref();assertTrue(WarMountReference.mayPrepare(Optional.empty(),Optional.empty(),r,id->false));
        assertTrue(WarMountReference.mayPrepare(Optional.of(r),Optional.of(r),r,id->false));
        assertFalse(WarMountReference.mayPrepare(Optional.of(r),Optional.empty(),r,id->true));
        assertFalse(WarMountReference.mayPrepare(Optional.empty(),Optional.of(r),r,id->true));
    }
    @Test void anActiveUseCannotBeStolenAndOnlyExplicitlyReleasedOldUsesCanBeReplaced(){
        var old=ref();var next=ref();
        assertFalse(WarMountReference.mayPrepare(Optional.empty(),Optional.of(old),next,id->false));
        assertFalse(WarMountReference.mayPrepare(Optional.of(old),Optional.empty(),next,id->false));
        assertTrue(WarMountReference.mayPrepare(Optional.empty(),Optional.of(old),next,id->id.equals(old.contract())));
        assertTrue(WarMountReference.mayPrepare(Optional.of(old),Optional.of(old),next,id->id.equals(old.contract())));
        var other=ref();assertFalse(WarMountReference.mayPrepare(Optional.of(old),Optional.of(other),next,id->id.equals(old.contract())));
    }
    @Test void aCurrentContractCannotQuietlyChangeItsHorseOwnerOrOriginalBody(){
        var r=ref();var changed=new WarMountReference(r.contract(),r.character(),r.body(),UUID.randomUUID(),r.owner(),r.principal(),r.dimension());
        assertFalse(WarMountReference.mayPrepare(Optional.of(r),Optional.empty(),changed,id->true));
        assertThrows(IllegalArgumentException.class,()->new WarMountReference(r.contract(),r.character(),r.body(),r.body(),r.owner(),r.principal(),r.dimension()));
    }
    @Test void bothEntityReferenceFormatsRoundTripWithoutMutatingUnknownOrMalformedEvidence(){
        var codec=new WarMountReferenceCodec();var r=ref();var owner=new CompoundTag();
        for(var key:List.of(WarMountReferenceCodec.RIDER_KEY,WarMountReferenceCodec.MOUNT_KEY)){
            assertTrue(codec.read(owner,key).isEmpty());codec.store(owner,key,r);assertEquals(r,codec.read(owner,key).orElseThrow());
            var saved=owner.getCompoundOrEmpty(key);saved.putString("Future","keep");var before=owner.copy();
            assertThrows(IllegalArgumentException.class,()->codec.read(owner,key));assertEquals(before,owner);
            owner.remove(key);
        }
        owner.putString(WarMountReferenceCodec.RIDER_KEY,"bad");assertThrows(IllegalArgumentException.class,()->codec.read(owner,WarMountReferenceCodec.RIDER_KEY));
    }
    @Test void groundPoseUsesNegativeFloorCoordinatesAndRejectsCrossDimensionArrival(){
        var pose=new Pose(DIM,-.2,5,-1.2);assertEquals(new WarIntent.Position(DIM,-1,5,-2),pose.block());
        assertEquals(.18,pose.distanceSquared(pose.block()),1e-9);
        assertEquals(Double.POSITIVE_INFINITY,pose.distanceSquared(new WarIntent.Position(WarKey.parse("minecraft:the_nether"),-1,5,-2)));
        assertThrows(IllegalArgumentException.class,()->new Pose(DIM,Double.NaN,0,0));
    }
}
