package com.jgeted.sagadyssey.social.industry;

import com.jgeted.sagadyssey.social.industry.food.NaturalFoodDemand;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NaturalFoodDemandTest {
    @Test void anUnmetMealOpensOneUnitRatherThanFillingAnUnboundedFishWarehouse() {
        assertEquals(1,NaturalFoodDemand.mealQuantity(1,0,false));
        assertEquals(2,NaturalFoodDemand.mealQuantity(12,0,false));
    }
    @Test void realFoodAlreadyAvailableMustNotBeReplacedByAFishingCommitment() {
        assertEquals(0,NaturalFoodDemand.mealQuantity(1,1,false));
        assertEquals(0,NaturalFoodDemand.mealQuantity(2,7,false));
        assertEquals(1,NaturalFoodDemand.mealQuantity(2,1,false));
    }
    @Test void pendingCustodyIsAResponsibilityNotEdibleStockOrAnotherCommitment() {
        assertEquals(0,NaturalFoodDemand.mealQuantity(3,0,true));
        assertEquals(2,NaturalFoodDemand.mealQuantity(3,0,false));
    }
    @Test void noHungryPersonOrInvalidCountsNeverCreateFreeProduction() {
        assertEquals(0,NaturalFoodDemand.mealQuantity(0,0,false));
        assertThrows(IllegalArgumentException.class,()->NaturalFoodDemand.mealQuantity(-1,0,false));
        assertThrows(IllegalArgumentException.class,()->NaturalFoodDemand.mealQuantity(1,-1,false));
    }
}
