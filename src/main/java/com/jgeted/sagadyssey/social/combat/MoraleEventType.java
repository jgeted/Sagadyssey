package com.jgeted.sagadyssey.social.combat;

public enum MoraleEventType {
    TOOK_DAMAGE(-6),
    SERIOUS_WOUND(-12),
    ALLY_WOUNDED(-8),
    ALLY_KILLED(-20),
    OUTNUMBERED(-10),
    LEADER_FLED(-18),
    UNPAID(-8),
    HUNGRY(-6),
    LEADER_PRESENT(8),
    DEFENDING_HOME(10),
    FED_AND_RESTED(6),
    ENEMY_ROUTED(14),
    BATTLE_WON(18);

    private final int defaultDelta;
    MoraleEventType(int defaultDelta) { this.defaultDelta = defaultDelta; }
    public int defaultDelta() { return defaultDelta; }
}
