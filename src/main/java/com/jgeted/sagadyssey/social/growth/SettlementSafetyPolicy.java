package com.jgeted.sagadyssey.social.growth;

/** Bounded policy; no random deaths, light radius approximation or free healing. */
public final class SettlementSafetyPolicy {
    public static final int SCAN_INTERVAL = 200, QUIET_TICKS = 200, WORK_SLICE = 200;
    public static final int DEFENCE_RADIUS = 16, THREAT_RADIUS = 12;
    private SettlementSafetyPolicy() {}
    public static boolean night(long dayTime) {
        long t = Math.floorMod(dayTime, 24000);
        return t >= 13000 && t < 23000;
    }
    public static boolean defend(boolean trained, boolean equipped, float health, float maximum,
                                 boolean incapacitated, boolean actualThreat, double distanceFromHomeSquared) {
        return trained && equipped && maximum > 0 && health > maximum * .5F && !incapacitated
                && actualThreat && distanceFromHomeSquared <= DEFENCE_RADIUS * DEFENCE_RADIUS;
    }
    public static boolean quiet(long now, long lastThreat) { return now - lastThreat >= QUIET_TICKS; }
}
