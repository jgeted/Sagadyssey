package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.spi.WarCommandPostPorts.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarCommandPostTest {
    private static final WarKey DIM=WarKey.parse("minecraft:overworld");
    static WarCommandPost post(){return new WarCommandPost(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),new WarIntent.Position(DIM,0,64,0),WarKey.parse("minecraft:red_banner"),1);}
    static class Memory implements Journal {
        final Map<UUID,WarCommandPost> records=new HashMap<>();boolean pending,failed,bad;int writes;
        public Collection<WarCommandPost> recover(){return List.copyOf(records.values());}
        public Reply persist(WarCommandPost p){writes++;if(failed)return new Reply(Status.UNKNOWN,Optional.empty(),"IO");if(pending)return new Reply(Status.PENDING,Optional.empty(),"");if(bad)return new Reply(Status.APPLIED,Optional.empty(),"");records.put(p.unit(),p);return new Reply(Status.APPLIED,Optional.of(p),"");}
        public void close(){}
    }
    @Test void aPendingFlagCannotBeSignaledAndItsReplacementCannotUseTheOldFlag(){
        var journal=new Memory();var service=new WarCommandPostService(journal);var p=post();
        journal.pending=true;assertEquals(Status.PENDING,service.bind(p).status());assertTrue(service.view(p.unit()).durable().isEmpty());
        journal.pending=false;service.tick();assertEquals(p,service.view(p.unit()).durable().orElseThrow());
        var next=new WarCommandPost(p.unit(),p.army(),p.binder(),new WarIntent.Position(DIM,5,64,8),p.block(),2);
        journal.pending=true;assertEquals(Status.PENDING,service.bind(next).status());assertTrue(service.view(p.unit()).durable().isEmpty());
        assertEquals(Status.UNAVAILABLE,service.bind(p).status());journal.pending=false;service.tick();assertEquals(next,service.view(p.unit()).durable().orElseThrow());
    }
    @Test void failedOrIncorrectAcknowledgementsRequireReviewAndCannotPublishAFlag(){
        for(boolean bad:new boolean[]{false,true}){
            var journal=new Memory();journal.bad=bad;journal.failed=!bad;var service=new WarCommandPostService(journal);var p=post();
            assertEquals(Status.UNKNOWN,service.bind(p).status());assertTrue(service.view(p.unit()).durable().isEmpty());
            int writes=journal.writes;service.tick();service.bind(p);assertEquals(writes,journal.writes);
        }
    }
    @Test void updatesCannotChangeArmySkipRevisionsOrWrapCounters(){
        var p=post();assertFalse(WarCommandPost.follows(p,new WarCommandPost(p.unit(),UUID.randomUUID(),p.binder(),p.position(),p.block(),2)));
        assertFalse(WarCommandPost.follows(p,new WarCommandPost(p.unit(),p.army(),p.binder(),p.position(),p.block(),3)));
        assertTrue(WarCommandPost.follows(p,p));
        var journal=new Memory();var s=new WarCommandPostService(journal);assertEquals(Status.UNKNOWN,s.bind(new WarCommandPost(p.unit(),p.army(),p.binder(),p.position(),p.block(),2)).status());assertEquals(0,journal.writes);
    }
    @Test void postWriteQueueHasABoundedPerTickQuota(){
        var journal=new Memory();var s=new WarCommandPostService(journal);journal.pending=true;
        var posts=new ArrayList<WarCommandPost>();for(int i=0;i<9;i++){var p=post();posts.add(p);s.bind(p);}journal.pending=false;int before=journal.writes;s.tick();assertEquals(4,journal.writes-before);assertEquals(4,posts.stream().filter(p->s.view(p.unit()).status()==Status.APPLIED).count());
    }
    @Test void hornRequiresDirectFreshCurrentRoutingWhileMapRetainsDelayedCourierRouting(){
        var origin=new WarIntent.Position(DIM,0,64,0);
        assertTrue(WarCommandMediaPolicy.audible(WarTransmission.route(origin,new WarIntent.Position(DIM,32,64,0),60,100),100));
        assertFalse(WarCommandMediaPolicy.audible(WarTransmission.route(origin,new WarIntent.Position(DIM,33,64,0),100,100),100));
        assertFalse(WarCommandMediaPolicy.audible(WarTransmission.route(origin,origin,59,100),100));
        assertFalse(WarCommandMediaPolicy.audible(WarTransmission.route(origin,origin,100,100),101));
    }
}
