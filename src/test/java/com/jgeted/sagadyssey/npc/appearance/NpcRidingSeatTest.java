package com.jgeted.sagadyssey.npc.appearance;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NpcRidingSeatTest {
    @Test void allAdultEquineSaddlesMeetTheBentThighInsteadOfTheRidersFeet() {
        for (double[] mount : new double[][]{{1.44375,1.1},{1.1125,.87},{1.2125,.92}}) {
            double attachment = NpcRidingSeat.attachmentHeight(mount[0],mount[1],1);
            double hip = mount[0]-attachment+NpcRidingSeat.HIP_HEIGHT;
            double saddle = NpcRidingSeat.SADDLE_HEIGHT*mount[1];
            assertEquals(.1625,hip-saddle,1e-8);
            assertTrue(attachment>.4 && attachment<.85);
        }
    }

    @Test void scaledRidersKeepTheSameProportionalSeatContact() {
        double offset=NpcRidingSeat.attachmentHeight(1.44375,1,.8);
        assertEquals(.13,1.44375-offset+NpcRidingSeat.HIP_HEIGHT*.8-NpcRidingSeat.SADDLE_HEIGHT,1e-8);
        assertEquals(.6,NpcRidingSeat.DEFAULT_ATTACHMENT.y,1e-8);
    }
}
