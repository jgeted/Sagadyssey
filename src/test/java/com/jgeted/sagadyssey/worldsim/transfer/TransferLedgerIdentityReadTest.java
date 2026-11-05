package com.jgeted.sagadyssey.worldsim.transfer;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.nbt.*;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TransferLedgerIdentityReadTest {
    @Test void absentIdentityQueryDoesNotCreateOrDirtyAnything(){var d=new TransferLedgerData();d.setDirty(false);var before=d.save(new CompoundTag(),null);assertNull(d.requestIdentity(UUID.randomUUID()));assertFalse(d.isDirty());assertEquals(before,d.save(new CompoundTag(),null));}
    @Test void callerCanOnlyMutateACopy(){var d=new TransferLedgerData();var id=UUID.randomUUID();var owner=SubjectRef.character(UUID.randomUUID());var request=new TransferRequest(id,owner,TransferIntent.GIFT,List.of(new TransferLeg(UUID.randomUUID(),UUID.randomUUID(),ItemFingerprint.of(new ItemStack(Items.CARROT)),3)),List.of(),"readonly",10);var receipt=new TransferReceipt(id,TransferReceipt.Status.SUCCESS,true,"",Map.of(),10);d.record(request,receipt,new TransferAudit(id,owner,TransferIntent.GIFT,receipt.status(),true,"readonly","",1,10));d.setDirty(false);var before=d.save(new CompoundTag(),null);var copy=d.requestIdentity(id);copy.remove("Actor");copy.getList("Legs",Tag.TAG_COMPOUND).getCompound(0).putInt("Quantity",900);assertFalse(d.isDirty());assertEquals(request.identity(),d.requestIdentity(id));assertEquals(before,d.save(new CompoundTag(),null));}
    @Test void existingFormatRoundtripKeepsIdentityAndLegacyAbsence(){var d=new TransferLedgerData();var id=UUID.randomUUID();var owner=SubjectRef.character(UUID.randomUUID());var request=new TransferRequest(id,owner,TransferIntent.GIFT,List.of(new TransferLeg(UUID.randomUUID(),UUID.randomUUID(),ItemFingerprint.of(new ItemStack(Items.CARROT)),3)),List.of(),"readonly",10);var receipt=new TransferReceipt(id,TransferReceipt.Status.SUCCESS,true,"",Map.of(),10);d.record(request,receipt,new TransferAudit(id,owner,TransferIntent.GIFT,receipt.status(),true,"readonly","",1,10));var tag=d.save(new CompoundTag(),null);var restored=TransferLedgerData.load(tag,null);assertEquals(request.identity(),restored.requestIdentity(id));tag.getList("Receipts",Tag.TAG_COMPOUND).getCompound(0).remove("RequestIdentity");var legacy=TransferLedgerData.load(tag,null);assertNull(legacy.requestIdentity(id));assertEquals(receipt,legacy.receipt(id));}
}
