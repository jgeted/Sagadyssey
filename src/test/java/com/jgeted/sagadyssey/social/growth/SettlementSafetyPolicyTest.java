package com.jgeted.sagadyssey.social.growth;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SettlementSafetyPolicyTest {
    @Test void nightBoundaryDoesNotTurnAllDayIntoWithdrawal(){assertFalse(SettlementSafetyPolicy.night(12999));assertTrue(SettlementSafetyPolicy.night(13000));assertTrue(SettlementSafetyPolicy.night(46999));assertFalse(SettlementSafetyPolicy.night(23000));}
    @Test void woundedUntrainedOrUnarmedPeopleDoNotDefend(){assertFalse(SettlementSafetyPolicy.defend(false,true,20,20,false,true,0));assertFalse(SettlementSafetyPolicy.defend(true,false,20,20,false,true,0));assertFalse(SettlementSafetyPolicy.defend(true,true,10,20,false,true,0));assertFalse(SettlementSafetyPolicy.defend(true,true,20,20,true,true,0));}
    @Test void HealthyTrainedResidentStillNeedsActualThreatAndBound(){assertTrue(SettlementSafetyPolicy.defend(true,true,20,20,false,true,256));assertFalse(SettlementSafetyPolicy.defend(true,true,20,20,false,false,1));assertFalse(SettlementSafetyPolicy.defend(true,true,20,20,false,true,257));}
    @Test void ResumeRequiresActualQuietWindow(){assertFalse(SettlementSafetyPolicy.quiet(1199,1000));assertTrue(SettlementSafetyPolicy.quiet(1200,1000));}
}
