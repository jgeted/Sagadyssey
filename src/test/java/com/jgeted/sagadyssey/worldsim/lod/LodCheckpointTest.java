package com.jgeted.sagadyssey.worldsim.lod;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LodCheckpointTest {
    private LodCheckpoint row(){return new LodCheckpoint(UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",0,UUID.randomUUID(),"a".repeat(64),2,Map.of(UUID.randomUUID(),4L),Map.of(UUID.randomUUID(),"b".repeat(64)),Set.of(UUID.randomUUID()),"c".repeat(64),200,400,true,"L1");}
    @Test void roundTripRetainsAllReceiptBoundaries(){var r=row();assertEquals(r,LodCheckpoint.load(r.save()));}
    @Test void collectionsAreImmutable(){var r=row();assertThrows(UnsupportedOperationException.class,()->r.assetRevisions().clear());assertThrows(UnsupportedOperationException.class,()->r.reservations().clear());}
    @Test void missingCollectionsCannotBecomeEmptyReservations(){var t=row().save();t.remove("Reservations");assertThrows(IllegalArgumentException.class,()->LodCheckpoint.load(t));}
    @Test void badCollectionTypeCannotBecomeEmptyAssets(){var t=row().save();var l=new ListTag();l.add(StringTag.valueOf("not a revision"));t.put("Assets",l);assertThrows(IllegalArgumentException.class,()->LodCheckpoint.load(t));}
    @Test void malformedIdentityAndDigestFailClosed(){var t=row().save();t.putString("InventoryDigest","x".repeat(64));assertThrows(IllegalArgumentException.class,()->LodCheckpoint.load(t));var b=row().save();b.putString("Plan","not-uuid");assertThrows(IllegalArgumentException.class,()->LodCheckpoint.load(b));}
    @Test void duplicateReservationCannotBeSilentlyDeduplicated(){var t=row().save();var l=MinecraftApi.listOfType(t, "Reservations",Tag.TAG_COMPOUND);l.add(l.getFirst().copy());assertThrows(IllegalArgumentException.class,()->LodCheckpoint.load(t));}
    @Test void monotonicSummaryAndNoCopiedInventories(){var r=row();assertThrows(IllegalArgumentException.class,()->r.summary(199));assertFalse(r.save().contains("Items"));assertEquals(r.inventoryDigest(),r.summary(600).inventoryDigest());}
    @Test void summaryQueueIsBoundedAndReschedulesWithoutDuplication(){var data=new NpcLodData();var r=row();data.put(r);data.put(r);assertEquals(1,data.backlog(600));assertTrue(data.due(599,8).isEmpty());assertEquals(List.of(r.body()),data.due(600,8));data.put(r.summary(600));assertTrue(data.due(799,8).isEmpty());assertEquals(List.of(r.body()),data.due(800,8));}
    @Test void savedDataPreservesQueueAndRejectsUnsupportedSchema(){var d=new NpcLodData();var r=row();d.put(r);var tag=d.save(new CompoundTag(),null);assertEquals(r,NpcLodData.load(tag,null).find(r.body()));tag.putInt("Schema",99);assertThrows(IllegalStateException.class,()->NpcLodData.load(tag,null));}
    @Test void identityAndTimeCannotBeRebound(){var d=new NpcLodData();var r=row();d.put(r);var t=r.save();t.store("Person", UUIDUtil.CODEC,UUID.randomUUID());assertThrows(IllegalStateException.class,()->d.put(LodCheckpoint.load(t)));assertThrows(IllegalStateException.class,()->d.put(r.summary(399)));}
    @Test void duplicatePersonCannotAcquireReplacementBody(){var d=new NpcLodData();var r=row();d.put(r);var t=r.save();t.store("Body", UUIDUtil.CODEC,UUID.randomUUID());assertThrows(IllegalStateException.class,()->d.put(LodCheckpoint.load(t)));}
    @Test void canonicalRevisionIncludesTypesComponentsAndIgnoresMapInsertionOrder(){var a=new CompoundTag();a.putInt("a",1);a.putString("b","name");var b=new CompoundTag();b.putString("b","name");b.putInt("a",1);assertEquals(LodFingerprints.of(a),LodFingerprints.of(b));b.putString("a","1");assertNotEquals(LodFingerprints.of(a),LodFingerprints.of(b));}
    @Test void offlineRestingNeedsMatchLoadedRestDirectionWithoutFreeFood(){assertEquals(0,NpcLodRuntime.restingHunger(0,199));assertEquals(2,NpcLodRuntime.restingHunger(0,200));assertEquals(720,NpcLodRuntime.restingHunger(0,72000));assertEquals(1000,NpcLodRuntime.restingHunger(0,Long.MAX_VALUE));assertThrows(IllegalArgumentException.class,()->NpcLodRuntime.restingHunger(200,199));}
}
