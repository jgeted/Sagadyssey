package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.api.*;
import java.util.OptionalDouble;

/** 近身自卫切剑，敌人拉开后回主武器；不同阈值避免在边界反复换装。 */
public final class WarWeaponChoice {
    private WarWeaponChoice() {}
    public static WarAttackKind choose(WarAttackKind primary, boolean backupHeld, OptionalDouble targetDistanceSquared) {
        if (primary != WarAttackKind.BOW_SHOT && primary != WarAttackKind.CROSSBOW_SHOT) return primary;
        if (targetDistanceSquared.isEmpty()) return primary;
        double distance = targetDistanceSquared.getAsDouble();
        if (!Double.isFinite(distance) || distance < 0) throw new IllegalArgumentException("invalid weapon choice observation");
        return distance < (backupHeld ? 36 : 16) ? WarAttackKind.SWORD_STRIKE : primary;
    }
}
