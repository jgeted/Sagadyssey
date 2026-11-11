package com.jgeted.sagadyssey.social.industry.animal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WoolSupplyNeedsTest {
    @Test void unfinishedHouseDoesNotCountPlannedBedsAsCapacity() {
        assertEquals(3, WoolSupplyNeeds.unmadeBeds(64, 0, 0, false));
        assertEquals(3, WoolSupplyNeeds.unmadeBeds(64, 65, 0, false));
    }
    @Test void actualBedsCarriedAndInstalledAreNotRequestedAgain() {
        assertEquals(2, WoolSupplyNeeds.unmadeBeds(64, 66, 0, false));
        assertEquals(0, WoolSupplyNeeds.unmadeBeds(64, 66, 1, true));
        assertEquals(0, WoolSupplyNeeds.unmadeBeds(64, 68, 0, false));
    }
}
