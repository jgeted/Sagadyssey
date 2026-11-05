package com.jgeted.sagadyssey.worldsim.lod;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class TickByteBudgetTest {
    @Test void viewerCannotEvadeCapWithMultipleContexts(){var b=new TickByteBudget(10,20);var id=UUID.randomUUID();assertTrue(b.admit(id,1,6));assertFalse(b.admit(id,1,5));assertTrue(b.admit(id,1,4));assertFalse(b.admit(id,1,1));}
    @Test void globalCapIncludesDifferentViewersAndResetsOnNextTick(){var b=new TickByteBudget(10,20);assertTrue(b.admit(UUID.randomUUID(),1,10));assertTrue(b.admit(UUID.randomUUID(),1,10));assertFalse(b.admit(UUID.randomUUID(),1,1));assertTrue(b.admit(UUID.randomUUID(),2,10));}
    @Test void rejectInvalidBytesAndBackwardsTime(){var b=new TickByteBudget(10,20);var id=UUID.randomUUID();assertFalse(b.admit(id,1,Integer.MAX_VALUE));assertTrue(b.admit(id,2,10));assertThrows(IllegalArgumentException.class,()->b.admit(id,1,1));assertThrows(IllegalArgumentException.class,()->b.admit(id,2,0));}
}
