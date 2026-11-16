package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.api.WarWeaponProfile;
import java.util.Objects;

/** 只做动作选择；原生攻击和导航由端口执行。 */
public final class WarMeleePolicy {
    public enum Action { APPROACH, HOLD_DISTANCE, WAIT, STRIKE, BLOCKED }
    private WarMeleePolicy() {}
    public static Action decide(WarWeaponProfile.Melee profile, double distanceSquared, double targetWidth,
                                boolean lineOfSight, long now, long nextAttack) {
        Objects.requireNonNull(profile);
        if (!Double.isFinite(distanceSquared) || distanceSquared < 0 || !Double.isFinite(targetWidth) || targetWidth < 0 || now < 0 || nextAttack < 0)
            throw new IllegalArgumentException("invalid melee observation");
        if (distanceSquared < profile.minimumReach() * profile.minimumReach()) return Action.HOLD_DISTANCE;
        double reach = profile.reachBeforeTargetRadius() + targetWidth / 2;
        if (distanceSquared > reach * reach || !lineOfSight) return Action.APPROACH;
        if (now < nextAttack) return Action.WAIT;
        return now > Long.MAX_VALUE - profile.recoveryTicks() ? Action.BLOCKED : Action.STRIKE;
    }
}
