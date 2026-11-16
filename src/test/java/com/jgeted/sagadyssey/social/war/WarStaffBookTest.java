package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry.*;
import com.jgeted.sagadyssey.social.war.spi.WarStaffPorts.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarStaffBookTest {
    static final UUID ARMY=UUID.randomUUID(),OWNER=UUID.randomUUID(),UNIT=UUID.randomUUID(),PERSON=UUID.randomUUID();
    static Establishment establishment(int n){return new Establishment(ARMY,OWNER,1,10,Map.of(UNIT,n));}
    static Appointment appointment(UUID id,UUID person,Role role){return new Appointment(new Terms(id,ARMY,OWNER,person,UUID.randomUUID(),role,role==Role.CAPTAIN?Optional.of(UNIT):Optional.empty(),1,24,role==Role.CAPTAIN?24:0,1,10,24010,Mode.REGULAR,"explicit owner appointment","COMMONER","BLACKSMITH",0),Phase.PREPARING,1,10,"preparing");}
    static final class Journal implements com.jgeted.sagadyssey.social.war.spi.WarStaffPorts.Journal {
        final Map<UUID,WarStaffEntry> saved=new LinkedHashMap<>();boolean pending,wrong,fail;
        public Collection<WarStaffEntry> recover(){return List.copyOf(saved.values());}
        public Reply persist(WarStaffEntry v){if(fail)return new Reply(Status.UNKNOWN,Optional.empty(),"failed");if(pending)return new Reply(Status.PENDING,Optional.empty(),"pending");saved.put(v.id(),v);return new Reply(Status.APPLIED,Optional.of(wrong?establishment(2):v),"");}
        public void close(){}
    }
    static WarStaffBook book(Journal j){var b=new WarStaffBook(j);assertEquals(Status.APPLIED,b.put(establishment(24)).status());return b;}
    @Test void smallSquadsHaveOneOptionalCompatibleAssistantAndNoMandatoryStaff(){
        var p=new WarStaffPolicy(WarStaffPolicy.Rules.prototype());var small=establishment(2);
        assertTrue(p.evaluate(small,Role.TEMPORARY_DEPUTY,Optional.empty()).available());
        for(var r:List.of(Role.DEPUTY,Role.CAPTAIN,Role.STAFF_OFFICER,Role.STANDARD_BEARER,Role.QUARTERMASTER,Role.ENGINEERING_LEAD))assertFalse(p.evaluate(small,r,Optional.empty()).available());
        assertEquals("随行队",p.scale(small));assertFalse(p.evaluate(establishment(0),Role.TEMPORARY_DEPUTY,Optional.empty()).available());
    }
    @Test void thresholdAndScopePoliciesAreExplicitAndReplaceable(){
        var p=new WarStaffPolicy(WarStaffPolicy.Rules.prototype());assertFalse(p.evaluate(establishment(20),Role.DEPUTY,Optional.empty()).available());assertTrue(p.evaluate(establishment(21),Role.DEPUTY,Optional.empty()).available());
        var split=new Establishment(ARMY,OWNER,1,10,Map.of(UNIT,11,UUID.randomUUID(),10));assertFalse(p.evaluate(split,Role.CAPTAIN,Optional.of(UNIT)).available());
        assertTrue(p.evaluate(establishment(21),Role.CAPTAIN,Optional.of(UNIT)).available());assertFalse(p.evaluate(establishment(21),Role.DEPUTY,Optional.of(UNIT)).available());
        assertTrue(new WarStaffPolicy(new WarStaffPolicy.Rules(5,10,20,3)).evaluate(establishment(5),Role.DEPUTY,Optional.empty()).available());
    }
    @Test void dedicatedEstablishmentStillRequiresAnOriginalDedicatedService(){
        var p=new WarStaffPolicy(WarStaffPolicy.Rules.prototype());for(var r:List.of(Role.STAFF_OFFICER,Role.STANDARD_BEARER,Role.QUARTERMASTER,Role.ENGINEERING_LEAD)){var q=p.evaluate(establishment(300),r,Optional.empty());assertTrue(q.available());assertTrue(q.implemented());assertEquals("DEDICATED_ORIGINAL_STAFF_SERVICE_REQUIRED",q.reason());assertFalse(com.jgeted.sagadyssey.social.war.domain.WarOfficeQualification.compatible(r,com.jgeted.sagadyssey.social.war.api.WarMilitiaRequest.Role.COMBATANT));assertTrue(com.jgeted.sagadyssey.social.war.domain.WarOfficeQualification.compatible(r,com.jgeted.sagadyssey.social.war.api.WarMilitiaRequest.Role.STAFF));}
    }
    @Test void pendingAppointmentReservesBothTheSeatAndThePersonAcrossRoles(){
        var j=new Journal();var b=book(j);j.pending=true;var a=appointment(UUID.randomUUID(),PERSON,Role.TEMPORARY_DEPUTY);assertEquals(Status.PENDING,b.put(a).status());assertFalse(b.durable(a));
        assertEquals(Status.UNAVAILABLE,b.put(appointment(UUID.randomUUID(),UUID.randomUUID(),Role.DEPUTY)).status());
        assertEquals(Status.UNAVAILABLE,b.put(appointment(UUID.randomUUID(),PERSON,Role.CAPTAIN)).status());
        j.pending=false;b.poll(4);assertTrue(b.durable(a));assertEquals(a,b.appointment(a.id()).orElseThrow());
    }
    @Test void unresolvedRevocationKeepsSeatUntilItsFinalReceiptIsDurable(){
        var j=new Journal();var b=book(j);var a=appointment(UUID.randomUUID(),PERSON,Role.DEPUTY);b.put(a);var active=a.advance(Phase.ACTIVE,11,"shared office saved");b.put(active);var stopping=active.advance(Phase.REVOKING,12,"owner revoke");b.put(stopping);
        j.pending=true;var ended=stopping.advance(Phase.REVOKED,13,"shared revoke saved");b.put(ended);var replacement=appointment(UUID.randomUUID(),UUID.randomUUID(),Role.DEPUTY);
        assertEquals(Status.UNAVAILABLE,b.put(replacement).status());j.pending=false;b.poll(4);assertEquals(Status.APPLIED,b.put(replacement).status());assertTrue(b.appointment(ended.id()).isPresent());
    }
    @Test void militaryEvidenceDoesNotMutateClassProfessionOrIncomeOnPromotion(){
        var a=appointment(UUID.randomUUID(),PERSON,Role.CAPTAIN);var active=a.advance(Phase.ACTIVE,11,"shared office saved");assertEquals(a.terms(),active.terms());
        assertEquals("COMMONER",active.terms().socialClass());assertEquals("BLACKSMITH",active.terms().profession());assertEquals(0,active.terms().experience());assertFalse(WarStaffEntry.follows(active,a));
        assertThrows(IllegalArgumentException.class,()->active.advance(Phase.PREPARING,12,"grant again"));
    }
    @Test void explicitEstablishmentReviewPersistsAndNeverErasesAnExistingAppointment(){
        var j=new Journal();var b=book(j);var a=appointment(UUID.randomUUID(),PERSON,Role.CAPTAIN);b.put(a);b.put(a.advance(Phase.ACTIVE,11,"saved"));
        b.put(new Establishment(ARMY,OWNER,2,20,Map.of(UNIT,0)));var reopened=new WarStaffBook(j);assertEquals(0,reopened.establishment(ARMY).orElseThrow().strength());assertEquals(Phase.ACTIVE,reopened.appointment(a.id()).orElseThrow().phase());assertEquals(24,reopened.appointment(a.id()).orElseThrow().terms().combatants());assertEquals(1,reopened.appointment(a.id()).orElseThrow().terms().policyVersion());
    }
    @Test void malformedReferencesAndParallelAuthorityAreRejectedOnRecovery(){
        var j=new Journal();var a=appointment(UUID.randomUUID(),PERSON,Role.DEPUTY);j.saved.put(a.id(),a);assertThrows(IllegalArgumentException.class,()->new WarStaffBook(j));
        j.saved.put(ARMY,establishment(24));assertDoesNotThrow(()->new WarStaffBook(j));var conflict=appointment(UUID.randomUUID(),UUID.randomUUID(),Role.TEMPORARY_DEPUTY);j.saved.put(conflict.id(),conflict);assertThrows(IllegalArgumentException.class,()->new WarStaffBook(j));
    }
    @Test void aCorruptAcknowledgementCannotMakeAnAppointmentEffective(){var j=new Journal();var b=book(j);var a=appointment(UUID.randomUUID(),PERSON,Role.DEPUTY);j.wrong=true;b.put(a);assertFalse(b.durable(a));assertTrue(b.problem().contains("MISMATCH"));}
    @Test void uncertainDiskStateDoesNotBecomeASuccessfulPromotion(){var j=new Journal();var b=book(j);j.fail=true;assertEquals(Status.UNKNOWN,b.put(appointment(UUID.randomUUID(),PERSON,Role.DEPUTY)).status());assertFalse(b.problem().isEmpty());}
}
