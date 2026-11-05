package com.jgeted.sagadyssey.social.growth;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SettlementPolicyTest {
    @Test void sufficientFoodReleasesLaborWithoutThrashing() {
        assertFalse(SettlementPolicy.harvest(true, 63, 4.5, false));
        assertFalse(SettlementPolicy.harvest(false, 30, 4.5, false));
        assertTrue(SettlementPolicy.harvest(false, 17, 4.5, false));
        assertTrue(SettlementPolicy.harvest(true, 30, 4.5, false));
    }
    @Test void actualHungerOverridesTheReserveHysteresis() {
        assertTrue(SettlementPolicy.harvest(false, 1000, 4.5, true));
    }
    @Test void infeasibleAncientTaskCannotBlockAnAvailableBed() {
        var stone = new SettlementPolicy.Candidate("stone", 1, 0, 0, 1, 5, false);
        var bed = new SettlementPolicy.Candidate("bed", 1, 100, 1000, 400, 1, true);
        assertEquals("bed", SettlementPolicy.choose(List.of(stone, bed)).id());
    }
    @Test void ageDoesNotOverrideSafetyTier() {
        var safe = new SettlementPolicy.Candidate("safe", 0, 100, 1000, 100, 0, true);
        var old = new SettlementPolicy.Candidate("old", 4, 0, 0, 1, 10, true);
        assertEquals("safe", SettlementPolicy.choose(List.of(old, safe)).id());
    }
    @Test void builtAndCarriedMaterialsRemainPartOfTheBudget() {
        assertEquals(13, SettlementPolicy.materialDeficit(55, 10, 30, 2));
        assertEquals(0, SettlementPolicy.materialDeficit(55, 54, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> SettlementPolicy.materialDeficit(55, 0, -1, 0));
    }
}
