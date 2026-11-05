package com.jgeted.sagadyssey.worldsim.lod;
import com.jgeted.sagadyssey.social.world.WorldDynamicsData;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WorldLodClockTest {
    @Test void displayedTimeCannotReplayOrSkipWorldDaysAfterClockMigration(){var d=new WorldDynamicsData();assertEquals(10,d.simulationDay(24000,240000));assertTrue(d.beginDay(10));assertEquals(10,d.simulationDay(24001,0));assertFalse(d.beginDay(10));assertEquals(11,d.simulationDay(48000,240000000));}
    @Test void previousFutureDayIsNotReplayedAndOffsetIsPersisted(){var d=new WorldDynamicsData();d.beginDay(100);assertEquals(100,d.simulationDay(24000,0));var t=d.save(new CompoundTag(),null);assertTrue(t.getBoolean("MonotonicClock"));assertEquals(99,t.getLong("DayOffset"));assertFalse(d.beginDay(100));}
}
