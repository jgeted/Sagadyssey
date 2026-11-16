package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarMusterBook;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.*;
import com.jgeted.sagadyssey.social.war.spi.WarMusterPorts.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarMusterBookTest {
    static final UUID OWNER=UUID.randomUUID(),PERSON=UUID.randomUUID();
    static final WarKey PROFILE=new WarLoadout(WarLoadout.Mount.FOOT,WarLoadout.Weapon.SWORD,false,false).id();
    static Army army(){return new Army(WarMusterEntry.armyId(OWNER,"小队"),OWNER,"小队",10);}
    static Unit unit(){return new Unit(WarMusterEntry.unitId(army().id(),"第一队"),army().id(),"第一队",PROFILE,1,false,1);}
    static Offer offer(){var r=new WarMilitiaRequest(UUID.randomUUID(),OWNER,army().id(),unit().id(),PERSON,PROFILE,1,WarMilitiaRequest.Kind.MILITIA,WarMilitiaRequest.Role.COMBATANT,
            new WarMilitiaRequest.Wage(new WarKey("minecraft","emerald"),1,24000),10);return new Offer(r,UUID.randomUUID(),UUID.randomUUID(),0,"minecraft:overworld",null,24010,.5,.25,Stage.PROPOSED,1,10,"terms");}
    static final class Journal implements com.jgeted.sagadyssey.social.war.spi.WarMusterPorts.Journal {
        final Map<UUID,WarMusterEntry> saved=new LinkedHashMap<>();boolean waiting,fail,wrong;
        public Collection<WarMusterEntry> recover(){return List.copyOf(saved.values());}
        public Reply persist(WarMusterEntry value){if(fail)return new Reply(Status.UNKNOWN,Optional.empty(),"disk failure");if(waiting)return new Reply(Status.PENDING,Optional.empty(),"waiting");saved.put(value.id(),value);return new Reply(Status.APPLIED,Optional.of(wrong?army():value),"");}
        public void close(){}
    }
    static WarMusterBook book(Journal j){var b=new WarMusterBook(j);b.put(army(),10);b.put(unit(),10);b.put(unit().acknowledged(),10);return b;}
    @Test void pendingArmyCannotGrantCommandAuthorityToEitherAnOwnerOrAnotherLife(){
        var j=new Journal();j.waiting=true;var b=new WarMusterBook(j);assertEquals(Status.PENDING,b.put(army(),10).status());assertFalse(b.owns(OWNER,army().id()));j.waiting=false;b.poll(4);
        assertTrue(b.owns(OWNER,army().id()));assertFalse(b.owns(UUID.randomUUID(),army().id()));assertFalse(b.owns(OWNER,UUID.randomUUID()));
    }
    @Test void aPendingDecisionReservesThePersonAndChangingTicketCannotReroll(){
        var j=new Journal();var b=book(j);j.waiting=true;var o=offer();assertEquals(Status.PENDING,b.put(o,10).status());assertFalse(b.durable(o));
        assertEquals(Status.UNKNOWN,b.put(offer(),10).status());assertEquals(o,b.latest(PERSON).orElseThrow());
        var reroll=new Offer(o.request(),o.body(),o.parent(),0,o.dimension(),null,o.endsAt(),.99,.01,Stage.PROPOSED,1,10,"changed");assertEquals(Status.UNKNOWN,b.put(reroll,10).status());
        j.waiting=false;b.poll(4);assertTrue(b.durable(o));assertEquals(Status.APPLIED,b.put(o,11).status());
    }
    @Test void aDeclinedOrEndedDecisionCannotBeReactivatedAndCooldownIsShared(){
        var j=new Journal();var b=book(j);var base=offer();var refused=new Offer(base.request(),base.body(),base.parent(),0,base.dimension(),null,base.endsAt(),.5,.99,Stage.PROPOSED,1,10,"");b.put(refused,10);
        var declined=refused.advance(Stage.DECLINED,11,"no");b.put(declined,11);assertThrows(IllegalArgumentException.class,()->declined.advance(Stage.GRANTING,12,"retry"));
        assertEquals(Status.UNKNOWN,b.put(offer(),1210).status());assertEquals(Status.APPLIED,b.put(offer(),1211).status());
    }
    @Test void orphanUnrecordedOrForeignProfileReferencesNeverEnterTheBook(){
        var b=new WarMusterBook(new Journal());assertEquals(Status.UNKNOWN,b.put(unit(),10).status());b.put(army(),10);b.put(unit(),10);assertEquals(Status.UNKNOWN,b.put(offer(),10).status());
        b.put(unit().acknowledged(),10);var o=offer();var r=o.request();var wrong=new WarMilitiaRequest(r.id(),UUID.randomUUID(),r.armyId(),r.unitId(),r.characterId(),r.loadout(),1,r.kind(),r.role(),r.wage(),10);
        assertEquals(Status.UNKNOWN,b.put(new Offer(wrong,o.body(),o.parent(),0,o.dimension(),null,o.endsAt(),.5,.2,Stage.PROPOSED,1,10,""),10).status());
    }
    @Test void recoveryUsesReferencesRatherThanSerializationOrderAndRejectsMissingSources(){
        var j=new Journal();var o=offer();j.saved.put(o.id(),o);j.saved.put(unit().id(),unit().acknowledged());j.saved.put(army().id(),army());assertEquals(o,new WarMusterBook(j).latest(PERSON).orElseThrow());
        j.saved.remove(army().id());assertThrows(IllegalArgumentException.class,()->new WarMusterBook(j));
    }
    @Test void transitionsKeepTermsAndTimeAndNeverSkipTheGrantFence(){
        var o=offer();assertThrows(IllegalArgumentException.class,()->o.advance(Stage.ACTIVE,11,"skip"));var grant=o.advance(Stage.GRANTING,11,"yes");assertTrue(WarMusterEntry.follows(o,grant));
        assertFalse(WarMusterEntry.follows(grant,o));assertThrows(IllegalArgumentException.class,()->grant.advance(Stage.PREPARING,10,"past"));
        var closed=grant.advance(Stage.RELEASING,12,"stop").advance(Stage.ENDED,13,"closed");assertThrows(IllegalArgumentException.class,()->closed.advance(Stage.PROPOSED,14,"again"));
    }
    @Test void writeFailureNeverTurnsAPendingArmyIntoAuthority(){var j=new Journal();j.waiting=true;var b=new WarMusterBook(j);b.put(army(),10);j.waiting=false;j.fail=true;b.poll(4);assertFalse(b.owns(OWNER,army().id()));assertTrue(b.problem().contains("REVIEW"));}
    @Test void wrongDurableAcknowledgementDoesNotPublishTheOffer(){var j=new Journal();var b=book(j);var o=offer();j.wrong=true;assertEquals(Status.APPLIED,b.put(o,10).status());assertFalse(b.durable(o));}
    @Test void stableNamesAreCharacterScopedAndLimitsDoNotEvictExistingRecords(){
        var b=new WarMusterBook(new Journal());for(int i=0;i<4;i++){var a=new Army(WarMusterEntry.armyId(OWNER,"army"+i),OWNER,"army"+i,10);assertEquals(Status.APPLIED,b.put(a,10).status());}
        var fifth=new Army(WarMusterEntry.armyId(OWNER,"fifth"),OWNER,"fifth",10);assertEquals(Status.UNKNOWN,b.put(fifth,10).status());assertTrue(b.owns(OWNER,WarMusterEntry.armyId(OWNER,"army0")));
        assertNotEquals(WarMusterEntry.armyId(OWNER,"army0"),WarMusterEntry.armyId(UUID.randomUUID(),"army0"));
    }
    @Test void reviewHistoryCannotBeHiddenByANewerClosedOfferAfterRecovery(){
        var j=new Journal();var first=offer().advance(Stage.REVIEW,11,"unknown external result");var newer=offer().advance(Stage.RELEASING,12,"end").advance(Stage.ENDED,13,"done");
        j.saved.put(army().id(),army());j.saved.put(unit().id(),unit().acknowledged());j.saved.put(first.id(),first);j.saved.put(newer.id(),newer);assertThrows(IllegalArgumentException.class,()->new WarMusterBook(j));
    }
}
