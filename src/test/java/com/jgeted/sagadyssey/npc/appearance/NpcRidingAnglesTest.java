package com.jgeted.sagadyssey.npc.appearance;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NpcRidingAnglesTest {
    @Test void oppositeRiderDirectionCannotRotateTheHipsAwayFromTheSaddle() {
        float saddle = NpcRidingAngles.bodyYaw(5, 5, .5F);
        assertEquals(5, saddle);
        assertEquals(65, NpcRidingAngles.headYaw(180, 180, saddle, .5F));
        assertEquals(5, saddle);
    }

    @Test void turningAcrossNorthUsesTheShortArcAndLeavesHeadRelativeToTheSaddle() {
        float saddle = NpcRidingAngles.bodyYaw(179, -179, .5F);
        assertEquals(180, saddle, .001);
        assertEquals(1, NpcRidingAngles.headYaw(-179, -179, saddle, .5F), .001);
        assertEquals(-1, NpcRidingAngles.headYaw(179, 179, saddle, .5F), .001);
    }

    @Test void sideAndRearHeadTargetsStayAnatomicallyBoundedInBothDirections() {
        assertEquals(65, NpcRidingAngles.headYaw(270, 270, 180, 1));
        assertEquals(-65, NpcRidingAngles.headYaw(90, 90, 180, 1));
        assertEquals(-65, NpcRidingAngles.headYaw(0, 0, 180, 1));
        assertEquals(0, NpcRidingAngles.headYaw(180, 180, 180, 1));
    }
}
