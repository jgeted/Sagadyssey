package com.jgeted.sagadyssey.social.war;
import com.jgeted.sagadyssey.social.war.application.WarDutyClock;
import com.jgeted.sagadyssey.social.war.application.WarDutyClock.Job;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

final class WarDutyClockTest {
    private static Job job(){return new Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());}
    @Test void requiresActualConsecutiveBodyExecutions(){
        var c=new WarDutyClock(40,2);var j=job();assertTrue(c.reserve(j));assertFalse(c.ready(j.task(),1000));
        for(long t=1000;t<1039;t++){c.observe(j,t);assertFalse(c.ready(j.task(),t));}
        assertEquals(40,c.observe(j,1039));assertTrue(c.ready(j.task(),1039));assertTrue(c.ready(j.task(),1040));assertFalse(c.ready(j.task(),1041));
    }
    @Test void duplicatesGapsRollbackAndUnobservedTimeCannotEarnWork(){
        var c=new WarDutyClock(3,1);var j=job();c.reserve(j);assertEquals(1,c.observe(j,1));assertEquals(1,c.observe(j,1));assertEquals(2,c.observe(j,2));
        assertEquals(1,c.observe(j,100));assertFalse(c.ready(j.task(),100));assertEquals(0,c.observe(j,99));assertEquals(1,c.observe(j,100));
        assertEquals(0,c.observe(j,-1));assertFalse(c.ready(j.task(),-1));
    }
    @Test void oneTaskPerPersonServiceAndOriginalBody(){
        var c=new WarDutyClock(2,5);var j=job();assertTrue(c.reserve(j));assertTrue(c.reserve(j));
        assertFalse(c.reserve(new Job(UUID.randomUUID(),j.service(),UUID.randomUUID(),UUID.randomUUID())));
        assertFalse(c.reserve(new Job(UUID.randomUUID(),UUID.randomUUID(),j.person(),UUID.randomUUID())));
        assertFalse(c.reserve(new Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),j.body())));
        assertFalse(c.reserve(new Job(j.task(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID())));
        assertEquals(j,c.service(j.service()).orElseThrow());assertTrue(c.personReserved(j.person()));
    }
    @Test void releaseAndRecoveryDiscardCreditWithoutCreatingJobs(){
        var c=new WarDutyClock(1,1);var j=job();c.reserve(j);c.observe(j,10);assertTrue(c.ready(j.task(),10));
        c.release(j.task());c.release(j.task());assertFalse(c.ready(j.task(),10));assertFalse(c.personReserved(j.person()));assertTrue(c.service(j.service()).isEmpty());
        assertTrue(c.reserve(j));assertFalse(c.ready(j.task(),10));c.observe(j,11);c.clear();assertFalse(c.ready(j.task(),11));assertEquals(0,c.observe(j,12));
    }
    @Test void boundedRegistryAndExactTerms(){
        assertThrows(IllegalArgumentException.class,()->new WarDutyClock(0,1));assertThrows(IllegalArgumentException.class,()->new WarDutyClock(1,0));
        var c=new WarDutyClock(2,1);var j=job();assertTrue(c.reserve(j));assertFalse(c.reserve(job()));
        assertEquals(0,c.observe(new Job(j.task(),j.service(),j.person(),UUID.randomUUID()),0));assertFalse(c.ready(j.task(),0));
    }
}
