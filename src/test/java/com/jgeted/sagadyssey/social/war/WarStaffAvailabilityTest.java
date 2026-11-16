package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.application.WarStaffAvailability;
import com.jgeted.sagadyssey.social.war.application.WarStaffAvailability.Facts;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarStaffAvailabilityTest {
    static Facts facts(double health,int hunger,long at){return new Facts(true,true,false,false,false,health,20,hunger,at);}
    @Test void quarterHealthAndStarvationBlockExecutionWithoutAnOfficeMutation(){assertFalse(WarStaffAvailability.available(facts(5,300,10),10));assertTrue(WarStaffAvailability.available(facts(5.01,899,10),10));assertFalse(WarStaffAvailability.available(facts(20,900,10),10));}
    @Test void futureMissingOrNegativeNeedsEvidenceCannotAuthorizeAdvice(){assertFalse(WarStaffAvailability.available(facts(20,300,11),10));assertFalse(WarStaffAvailability.available(facts(20,300,-1),10));assertFalse(WarStaffAvailability.available(facts(20,-1,10),10));}
    @Test void sleepingDisabledAndTriageDangerEachBlockIndependently(){for(int i=0;i<4;i++)assertFalse(WarStaffAvailability.available(new Facts(true,i!=0,i==1,i==2,i==3,20,20,300,10),10));}
    @Test void invalidNativeNumbersAndDeadBodiesNeverBecomeAvailable(){assertFalse(WarStaffAvailability.available(facts(Double.NaN,300,10),10));assertFalse(WarStaffAvailability.available(new Facts(false,true,false,false,false,20,20,300,10),10));assertFalse(WarStaffAvailability.available(new Facts(true,true,false,false,false,20,0,300,10),10));}
    @Test void returningToValidFactsRestoresAvailabilityWithoutASecondAppointment(){assertTrue(WarStaffAvailability.available(facts(20,300,10),10));assertFalse(WarStaffAvailability.available(facts(4,300,10),10));assertTrue(WarStaffAvailability.available(facts(20,300,12),12));}
}
