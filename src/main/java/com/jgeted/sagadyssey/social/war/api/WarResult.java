package com.jgeted.sagadyssey.social.war.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record WarResult(Status status, Optional<Receipt> receipt) {
    public WarResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(receipt, "receipt");
        if ((status == Status.APPLIED || status == Status.REPLAYED) != receipt.isPresent()) {
            throw new IllegalArgumentException("result and receipt disagree");
        }
    }

    public enum Status {
        APPLIED, REPLAYED, OPERATION_CONFLICT, STALE_REVISION, STALE_ORDER,
        NOT_FOUND, ALREADY_EXISTS, DENIED, UNAVAILABLE, UNSUPPORTED_DEFINITION,
        CAPACITY_EXCEEDED, TIME_REVERSED, RETIRED_GENERATION, FUTURE_GENERATION, ARCHIVING
    }

    public record Receipt(UUID operationId, long generation, UUID unitId, long unitRevision,
                          long orderSequence, long appliedAtTick) {
        public Receipt(UUID operationId, UUID unitId, long unitRevision, long orderSequence, long appliedAtTick) {
            this(operationId, 0, unitId, unitRevision, orderSequence, appliedAtTick);
        }
        public Receipt {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(unitId, "unitId");
            if (generation < 0 || unitRevision < 1 || orderSequence < 0 || appliedAtTick < 0) {
                throw new IllegalArgumentException("invalid receipt");
            }
        }
    }

    public static WarResult rejected(Status status) { return new WarResult(status, Optional.empty()); }
    public static WarResult applied(Receipt receipt) { return new WarResult(Status.APPLIED, Optional.of(receipt)); }
    public static WarResult replayed(Receipt receipt) { return new WarResult(Status.REPLAYED, Optional.of(receipt)); }
}
