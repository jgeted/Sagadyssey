package com.jgeted.sagadyssey.social.growth;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementDoorTimingTest {
    private static final BlockPos DOOR = new BlockPos(-6420, 66, -635);

    @Test void playerOpeningDoesNotReuseExpiredNpcTimer() {
        var row = new CompoundTag();
        row.putLong("DoorOpen:" + DOOR.asLong(), 100);
        assertFalse(SettlementDoorTiming.mayClose(row, DOOR, false, false, 1000));
        assertFalse(SettlementDoorTiming.mayClose(row, DOOR, true, false, 1001));
        assertFalse(SettlementDoorTiming.mayClose(row, DOOR, true, false, 1040));
        assertTrue(SettlementDoorTiming.mayClose(row, DOOR, true, false, 1041));
    }

    @Test void closeAndReopenReceivesANewHoldPeriod() {
        var row = new CompoundTag();
        assertFalse(SettlementDoorTiming.mayClose(row, DOOR, true, false, 100));
        assertTrue(SettlementDoorTiming.mayClose(row, DOOR, true, false, 140));
        assertFalse(SettlementDoorTiming.mayClose(row, DOOR, false, false, 141));
        assertFalse(SettlementDoorTiming.mayClose(row, DOOR, true, false, 142));
        assertFalse(SettlementDoorTiming.mayClose(row, DOOR, true, false, 181));
        assertTrue(SettlementDoorTiming.mayClose(row, DOOR, true, false, 182));
    }

    @Test void crossingKeepsDoorOpenAndTimerSurvivesSave() {
        var row = new CompoundTag();
        assertFalse(SettlementDoorTiming.mayClose(row, DOOR, false, true, 100));
        assertFalse(SettlementDoorTiming.mayClose(row, DOOR, true, true, 200));
        var restored = row.copy();
        assertFalse(SettlementDoorTiming.mayClose(restored, DOOR, true, false, 239));
        assertTrue(SettlementDoorTiming.mayClose(restored, DOOR, true, false, 240));
    }

    @Test void differentDoorsDoNotShareOpeningTime() {
        var row = new CompoundTag();
        assertFalse(SettlementDoorTiming.mayClose(row, DOOR, true, false, 100));
        assertFalse(SettlementDoorTiming.mayClose(row, DOOR.east(), true, false, 140));
        assertTrue(SettlementDoorTiming.mayClose(row, DOOR, true, false, 140));
        assertFalse(SettlementDoorTiming.mayClose(row, DOOR.east(), true, false, 179));
    }
}
