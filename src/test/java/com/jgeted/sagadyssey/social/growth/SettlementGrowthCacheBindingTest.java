package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.nbt.*;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementGrowthCacheBindingTest {
    static final class Fixture {
        final UUID txn=UUID.randomUUID(),source=UUID.randomUUID(),dest=UUID.randomUUID();
        final UUID actual=ContainerIds.named("growth-transfer-attempt:"+txn+":0");
        final SubjectRef actor=SubjectRef.household(UUID.randomUUID()),receiver=SubjectRef.character(UUID.randomUUID());
        final SimpleContainer a=new SimpleContainer(1),b=new SimpleContainer(1);
        final RightsStateData rights=new RightsStateData();final TransferLedgerData ledger=new TransferLedgerData();final CompoundTag attempt=new CompoundTag();
        final List<TransferLeg> legs=List.of(new TransferLeg(source,dest,ItemFingerprint.of(new ItemStack(Items.CARROT)),3));
        Fixture(){a.setItem(0,new ItemStack(Items.CARROT,30));rights.ensureContext(source,"s",actor,actor,AccessPolicy.PRIVATE);rights.ensureContext(dest,"d",receiver,receiver,AccessPolicy.PRIVATE);
            var req=new TransferRequest(actual,actor,TransferIntent.GIFT,legs,List.of(new ExpectedRevision(source,0),new ExpectedRevision(dest,0)),"v5合法建设或饲料领取",10);
            assertTrue(new TransferService(rights,ledger).submit(req,Map.of(source,new TransferEndpoint(source,a),dest,new TransferEndpoint(dest,b)),10).successful());
            attempt.putInt("Attempt",1);attempt.store("Id", UUIDUtil.CODEC,actual);attempt.putBoolean("Success",true);
        }
        boolean matches(UUID tx,SubjectRef who,TransferIntent intent,List<TransferLeg> requested,TransferLedgerData l){return SettlementGrowthData.cachedRequestMatches(attempt,tx,who,intent,requested,l);}
        boolean same(){return matches(txn,actor,TransferIntent.GIFT,legs,ledger);}
        TransferLedgerData changed(java.util.function.Consumer<CompoundTag> edit){var t=ledger.save(new CompoundTag(),null);edit.accept(t);return TransferLedgerData.FACTORY.deserializer().apply(t,null);}
    }
    @Test void originalSuccessIsConfirmedWithoutNewServiceSubmission(){var f=new Fixture();var before=f.ledger.save(new CompoundTag(),null);assertTrue(f.same());assertEquals(before,f.ledger.save(new CompoundTag(),null));assertEquals(27,f.a.getItem(0).getCount());assertEquals(3,f.b.getItem(0).getCount());assertEquals(1,f.attempt.getIntOr("Attempt", 0));}
    @Test void ownCommitAlreadyChangedCurrentRevisions(){var f=new Fixture();assertEquals(1,f.rights.context(f.source).revision());assertEquals(1,f.rights.context(f.dest).revision());assertTrue(f.same());assertEquals(0,MinecraftApi.listOfType(f.ledger.requestIdentity(f.actual), "ExpectedRevisions",Tag.TAG_COMPOUND).getCompoundOrEmpty(0).getLongOr("Revision", 0L));}
    @Test void quantityChangeIsRejected(){var f=new Fixture();assertFalse(f.matches(f.txn,f.actor,TransferIntent.GIFT,List.of(new TransferLeg(f.source,f.dest,ItemFingerprint.of(new ItemStack(Items.CARROT)),4)),f.ledger));}
    @Test void destinationChangeIsRejected(){var f=new Fixture();assertFalse(f.matches(f.txn,f.actor,TransferIntent.GIFT,List.of(new TransferLeg(f.source,UUID.randomUUID(),ItemFingerprint.of(new ItemStack(Items.CARROT)),3)),f.ledger));}
    @Test void sourceChangeIsRejected(){var f=new Fixture();assertFalse(f.matches(f.txn,f.actor,TransferIntent.GIFT,List.of(new TransferLeg(UUID.randomUUID(),f.dest,ItemFingerprint.of(new ItemStack(Items.CARROT)),3)),f.ledger));}
    @Test void itemChangeIsRejected(){var f=new Fixture();assertFalse(f.matches(f.txn,f.actor,TransferIntent.GIFT,List.of(new TransferLeg(f.source,f.dest,ItemFingerprint.of(new ItemStack(Items.POTATO)),3)),f.ledger));}
    @Test void actorChangeIsRejected(){var f=new Fixture();assertFalse(f.matches(f.txn,f.receiver,TransferIntent.GIFT,f.legs,f.ledger));}
    @Test void intentChangeIsRejected(){var f=new Fixture();assertFalse(f.matches(f.txn,f.actor,TransferIntent.DELIVERY,f.legs,f.ledger));}
    @Test void cachedActualCannotBeTransplantedToAnotherChild(){var f=new Fixture();assertFalse(f.matches(UUID.randomUUID(),f.actor,TransferIntent.GIFT,f.legs,f.ledger));}
    @Test void attemptOrdinalCannotBeChanged(){var f=new Fixture();f.attempt.putInt("Attempt",2);assertFalse(f.same());}
    @Test void missingReceiptIsNotSuccess(){var f=new Fixture();assertFalse(f.matches(f.txn,f.actor,TransferIntent.GIFT,f.legs,new TransferLedgerData()));}
    @Test void unavailableLedgerIsInsufficientWithoutCreatingIt(){var f=new Fixture();assertFalse(f.matches(f.txn,f.actor,TransferIntent.GIFT,f.legs,null));}
    @Test void missingIdentityIsNotGuessedFromLegacySuccess(){var f=new Fixture();var old=f.changed(t->MinecraftApi.listOfType(t, "Receipts",Tag.TAG_COMPOUND).getCompoundOrEmpty(0).remove("RequestIdentity"));assertNotNull(old.receipt(f.actual));assertFalse(f.matches(f.txn,f.actor,TransferIntent.GIFT,f.legs,old));assertNull(old.requestIdentity(f.actual));}
    @Test void unlawfulReceiptCannotConfirmLegalSuccess(){var f=new Fixture();var old=f.changed(t->MinecraftApi.listOfType(t, "Receipts",Tag.TAG_COMPOUND).getCompoundOrEmpty(0).putBoolean("Lawful",false));assertFalse(f.matches(f.txn,f.actor,TransferIntent.GIFT,f.legs,old));}
    @Test void authorityAndSourceAuthoritiesAreNotIgnored(){var f=new Fixture();var old=f.changed(t->MinecraftApi.listOfType(t, "Receipts",Tag.TAG_COMPOUND).getCompoundOrEmpty(0).getCompoundOrEmpty("RequestIdentity").putString("Authority","different"));assertFalse(f.matches(f.txn,f.actor,TransferIntent.GIFT,f.legs,old));}
}
