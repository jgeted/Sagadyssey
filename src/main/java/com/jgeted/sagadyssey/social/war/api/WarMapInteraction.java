package com.jgeted.sagadyssey.social.war.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Inputs and projections exchanged with the military map session. */
public final class WarMapInteraction {
    public enum Action { REFRESH, PREVIEW, ISSUE, CLOSE, SELECT_FIELD }
    public static final UUID NONE = new UUID(0, 0);

    private WarMapInteraction() {}

    public record Request(UUID session, long revision, Action action, int page, UUID unit,
                          WarMapView.Mode mode, int pixelX, int pixelZ, UUID ticket) {
        public Request {
            Objects.requireNonNull(session);
            Objects.requireNonNull(action);
            Objects.requireNonNull(unit);
            Objects.requireNonNull(mode);
            Objects.requireNonNull(ticket);
            if (revision < 1 || page < 0 || page > 171 || pixelX < 0 || pixelX > 127 || pixelZ < 0 || pixelZ > 127)
                throw new IllegalArgumentException("invalid bounded map request");
        }
    }

    public record Update(UUID session, boolean openScreen, Optional<WarMapView> view, String message) {
        public Update {
            Objects.requireNonNull(session);
            Objects.requireNonNull(view);
            Objects.requireNonNull(message);
            if (message.length() > 256 || view.filter(v -> !v.session().equals(session)).isPresent())
                throw new IllegalArgumentException("invalid map update");
        }

        public static Update closed(UUID session, String message) {
            return new Update(session, false, Optional.empty(), message);
        }
    }
}
