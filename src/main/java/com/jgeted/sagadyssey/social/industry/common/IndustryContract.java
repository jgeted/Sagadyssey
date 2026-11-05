package com.jgeted.sagadyssey.social.industry.common;

import java.util.*;

/** Immutable planning projections. None of these values grants rights, reserves goods or executes work. */
public final class IndustryContract {
    private IndustryContract() {}
    public enum Industry { FOOD, FORESTRY, STONE, METAL, WOOL, FISH, WORKSHOP, LOGISTICS }
    public enum Mode { CONTROLLED_NEAR, WORLDGEN, RUNTIME_NEAR, REMOTE }
    public enum Grade { NONE, EXECUTION, CYCLE, MIXED }
    public enum State { READY, BLOCKED, POTENTIAL, UNKNOWN, SATISFIED }
    public enum Cause { NO_DEMAND, UNVERIFIED, WRONG_SCOPE, UNKNOWN_SOURCE, NO_PERMISSION,
        LABOUR_BUSY, NO_TOOL, NO_FACILITY, ROUTE_BLOCKED, MISSING_INPUT, NEEDS_APPROVAL }

    /** All quantities describe the same physical stock; the overlap is not subtracted twice. */
    public record Stock(int physicalOwned, int protectedQuantity, int reservedQuantity,
                        int protectedReservedOverlap, boolean known, boolean authorized) {
        public Stock {
            if (physicalOwned < 0 || protectedQuantity < 0 || reservedQuantity < 0
                    || protectedReservedOverlap < 0 || protectedQuantity > physicalOwned
                    || reservedQuantity > physicalOwned
                    || protectedReservedOverlap > Math.min(protectedQuantity, reservedQuantity)
                    || (long) protectedQuantity + reservedQuantity - protectedReservedOverlap > physicalOwned)
                throw new IllegalArgumentException("inconsistent stock projection");
        }
        public int available() {
            return !known || !authorized ? 0
                    : physicalOwned - protectedQuantity - reservedQuantity + protectedReservedOverlap;
        }
    }

    /** remainingBatches is remaining uncommitted demand, not an original total or promised arrival. */
    public record Demand(String id, UUID person, UUID body, UUID household, UUID settlement,
                         long tick, int remainingBatches, Map<String, Stock> inputs,
                         boolean workerAvailable, boolean toolAvailable, boolean facilityAvailable,
                         boolean routeAvailable) {
        public Demand {
            text(id); Objects.requireNonNull(person); Objects.requireNonNull(body);
            Objects.requireNonNull(household); Objects.requireNonNull(settlement);
            if (tick < 0 || remainingBatches < 0) throw new IllegalArgumentException("invalid demand");
            inputs = Map.copyOf(inputs);
        }
    }

    public record Evidence(String id, String sourceJar, Grade grade, Set<Mode> modes, String boundary) {
        public Evidence {
            text(id); text(sourceJar); Objects.requireNonNull(grade); modes = Set.copyOf(modes); text(boundary);
            if (grade != Grade.NONE && !sourceJar.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("evidence requires exact source version");
        }
    }

    public record Capability(String id, Industry industry, Map<String,Integer> inputPerBatch,
                             boolean needsTool, boolean needsFacility, Evidence evidence,
                             boolean executionConnected, boolean approvalRequired) {
        public Capability {
            text(id); Objects.requireNonNull(industry); inputPerBatch = Map.copyOf(inputPerBatch);
            if (inputPerBatch.values().stream().anyMatch(q -> q == null || q <= 0))
                throw new IllegalArgumentException("inputs must be positive");
            Objects.requireNonNull(evidence);
        }
        public boolean verifiedIn(Mode mode) {
            return executionConnected && !approvalRequired && evidence.grade() != Grade.NONE
                    && evidence.modes().contains(mode);
        }
    }

    /** A proposal, never a claim on a person, material or facility. Actual services must recheck at commit. */
    public record Offer(String demandId, String capabilityId, State state, int executableBatches,
                        List<Cause> causes, Set<String> recheckOn, long observedAt) {
        public Offer {
            text(demandId); text(capabilityId); Objects.requireNonNull(state);
            causes = List.copyOf(causes); recheckOn = Set.copyOf(recheckOn);
            if (executableBatches < 0 || observedAt < 0
                    || (state == State.READY) != (executableBatches > 0))
                throw new IllegalArgumentException("invalid offer");
        }
    }

    /** The request fingerprint excludes a mutable inventory revision, but includes every effect-bearing field. */
    public record OperationIdentity(String businessId, String subOperation, int cycle, UUID person,
                                    UUID body, String from, String to, String itemFingerprint,
                                    int quantity, String intent) {
        public OperationIdentity {
            text(businessId); text(subOperation); Objects.requireNonNull(person); Objects.requireNonNull(body);
            text(from); text(to); text(itemFingerprint); text(intent);
            if (cycle < 0 || quantity <= 0) throw new IllegalArgumentException("invalid operation identity");
        }
        public boolean sameRequestAs(OperationIdentity other) { return equals(other); }
    }

    /** Audit-only reference to existing plans. This is not a new labour reservation service or durable ledger. */
    public record WorkClaimRef(UUID person, UUID body, UUID plan, long start, long endExclusive,
                               boolean actualHumanWork) {
        public WorkClaimRef {
            Objects.requireNonNull(person); Objects.requireNonNull(body); Objects.requireNonNull(plan);
            if (start < 0 || endExclusive <= start) throw new IllegalArgumentException("invalid work interval");
        }
        public boolean conflictsWith(WorkClaimRef other) {
            return person.equals(other.person) && actualHumanWork && other.actualHumanWork
                    && !plan.equals(other.plan) && start < other.endExclusive && other.start < endExclusive;
        }
    }

    private static void text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("identity is required");
    }
}
