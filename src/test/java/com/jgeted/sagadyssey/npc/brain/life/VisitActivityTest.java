package com.jgeted.sagadyssey.npc.brain.life;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisitActivityTest {
    private VisitActivity activity(int start, int end) {
        return new VisitActivity(ResourceLocation.parse("sagadyssey:talk"), VisitActivity.Kind.TALK,
                ResourceLocation.parse("sagadyssey:meeting"), 100, 2, start, end);
    }
    @Test void normalWindowHasExclusiveEnd() {
        var a = activity(1000, 12000);
        assertFalse(a.availableAt(999)); assertTrue(a.availableAt(1000));
        assertTrue(a.availableAt(11999)); assertFalse(a.availableAt(12000));
    }
    @Test void overnightAndWholeDayWindows() {
        var a = activity(22000, 1000);
        assertTrue(a.availableAt(23000)); assertTrue(a.availableAt(0));
        assertFalse(a.availableAt(1000)); assertTrue(a.availableAt(-1));
        assertTrue(activity(0, 0).availableAt(12345));
    }
    @Test void dataDrivenDefinitionParses() {
        var json = JsonParser.parseString("{\"kind\":\"TALK\",\"facility\":\"sagadyssey:meeting\",\"duration_ticks\":100,\"capacity\":2,\"window_start\":1000,\"window_end\":12000}").getAsJsonObject();
        assertEquals(activity(1000, 12000), VisitActivity.parse(ResourceLocation.parse("sagadyssey:talk"), json));
    }
    @Test void fractionalOrStringCapacityRejected() {
        var json = JsonParser.parseString("{\"kind\":\"TALK\",\"facility\":\"sagadyssey:meeting\",\"duration_ticks\":100,\"capacity\":2.5,\"window_start\":0,\"window_end\":0}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> VisitActivity.parse(ResourceLocation.parse("sagadyssey:talk"), json));
        json.addProperty("capacity", "2");
        assertThrows(IllegalArgumentException.class, () -> VisitActivity.parse(ResourceLocation.parse("sagadyssey:talk"), json));
    }
    @Test void unboundedDurationOrSinglePartyTalkRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VisitActivity(ResourceLocation.parse("sagadyssey:talk"),
                VisitActivity.Kind.TALK, ResourceLocation.parse("sagadyssey:meeting"), 0, 2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new VisitActivity(ResourceLocation.parse("sagadyssey:talk"),
                VisitActivity.Kind.TALK, ResourceLocation.parse("sagadyssey:meeting"), 100, 1, 0, 0));
    }
}
