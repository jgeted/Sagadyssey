package com.jgeted.sagadyssey.social.combat;

public record CombatDecision(CombatPosture posture, int effectiveResolve, String reason) {
    public CombatDecision {
        if (posture == null) throw new IllegalArgumentException("posture required");
        effectiveResolve = Math.max(0, Math.min(100, effectiveResolve));
        reason = reason == null ? "" : reason;
    }
}
