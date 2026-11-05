package com.jgeted.sagadyssey.core.structure;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NaturalFarmRulesTest {
    @Test void provedPlantingOverlapIsNotSubtractedTwice(){
        assertEquals(2,NaturalFarmRules.purposeAvailable(14,12,2,0));
        assertEquals(1,NaturalFarmRules.purposeAvailable(14,12,2,1));
    }
    @Test void unknownOccupancyIsKeptSeparate(){
        assertEquals(0,NaturalFarmRules.purposeAvailable(14,12,0,2));
        assertEquals(0,NaturalFarmRules.purposeAvailable(4,12,0,0));
        assertEquals(1,NaturalFarmRules.purposeAvailable(14,12,13,0));
    }
    @Test void paymentsUseSpendableFoodAndSevereHunger(){
        assertFalse(NaturalFarmRules.paymentAllowed(7,false));
        assertTrue(NaturalFarmRules.paymentAllowed(8,false));
        assertFalse(NaturalFarmRules.paymentAllowed(8,true));
        assertFalse(NaturalFarmRules.paymentAllowed(100,true));
    }
}
