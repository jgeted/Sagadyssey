package com.jgeted.sagadyssey.npc.handover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcInteractionPolicyTest {
    @Test
    void ordinaryRightClickNeverBecomesAnAccidentalHandover() {
        assertFalse(NpcInteractionPolicy.opensHandoverShortcut(true, false));
        assertFalse(NpcInteractionPolicy.opensHandoverShortcut(false, false));
        assertFalse(NpcInteractionPolicy.opensHandoverShortcut(false, true));
        assertTrue(NpcInteractionPolicy.opensHandoverShortcut(true, true));
    }
}
