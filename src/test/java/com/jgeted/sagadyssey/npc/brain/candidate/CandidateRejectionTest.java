package com.jgeted.sagadyssey.npc.brain.candidate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandidateRejectionTest {
    @Test
    void retainsStructuredDiagnosticReason() {
        CandidateRejection rejection = new CandidateRejection(
                "smith:order:17",
                CandidateRejection.Code.MISSING_PREREQUISITE,
                "missing coal");

        assertEquals("smith:order:17", rejection.candidateKey());
        assertEquals(CandidateRejection.Code.MISSING_PREREQUISITE, rejection.code());
        assertEquals("missing coal", rejection.detail());
    }

    @Test
    void rejectsIncompleteDiagnosticData() {
        assertThrows(IllegalArgumentException.class,
                () -> new CandidateRejection("", CandidateRejection.Code.EXPIRED, "expired"));
        assertThrows(NullPointerException.class,
                () -> new CandidateRejection("candidate", null, "expired"));
        assertThrows(IllegalArgumentException.class,
                () -> new CandidateRejection("candidate", CandidateRejection.Code.EXPIRED, " "));
    }
}
