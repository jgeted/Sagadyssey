package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarProjectileSafety;
import com.jgeted.sagadyssey.social.war.api.WarShotPermit;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarShotCodecTest {
    private static WarShotPermit permit() {
        return new WarShotPermit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true, 123);
    }
    @Test void everySourceReferenceAndTheExactShotTickRoundTrip() throws Exception {
        var original = permit(); var tag = WarProjectileSafety.encode(original);
        assertEquals(11, tag.size()); assertEquals(original, WarProjectileSafety.decode(TagParser.parseCompoundFully(tag.toString())).orElseThrow());
    }
    @Test void unknownFieldsAndWrongTypesAreRetainedButNeverTrusted() {
        for (String corruption : List.of("extra", "uuid", "clock", "flag", "negative")) {
            var tag = WarProjectileSafety.encode(permit());
            switch (corruption) {
                case "extra" -> tag.putInt("FutureSchema", 2);
                case "uuid" -> tag.putString("Body", UUID.randomUUID().toString());
                case "clock" -> tag.putInt("FiredAt", 123);
                case "flag" -> tag.putByte("Immediate", (byte)2);
                case "negative" -> tag.putLong("FiredAt", -1);
            }
            var before = tag.copy(); assertTrue(WarProjectileSafety.decode(tag).isEmpty(), corruption); assertEquals(before, tag);
        }
        assertTrue(WarProjectileSafety.decode(null).isEmpty()); assertTrue(WarProjectileSafety.decode(StringTag.valueOf("future")).isEmpty());
    }
}
