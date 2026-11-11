package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Component protections only; none of these assertions proves NPC scheduling or a full restart. */
class SupplyRecoveryRulesTest {
    private static final ItemFingerprint STRING=new ItemFingerprint("minecraft:string",0);
    private static NeedRequest create(NeedRequestData data,String purpose,long deadline){
        return data.openOrUpdate(new NeedRequest.Key(UUID.randomUUID(),purpose,STRING,UUID.randomUUID(),0,deadline),2,32,0);
    }
    @Test void futureGrantUsesAbsoluteGameTimeAndItsBoundarySurvivesNbt(){
        var grant=new RightGrant(UUID.randomUUID(),UUID.randomUUID(),SubjectRef.character(UUID.randomUUID()),
                Set.of(AssetRight.TRANSFER),"initial declared permission",19800L,24000L,true);
        assertFalse(grant.activeAt(19799));assertTrue(grant.activeAt(19800));assertTrue(grant.activeAt(24000));assertFalse(grant.activeAt(24001));
        var restored=RightGrant.load(grant.save());assertEquals(grant,restored);assertFalse(restored.activeAt(19799));assertTrue(restored.activeAt(19800));
    }
    @Test void allThirtyTwoRealFailureTransitionsRetainBusinessIdentityAndDoNotResetOnReopenOrNbt(){
        var data=new NeedRequestData();var need=create(data,"CHARACTER:production:original-string-order",72000);
        for(int i=0;i<32;i++)data.failedAttempt(need.id(),100L+600L*i,"component: no lawful source");
        var exhausted=data.request(need.id());assertEquals(32,exhausted.attempts());assertEquals(0,exhausted.delivered());
        assertEquals(exhausted,data.openOrUpdate(need.key(),2,32,19800));
        var restored=NeedRequestData.load(data.save(new CompoundTag(),null),null);
        assertEquals(exhausted,restored.request(need.id()));assertEquals(1,restored.requests().size());
        assertEquals(0,restored.request(need.id()).reserved());assertEquals(0,restored.request(need.id()).inTransit());
    }
    @Test void cancellingOneParentDoesNotDeleteSharedChildOrTheOtherParentsDependency(){
        var data=new NeedRequestData();var first=create(data,"CHARACTER:root-one",1000);var second=create(data,"CHARACTER:root-two",2000);
        var child=create(data,"CHARACTER:local-workshop:original:minecraft:string",1000);
        assertTrue(data.dependOn(first.id(),child.id(),1));assertTrue(data.dependOn(second.id(),child.id(),1));
        data.transition(first.id(),NeedRequestStatus.CANCELLED,2,"actual component cancellation before effects");
        assertEquals(NeedRequestStatus.OPEN,data.request(child.id()).status());assertEquals(Set.of(child.id()),data.dependencies(second.id()));
        assertFalse(LocalWorkshopNeeds.expireUncommitted(data.request(child.id()),true,false,1001));
        var loaded=NeedRequestData.load(data.save(new CompoundTag(),null),null);
        assertEquals(NeedRequestStatus.CANCELLED,loaded.request(first.id()).status());assertEquals(Set.of(child.id()),loaded.dependencies(second.id()));
        assertEquals(3,loaded.requests().size());
    }
    @Test void heldOrCarriedOriginalResponsibilityCannotBeDiscardedAtDeadline(){
        var data=new NeedRequestData();var child=create(data,"CHARACTER:local-supplier:original:string",100);
        data.acknowledge(UUID.randomUUID(),child.id(),NeedRequestData.Action.RESERVE,2,1);
        assertFalse(LocalSupplierRules.expireUncommitted(data.request(child.id()),false,false,101));
        data.acknowledge(UUID.randomUUID(),child.id(),NeedRequestData.Action.DISPATCH,2,102);
        assertFalse(LocalSupplierRules.expireUncommitted(data.request(child.id()),false,false,103));
        assertEquals(2,data.request(child.id()).inTransit());assertTrue(LocalSupplierRules.blocksNextBatch(data.request(child.id()),true,0));
        assertThrows(IllegalArgumentException.class,()->data.transition(child.id(),NeedRequestStatus.CANCELLED,104,"must not erase custody"));
    }
}
