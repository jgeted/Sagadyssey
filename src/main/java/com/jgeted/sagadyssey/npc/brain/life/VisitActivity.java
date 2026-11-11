package com.jgeted.sagadyssey.npc.brain.life;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import java.util.Objects;

/** Data-driven intent only: never grants location knowledge, access, food or relationships. */
public record VisitActivity(Identifier id, Kind kind, Identifier facility,
                            int durationTicks, int capacity, int windowStart, int windowEnd) {
    public enum Kind { TALK, VISIT, TAVERN, READ, OBSERVE, WAIT }

    public VisitActivity {
        Objects.requireNonNull(id, "activity id");
        Objects.requireNonNull(kind, "activity kind");
        Objects.requireNonNull(facility, "facility id");
        if (durationTicks < 20 || durationTicks > 24000) throw new IllegalArgumentException("invalid duration");
        if (capacity < 1 || capacity > 64) throw new IllegalArgumentException("invalid capacity");
        if ((kind == Kind.TALK || kind == Kind.VISIT) && capacity < 2)
            throw new IllegalArgumentException("paired activity needs two places");
        if (windowStart < 0 || windowStart >= 24000 || windowEnd < 0 || windowEnd >= 24000)
            throw new IllegalArgumentException("invalid daily window");
    }

    public boolean availableAt(long dayTime) {
        long time = Math.floorMod(dayTime, 24000L);
        if (windowStart == windowEnd) return true;
        return windowStart < windowEnd ? time >= windowStart && time < windowEnd
                : time >= windowStart || time < windowEnd;
    }

    public static VisitActivity parse(Identifier id, JsonObject json) {
        return new VisitActivity(id, Kind.valueOf(json.get("kind").getAsString()),
                Identifier.parse(json.get("facility").getAsString()),
                integer(json, "duration_ticks"), integer(json, "capacity"),
                integer(json, "window_start"), integer(json, "window_end"));
    }

    private static int integer(JsonObject json, String key) {
        var value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("missing numeric " + key);
        try { return new java.math.BigDecimal(value.getAsString()).intValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("non-integer " + key, e); }
    }
}
