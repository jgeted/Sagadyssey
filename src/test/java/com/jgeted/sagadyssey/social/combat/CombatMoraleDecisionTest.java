package com.jgeted.sagadyssey.social.combat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CombatMoraleDecisionTest {
    @Test
    void moraleEventsAreIdempotentAndRecoverGraduallyTowardBaseline() {
        CombatMoraleData morale = new CombatMoraleData();
        UUID fighter = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        MoraleEvent casualty = new MoraleEvent(eventId, fighter, UUID.randomUUID(),
                MoraleEventType.ALLY_KILLED, -20, UUID.randomUUID(), 0L, "战友阵亡");

        assertEquals(30, morale.record(casualty).current());
        assertEquals(30, morale.record(casualty).current());
        assertEquals(32, morale.profile(fighter, 12_000L).current());
        assertEquals(32, morale.groupAverage(List.of(fighter), 12_000L));
    }

    @Test
    void aHoldOrderCannotOverrideSeverePressureAndSurvival() {
        TriageAssessment wounded = new TriageAssessment(2, 40, 20, 0,
                20, 10, false, false);
        CombatDecision escape = CombatDecisionService.evaluate(25, wounded,
                2, 5, true, true, true, false);
        CombatDecision trapped = CombatDecisionService.evaluate(25, wounded,
                2, 5, false, true, true, false);

        assertEquals(CombatPosture.ROUT, escape.posture());
        assertEquals(CombatPosture.SURRENDER, trapped.posture());
        assertTrue(escape.reason().contains("溃逃"));
    }

    @Test
    void goodMoraleFavorableOddsAndMinorInjuryPermitAdvance() {
        TriageAssessment fit = new TriageAssessment(0, 5, 0, 0,
                0, 0, false, false);
        CombatDecision decision = CombatDecisionService.evaluate(90, fit,
                4, 3, true, true, false, false);
        assertEquals(CombatPosture.ADVANCE, decision.posture());
        assertTrue(decision.effectiveResolve() >= 72);
    }
}
