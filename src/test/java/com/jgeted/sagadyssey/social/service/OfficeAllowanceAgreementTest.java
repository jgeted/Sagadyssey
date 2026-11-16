package com.jgeted.sagadyssey.social.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OfficeAllowanceAgreementTest {
    final UUID principal=UUID.randomUUID(),agent=UUID.randomUUID(),office=UUID.randomUUID();
    ServiceRelationshipData data(){return new ServiceRelationshipData();}
    @Test void supplementaryPaymentNeverGrantsWorkMilitaryOrPrivateInventoryAuthority(){var d=data();var parent=d.ensureLegacyRecruitment(principal,agent,10);var a=d.recordAcceptedOfficeAllowance(office,principal,agent,parent.id(),parent.revision(),2,20,24020,20);assertEquals(Set.of(AuthorityScope.RECEIVE_SERVICE_ALLOWANCE),a.scopes());assertFalse(a.limits().dangerousOrdersAllowed());assertFalse(a.limits().privateInventoryAccess());assertFalse(ServiceRelationshipData.permits(a,principal,agent,Set.of(AuthorityScope.ASSIGN_ROLE),false,"",null,20));assertEquals(parent,d.relationship(parent.id()));}
    @Test void onlyTheExactCurrentParentCanSupportThePromise(){var d=data();var p=d.ensureLegacyRecruitment(principal,agent,10);assertThrows(IllegalArgumentException.class,()->d.recordAcceptedOfficeAllowance(office,UUID.randomUUID(),agent,p.id(),p.revision(),2,20,24020,20));assertThrows(IllegalArgumentException.class,()->d.recordAcceptedOfficeAllowance(office,principal,agent,p.id(),p.revision()+1,2,20,24020,20));assertNull(d.relationship(office));}
    @Test void cancellationStopsAccrualButRetainsTheOriginalPaymentObligation(){var d=data();var p=d.ensureLegacyRecruitment(principal,agent,10);var a=d.recordAcceptedOfficeAllowance(office,principal,agent,p.id(),p.revision(),2,20,24020,20);var stopped=d.stopOfficeAllowance(office,30);assertFalse(stopped.activeAt(31));assertEquals(30L,stopped.endsAt());assertEquals(a.obligations(),stopped.obligations());assertEquals(ObligationStatus.PENDING,stopped.obligations().getFirst().status());assertEquals(stopped,d.stopOfficeAllowance(office,100));assertTrue(d.relationship(p.id()).activeAt(31));}
    @Test void duplicatePromisesCannotChangeRateOrCreateAnotherPaymentTerm(){var d=data();var p=d.ensureLegacyRecruitment(principal,agent,10);var a=d.recordAcceptedOfficeAllowance(office,principal,agent,p.id(),p.revision(),2,20,24020,20);assertEquals(a,d.recordAcceptedOfficeAllowance(office,principal,agent,p.id(),p.revision(),2,20,24020,20));assertThrows(IllegalStateException.class,()->d.recordAcceptedOfficeAllowance(office,principal,agent,p.id(),p.revision(),3,20,24020,20));assertEquals(a,d.relationship(office));}
}
