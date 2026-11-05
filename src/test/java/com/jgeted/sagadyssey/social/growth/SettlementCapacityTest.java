package com.jgeted.sagadyssey.social.growth;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

final class SettlementCapacityTest {
    private final UUID a=UUID.randomUUID(),b=UUID.randomUUID();
    @Test void villageSurplusCannotMaskOneStarvingHome(){
        assertFalse(SettlementCapacity.allStable(List.of(new SettlementCapacity.Home(a,3,90,3,2,true,0,12),
                new SettlementCapacity.Home(b,3,0,3,2,true,20,12))));
    }
    @Test void projectedFoodAndUnknownBedsAreNotCapacity(){
        assertFalse(new SettlementCapacity.Home(a,3,0,3,2,true,90,12).canSupport(3,2));
        assertFalse(new SettlementCapacity.Home(a,3,90,3,2,false,0,12).stableFloor());
    }
    @Test void dynamicResidentsRequireMoreRealFood(){
        assertTrue(new SettlementCapacity.Home(a,3,18,3,2,true,0,12).canSupport(3,2));
        assertFalse(new SettlementCapacity.Home(a,3,17,3,2,true,0,12).canSupport(3,2));
        assertTrue(new SettlementCapacity.Home(a,6,9,6,4,true,0,12).stableFloor());
        assertFalse(new SettlementCapacity.Home(a,6,8,6,4,true,0,12).stableFloor());
    }
    @Test void carriedDeliveryAlreadyCountsAgainstTarget(){
        assertEquals(0,SettlementCapacity.replenishment(64,50,16));
        assertEquals(4,SettlementCapacity.replenishment(64,50,10));
    }
    @Test void abundantFoodDoesNotReplaceAssignableLabour(){
        assertFalse(new SettlementCapacity.Home(a,3,128,3,1,true,0,12).canSupport(3,2));
        assertTrue(new SettlementCapacity.Home(a,3,128,3,2,true,0,12).canSupport(3,2));
    }
    @Test void peopleCannotBeCountedAtBothEnds(){
        assertTrue(SettlementCapacity.uniquePeople(List.of(a),List.of(b),List.of()));
        assertFalse(SettlementCapacity.uniquePeople(List.of(a),List.of(),List.of(a)));
    }
    @Test void windowRequiresDayAndThreeCycles(){
        assertEquals(24000,SettlementCapacity.stableWindow(1000));
        assertEquals(36000,SettlementCapacity.stableWindow(12000));
        assertThrows(IllegalArgumentException.class,()->SettlementCapacity.stableWindow(0));
    }
}
