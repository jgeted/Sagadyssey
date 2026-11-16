package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.api.WarWeaponProfile;
import java.util.Objects;

/** 无世界副作用的蓄力与射击条件。蓄力中断后重新开始，绝不按错过的tick补射。 */
public final class WarRangedPolicy {
    public enum Action { OUT_OF_RANGE, NEED_BACKUP, NO_AMMO, COOLDOWN, BEGIN, DRAW, FIRE, BLOCKED }
    private WarRangedPolicy() {}
    public static Action decide(WarWeaponProfile.Ranged profile, double distanceSquared, boolean lineOfSight,
                                boolean hasAmmo, long now, long nextAttack, long drawingSince) {
        Objects.requireNonNull(profile);
        if (!Double.isFinite(distanceSquared) || distanceSquared < 0 || now < 0 || nextAttack < 0 || drawingSince < -1)
            throw new IllegalArgumentException("invalid ranged observation");
        if (drawingSince > now || now > Long.MAX_VALUE - profile.recoveryTicks()) return Action.BLOCKED;
        if (distanceSquared < profile.preferredMinimumReach() * profile.preferredMinimumReach()) return Action.NEED_BACKUP;
        if (!lineOfSight || distanceSquared > profile.maximumReach() * profile.maximumReach()) return Action.OUT_OF_RANGE;
        if (!hasAmmo) return Action.NO_AMMO;
        if (now < nextAttack) return Action.COOLDOWN;
        if (drawingSince < 0) return Action.BEGIN;
        return now - drawingSince >= profile.drawTicks() ? Action.FIRE : Action.DRAW;
    }
}
