package com.jgeted.sagadyssey.npc.entity;

/** 士气分档及其实际战斗影响。 */
public enum NpcMoraleState {
    LOW(0, 19, "低迷", 0.85D, 0.45F),
    SHAKEN(20, 39, "动摇", 0.93D, 0.38F),
    STEADY(40, 69, "稳定", 1.00D, 0.30F),
    INSPIRED(70, 89, "振奋", 1.08D, 0.25F),
    HEROIC(90, 100, "昂扬", 1.15D, 0.20F);

    private final int min;
    private final int max;
    private final String displayName;
    private final double damageMultiplier;
    private final float retreatThreshold;

    NpcMoraleState(int min, int max, String displayName,
                   double damageMultiplier, float retreatThreshold) {
        this.min = min;
        this.max = max;
        this.displayName = displayName;
        this.damageMultiplier = damageMultiplier;
        this.retreatThreshold = retreatThreshold;
    }

    public static NpcMoraleState fromValue(int morale) {
        int clamped = Math.max(0, Math.min(100, morale));
        for (NpcMoraleState state : values()) {
            if (clamped >= state.min && clamped <= state.max) return state;
        }
        return STEADY;
    }

    public String displayName() { return displayName; }
    public double damageMultiplier() { return damageMultiplier; }
    public float retreatThreshold() { return retreatThreshold; }
    public float recoverThreshold() { return Math.min(0.75F, retreatThreshold + 0.30F); }
}
