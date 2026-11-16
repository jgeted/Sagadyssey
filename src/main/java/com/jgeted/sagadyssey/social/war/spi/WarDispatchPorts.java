package com.jgeted.sagadyssey.social.war.spi;

import com.jgeted.sagadyssey.social.war.domain.WarTransmission;
import java.util.*;

/** 军令传递的持久化端口。 */
public final class WarDispatchPorts {
    private WarDispatchPorts() {}
    @FunctionalInterface public interface JournalFactory { Journal open() throws java.io.IOException; }
    public enum Status { APPLIED, PENDING, UNKNOWN, UNAVAILABLE }
    public record Written(Status status, Optional<WarTransmission> durable, String reason) {}
    public interface Journal extends AutoCloseable {
        int capacity();
        Collection<WarTransmission> recover();
        Written persist(WarTransmission value);
        default boolean wantsArchiveCandidates() { return false; }
        /** Returns IDs only after the cold page and the new hot checkpoint are both durable. */
        default Set<UUID> maintain(List<WarTransmission> archived) { return Set.of(); }
        default long archivedCount() { return 0; }
        default String archiveProblem() { return ""; }
        @Override void close();
    }
}
