package com.jgeted.sagadyssey.worldsim.lod;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FairTickBudgetTest {
    @Test void hundredBodiesWithStableEntityOrderAllReceiveService() {
        var q=new FairTickBudget(8,2_000_000,8192);var ids=new ArrayList<UUID>();var served=new HashMap<UUID,Integer>();
        for(int i=0;i<100;i++)ids.add(UUID.randomUUID());
        for(int tick=0;tick<100;tick++)for(var id:ids)if(q.request(id,tick)){q.charge(1);served.merge(id,1,Integer::sum);}
        assertEquals(100,served.size());assertTrue(served.values().stream().allMatch(n->n>=5));
        assertEquals(8,q.snapshot().maxAdmissions());assertTrue(q.snapshot().maxWaitTicks()<30);
    }
    @Test void timeBudgetDefersEvenWhenCountRemains() {var q=new FairTickBudget(8,10,100);assertTrue(q.request(UUID.randomUUID(),0));q.charge(10);assertFalse(q.request(UUID.randomUUID(),0));assertEquals(1,q.snapshot().backlog());}
    @Test void sameBodyCannotReenterSameTick() {var q=new FairTickBudget(8,100,100);var id=UUID.randomUUID();assertTrue(q.request(id,0));assertFalse(q.request(id,0));assertTrue(q.request(id,1));}
    @Test void disappearedHeadCannotPermanentlyBlockTheQueue() {var q=new FairTickBudget(1,100,2);q.request(UUID.randomUUID(),0);q.request(UUID.randomUUID(),0);assertTrue(q.request(UUID.randomUUID(),201));}
    @Test void explicitForgetImmediatelyUnblocksNextBody() {var q=new FairTickBudget(1,100,3);var a=UUID.randomUUID();var b=UUID.randomUUID();q.request(UUID.randomUUID(),0);q.request(a,0);q.request(b,0);q.forget(a);assertTrue(q.request(b,1));}
    @Test void capacityIsHardAndTimeIsMonotonic() {var q=new FairTickBudget(1,1,1);q.request(UUID.randomUUID(),1);q.charge(1);q.request(UUID.randomUUID(),1);q.request(UUID.randomUUID(),1);assertEquals(1,q.snapshot().backlog());assertThrows(IllegalArgumentException.class,()->q.request(UUID.randomUUID(),0));}
}
