package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.persistence.WarLeadershipCodec;
import com.jgeted.sagadyssey.social.war.domain.WarLeadershipRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.jgeted.sagadyssey.social.war.domain.WarLeadershipRecord.Specialty.*;
import static org.junit.jupiter.api.Assertions.*;

class WarLeadershipTest {
    @Test void ordersAndEmptyOrPartialArrivalsCannotOnTheirOwnEarnExperience() {
        assertFalse(WarLeadershipRecord.qualifies(MANEUVER, 100, 10, 7, false));
        assertTrue(WarLeadershipRecord.qualifies(MANEUVER, 100, 10, 8, false));
        assertFalse(WarLeadershipRecord.qualifies(ASSAULT, 200, 10, 10, false));
        assertTrue(WarLeadershipRecord.qualifies(ASSAULT, 200, 10, 0, true));
        assertFalse(WarLeadershipRecord.qualifies(HOLD, 80, 10, 6, false));
        assertFalse(WarLeadershipRecord.qualifies(HOLD, 79, 10, 6, true));
        assertTrue(WarLeadershipRecord.qualifies(HOLD, 80, 10, 6, true));
        assertFalse(WarLeadershipRecord.qualifies(WITHDRAW, 100, 10, 7, true));
        assertTrue(WarLeadershipRecord.qualifies(WITHDRAW, 100, 10, 8, true));
    }

    @Test void distinctEffectsRemainBoundedAndReceiptSurvivesRoundTrip() throws Exception {
        assertEquals(100, WarLeadershipRecord.pathRetryTicks(0));
        assertEquals(76, WarLeadershipRecord.pathRetryTicks(100));
        assertEquals(2, WarLeadershipRecord.formationLossDiscount(1));
        assertEquals(16, WarLeadershipRecord.formationLossDiscount(9));
        assertEquals(120, WarLeadershipRecord.rallyRecoveryBonus(8));
        var codec=new WarLeadershipCodec();
        var value=new WarLeadershipRecord(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                HOLD,17,97,12,9,Optional.of(UUID.randomUUID()));
        var encoded=codec.write(List.of(value));
        assertEquals(List.of(value),codec.read(encoded));
        assertThrows(java.io.IOException.class,()->codec.write(List.of(value,value)));
    }
}
