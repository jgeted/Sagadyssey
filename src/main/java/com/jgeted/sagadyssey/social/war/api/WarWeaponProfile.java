package com.jgeted.sagadyssey.social.war.api;

import java.util.*;

/** 原生适配器观测得到的能力；不保存物品副本或另造伤害属性。 */
public record WarWeaponProfile(Set<WarLoadout.Weapon> weapons, boolean meleeBackup, boolean shield,
                               boolean armor, Optional<Melee> melee, Optional<Ranged> ranged) {
    public WarWeaponProfile(Set<WarLoadout.Weapon> weapons, boolean meleeBackup, boolean shield, boolean armor, Optional<Melee> melee) {
        this(weapons, meleeBackup, shield, armor, melee, Optional.empty());
    }
    public record Ranged(WarAttackKind kind, double preferredMinimumReach, double maximumReach, long drawTicks, long recoveryTicks) {
        public Ranged {
            Objects.requireNonNull(kind);
            if (kind != WarAttackKind.BOW_SHOT && kind != WarAttackKind.CROSSBOW_SHOT || !Double.isFinite(preferredMinimumReach)
                    || !Double.isFinite(maximumReach) || preferredMinimumReach < 0 || maximumReach <= preferredMinimumReach || maximumReach > 32
                    || drawTicks < 1 || drawTicks > 2400 || recoveryTicks < 1 || recoveryTicks > 2400) throw new IllegalArgumentException("invalid ranged capability");
        }
    }
    public record Melee(WarAttackKind kind, double minimumReach, double reachBeforeTargetRadius, long recoveryTicks) {
        public Melee {
            Objects.requireNonNull(kind);
            if (!Double.isFinite(minimumReach) || !Double.isFinite(reachBeforeTargetRadius) || minimumReach < 0
                    || reachBeforeTargetRadius < minimumReach || reachBeforeTargetRadius > 64 || recoveryTicks < 1 || recoveryTicks > 2400)
                throw new IllegalArgumentException("invalid melee capability");
        }
    }
    public WarWeaponProfile {
        weapons = Set.copyOf(weapons); Objects.requireNonNull(melee); Objects.requireNonNull(ranged);
        if (melee.isPresent() && !weapons.contains(melee.get().kind().weapon())) throw new IllegalArgumentException("melee capability lacks weapon classification");
        if (ranged.isPresent() && !weapons.contains(ranged.get().kind().weapon())) throw new IllegalArgumentException("ranged capability lacks weapon classification");
    }
}
