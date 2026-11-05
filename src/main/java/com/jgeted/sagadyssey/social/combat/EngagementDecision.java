package com.jgeted.sagadyssey.social.combat;

public record EngagementDecision(EngagementAction action, String reason) {
    public EngagementDecision {
        if (action == null) throw new IllegalArgumentException("action required");
        reason = reason == null ? "" : reason;
    }
}
