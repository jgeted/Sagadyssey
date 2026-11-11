package com.jgeted.sagadyssey.core.structure;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class NaturalMemberPaymentTest {
    @Test void paidReceiptSurvivesReloadAndCannotBeReclaimedOnSameOrEarlierDay(){
        var d=new NaturalVillageLifeData();var person=UUID.randomUUID();var village=UUID.randomUUID();
        assertNotNull(d.beginPayment(person,village,5));d.finishPayment(person,village,"PAID");
        d=NaturalVillageLifeData.load(d.save(new CompoundTag(),null),null);
        assertNull(d.beginPayment(person,village,5));assertNull(d.beginPayment(person,village,4));assertNotNull(d.beginPayment(person,village,6));
    }
    @Test void unknownInterruptedTransferIsNotReissuedAfterReload(){
        var d=new NaturalVillageLifeData();var person=UUID.randomUUID();var village=UUID.randomUUID();var tx=d.beginPayment(person,village,1);
        d=NaturalVillageLifeData.load(d.save(new CompoundTag(),null),null);
        assertEquals(tx,d.payment(person,village).read("Transaction", UUIDUtil.CODEC).orElseThrow());assertNull(d.beginPayment(person,village,2));d.finishPayment(person,village,"REVIEW");assertNull(d.beginPayment(person,village,2));
    }
    @Test void confirmedFailureCanRetryWithFreshTransactionButSuccessCannot(){
        var d=new NaturalVillageLifeData();var person=UUID.randomUUID();var village=UUID.randomUUID();var old=d.beginPayment(person,village,1);d.finishPayment(person,village,"FAILED");
        var retry=d.beginPayment(person,village,1);assertNotNull(retry);assertNotEquals(old,retry);d.finishPayment(person,village,"PAID");assertNull(d.beginPayment(person,village,1));
    }

    @Test void acceptedWorkKeepsBindingAndTransactionAcrossReloadAndSubmission(){
        var d=new NaturalVillageLifeData();var person=UUID.randomUUID();var village=UUID.randomUUID();var home=UUID.randomUUID();
        var tx=d.acceptWork(person,village,2,"original-building",home);assertNotNull(tx);
        d=NaturalVillageLifeData.load(d.save(new CompoundTag(),null),null);
        assertEquals(tx,d.acceptWork(person,village,3,"original-building",home));
        assertNull(d.acceptWork(person,village,3,"another-building",home));
        assertNull(d.acceptWork(person,village,3,"original-building",UUID.randomUUID()));
        assertEquals(tx,d.beginPayment(person,village,3));assertFalse(d.cancelWork(person,village));
        d.finishPayment(person,village,"PAID");assertNull(d.acceptWork(person,village,3,"original-building",home));
    }
    @Test void cancelledUnsubmittedWorkDoesNotDiscardPendingOrPaidObligations(){
        var d=new NaturalVillageLifeData();var person=UUID.randomUUID();var village=UUID.randomUUID();var home=UUID.randomUUID();
        var old=d.acceptWork(person,village,0,"b",home);assertTrue(d.cancelWork(person,village));
        d=NaturalVillageLifeData.load(d.save(new CompoundTag(),null),null);
        assertEquals("CANCELLED",d.payment(person,village).getStringOr("State", ""));
        var next=d.acceptWork(person,village,0,"b",home);assertNotEquals(old,next);assertEquals(next,d.beginPayment(person,village,0));
        assertFalse(d.cancelWork(person,village));d.finishPayment(person,village,"REVIEW");
        d=NaturalVillageLifeData.load(d.save(new CompoundTag(),null),null);
        assertFalse(d.cancelWork(person,village));assertNull(d.acceptWork(person,village,100,"b",home));assertNull(d.beginPayment(person,village,100));
    }
}
