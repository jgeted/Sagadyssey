package com.jgeted.sagadyssey.worldsim.lod;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class LodMigrationDataTest {
    private CompoundTag intent(){var t=new CompoundTag();t.putString("State","INTENT");t.putString("Before","a".repeat(64));t.putLong("Time",100);var ids=new ListTag();var r=new CompoundTag();r.putUUID("Body",UUID.randomUUID());r.putUUID("Person",UUID.randomUUID());r.putString("Mode","LEGACY");ids.add(r);t.put("Targets",ids);return t;}
    @Test void durableTransitionRoundTripsWithoutMutatingCaller(){var d=new LodMigrationData();var id=UUID.randomUUID();var t=intent();d.put(id,t);t.putString("After","b".repeat(64));t.putString("State","APPLIED");d.put(id,t);assertEquals("APPLIED",LodMigrationData.load(d.save(new CompoundTag(),null),null).find(id).getString("State"));t.putString("State","ROLLED_BACK");d.put(id,t);assertEquals("ROLLED_BACK",d.find(id).getString("State"));assertFalse(t.contains("Id"));}
    @Test void cannotRewriteIdentityOrOriginalRevision(){var d=new LodMigrationData();var id=UUID.randomUUID();var t=intent();d.put(id,t);t.putString("Before","b".repeat(64));assertThrows(IllegalStateException.class,()->d.put(id,t));var replacement=intent();assertThrows(IllegalStateException.class,()->d.put(id,replacement));}
    @Test void cannotSkipIntentOrUndoAppliedReceipt(){var d=new LodMigrationData();var id=UUID.randomUUID();var t=intent();var original=t.copy();t.putString("After","b".repeat(64));t.putString("State","APPLIED");assertThrows(IllegalStateException.class,()->d.put(id,t));d.put(id,original);d.put(id,t);assertThrows(IllegalStateException.class,()->d.put(id,original));}
    @Test void corruptAndDuplicateJournalFailClosed(){var d=new LodMigrationData();d.put(UUID.randomUUID(),intent());var t=d.save(new CompoundTag(),null);var list=t.getList("Migrations",Tag.TAG_COMPOUND);list.add(list.getFirst().copy());assertThrows(IllegalArgumentException.class,()->LodMigrationData.load(t,null));t.putInt("Schema",2);assertThrows(IllegalArgumentException.class,()->LodMigrationData.load(t,null));}
    @Test void rejectMalformedCountReport(){var d=new LodMigrationData();var t=intent();t.put("BeforeCounts",new CompoundTag());assertThrows(IllegalArgumentException.class,()->d.put(UUID.randomUUID(),t));}
}
