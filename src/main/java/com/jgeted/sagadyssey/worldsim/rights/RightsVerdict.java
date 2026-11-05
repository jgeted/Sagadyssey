package com.jgeted.sagadyssey.worldsim.rights;

import java.util.List;

public record RightsVerdict(Status status, List<String> bases, String reason) {
    public RightsVerdict {
        bases = List.copyOf(bases == null ? List.of() : bases);
        reason = reason == null ? "" : reason;
    }

    public static RightsVerdict allow(String basis) {
        return new RightsVerdict(Status.ALLOW, List.of(basis), "");
    }

    public static RightsVerdict deny(String reason) {
        return new RightsVerdict(Status.DENY, List.of(), reason);
    }

    public enum Status { ALLOW, DENY, CONTESTED }
}
