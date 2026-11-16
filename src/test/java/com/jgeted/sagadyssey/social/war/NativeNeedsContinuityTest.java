package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.social.life.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NativeNeedsContinuityTest {
    @Test void oldNeedsLoadWithAnAccrualAnchorAndMealsDoNotMoveIt() {
        var id = UUID.randomUUID(); var old = new NpcNeedsRecord(id, 700, -1, "old", 200); var tag = old.save(); tag.remove("LastAccrued");
        var loaded = NpcNeedsRecord.load(tag); assertEquals(200, loaded.lastAccruedAt());
        var fed = loaded.fed(200, "actual food", 250); assertEquals(200, fed.lastAccruedAt()); assertEquals(500, fed.hunger());
        assertEquals(fed, NpcNeedsRecord.load(fed.save()));
    }
    @Test void aLongUnloadedIntervalAccruesOnceWithoutEatingOrRefilling() {
        var data = new NpcLifeData(); var id = UUID.randomUUID(); data.ensureNeeds(id, 0);
        assertTrue(data.accrueHungerThrough(id, 1_000, 0)); assertEquals(130, data.needs(id).hunger());
        assertTrue(data.accrueHungerThrough(id, 1_000, 3)); assertEquals(130, data.needs(id).hunger());
        assertTrue(data.accrueHungerThrough(id, 1_200, 3)); assertEquals(135, data.needs(id).hunger());
        assertEquals(-1, data.needs(id).lastAteAt()); assertFalse(data.accrueHungerThrough(id, 1_199, 0));
    }
    @Test void hungerSaturationStillAdvancesTheClockAndCannotOverflow() {
        var data = new NpcLifeData(); var id = UUID.randomUUID(); data.ensureNeeds(id, 0);
        assertTrue(data.accrueHungerThrough(id, Long.MAX_VALUE, 0)); assertEquals(1000, data.needs(id).hunger());
        long accrued = data.needs(id).lastAccruedAt(); assertTrue(accrued > 0); assertTrue(data.accrueHungerThrough(id, Long.MAX_VALUE, 0));
        data.increaseHunger(id, Integer.MAX_VALUE, Long.MAX_VALUE); assertEquals(1000, data.needs(id).hunger());
    }
    @Test void nutritionReceiptAndNeedsAreSavedTogetherAndCannotBeAppliedTwice() {
        var data = new NpcLifeData(); var id = UUID.randomUUID(); var operation = UUID.randomUUID(); data.ensureNeeds(id, 0); data.setHunger(id, 700, 1);
        var receipt = data.feedOnce(operation, id, 200, "actual ration", 2, 3); assertEquals(500, data.needs(id).hunger());
        assertEquals(receipt, data.feedOnce(operation, id, 200, "actual ration", 2, 99)); assertEquals(500, data.needs(id).hunger());
        assertThrows(IllegalStateException.class, () -> data.feedOnce(operation, id, 300, "actual ration", 2, 100));
        var saved = data.save(new CompoundTag(), null); assertEquals(1, MinecraftApi.listOfType(saved, "MealReceipts", 10).size());
        assertEquals(receipt, NpcMealReceipt.load(MinecraftApi.listOfType(saved, "MealReceipts", 10).getCompoundOrEmpty(0)));
        assertEquals(500, MinecraftApi.listOfType(saved, "Needs", 10).getCompoundOrEmpty(0).getIntOr("Hunger", 0));
    }
}
