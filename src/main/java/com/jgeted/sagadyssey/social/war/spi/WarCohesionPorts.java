package com.jgeted.sagadyssey.social.war.spi;

import com.jgeted.sagadyssey.social.war.domain.WarCohesion;
import com.jgeted.sagadyssey.social.war.api.WarCohesionFrame;
import java.util.*;

public final class WarCohesionPorts {
    private WarCohesionPorts() {}
    public enum Status { APPLIED, PENDING, UNKNOWN, UNAVAILABLE }
    public record Written(Status status, Optional<WarCohesion> durable, String reason) {}
    public interface Journal extends AutoCloseable {
        Collection<WarCohesion> recover();
        Written persist(WarCohesion value);
        @Override void close();
    }
    @FunctionalInterface public interface JournalFactory { Journal open() throws java.io.IOException; }
    /** Trusted assembly may replace the tactical rules without changing storage or native execution. */
    public interface Policy {
        WarCohesion plan(Optional<WarCohesion> previous, WarCohesionFrame frame, boolean recoveryEarned);
        long recoveryTicks();
        long maximumObservationGap();
    }
}
