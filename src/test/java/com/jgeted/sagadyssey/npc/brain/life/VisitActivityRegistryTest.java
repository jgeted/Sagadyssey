package com.jgeted.sagadyssey.npc.brain.life;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class VisitActivityRegistryTest {
    @Test void definitionsPublishedAsImmutableSnapshot() {
        var registry = new VisitActivityRegistry(); var id = Identifier.parse("sagadyssey:wait");
        registry.replace(Map.of(id, JsonParser.parseString("{\"kind\":\"WAIT\",\"facility\":\"sagadyssey:safe_rest\",\"duration_ticks\":100,\"capacity\":1,\"window_start\":0,\"window_end\":0}")));
        assertEquals(VisitActivity.Kind.WAIT, registry.snapshot().get(id).kind());
        assertThrows(UnsupportedOperationException.class, () -> registry.snapshot().clear());
    }
    @Test void failedReloadDoesNotPublishPartialDefinitions() {
        var registry = new VisitActivityRegistry(); var id = Identifier.parse("sagadyssey:wait");
        registry.replace(Map.of(id, JsonParser.parseString("{\"kind\":\"WAIT\",\"facility\":\"sagadyssey:safe_rest\",\"duration_ticks\":100,\"capacity\":1,\"window_start\":0,\"window_end\":0}")));
        var original = registry.snapshot();
        assertThrows(IllegalArgumentException.class, () -> registry.replace(Map.of(id, JsonParser.parseString("[]"))));
        assertSame(original, registry.snapshot());
    }
}
