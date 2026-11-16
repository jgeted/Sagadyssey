package com.jgeted.sagadyssey.social.war.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded presentation and input. Each control refers to an action offered by the server for that view. */
public final class WarManagementInteraction {
    public static final int CLOSE = -1;
    public static final int MAP_POINT = -2;
    public static final int MAX_LINES = 24;
    public static final int MAX_FIELDS = 3;
    public static final int MAX_CONTROLS = 16;
    public static final int MAX_VALUE = 80;

    private WarManagementInteraction() {}

    public enum Display { PANEL, WORLD, COMMAND_MAP, CLOSED }

    public record Field(String label, String initial, int length) {
        public Field {
            limit(label, 64);
            if (length < 1 || length > MAX_VALUE) throw new IllegalArgumentException("panel field length");
            limit(initial, length);
        }
    }

    public record View(UUID session, long revision, Display display, String title, String message,
                       List<String> lines, List<Field> fields, List<String> controls,
                       Optional<WarMapSheet> map) {
        public View {
            Objects.requireNonNull(session);
            Objects.requireNonNull(display);
            Objects.requireNonNull(map);
            limit(title, 96);
            limit(message, 256);
            lines = List.copyOf(lines);
            fields = List.copyOf(fields);
            controls = List.copyOf(controls);
            if (revision < 1 || lines.size() > MAX_LINES || fields.size() > MAX_FIELDS || controls.size() > MAX_CONTROLS
                    || display != Display.PANEL && (!lines.isEmpty() || !fields.isEmpty() || !controls.isEmpty() || map.isPresent())
                    || map.isPresent() && !fields.isEmpty()) throw new IllegalArgumentException("panel bounds");
            lines.forEach(line -> limit(line, 256));
            controls.forEach(label -> limit(label, 80));
        }
    }

    public record Request(UUID session, long revision, int control, List<String> values, int x, int z) {
        public Request {
            Objects.requireNonNull(session);
            values = List.copyOf(values);
            if (revision < 1 || control < MAP_POINT || control >= MAX_CONTROLS || values.size() > MAX_FIELDS
                    || x < 0 || x > 127 || z < 0 || z > 127 || control < 0 && !values.isEmpty()
                    || control != MAP_POINT && (x != 0 || z != 0)) throw new IllegalArgumentException("panel request bounds");
            values.forEach(value -> limit(value, MAX_VALUE));
        }
    }

    private static void limit(String value, int maximum) {
        if (Objects.requireNonNull(value).length() > maximum) throw new IllegalArgumentException("panel text bounds");
    }
}
