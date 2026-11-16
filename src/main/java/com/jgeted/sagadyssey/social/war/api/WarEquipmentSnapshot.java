package com.jgeted.sagadyssey.social.war.api;

import java.util.*;

/** 一次只读装备观测；主武器定位由编制提供，临时抽剑不会把弓手改成剑士。 */
public record WarEquipmentSnapshot(WarLoadout observed, Set<WarLoadout.Weapon> availableWeapons,
                                   boolean meleeBackup, boolean blocking, boolean usingRangedWeapon) {
    public enum Mismatch { MOUNT, PRIMARY_WEAPON, SHIELD, RIDER_ARMOR, MELEE_BACKUP }
    public WarEquipmentSnapshot {
        Objects.requireNonNull(observed); availableWeapons = Set.copyOf(availableWeapons);
    }
    public Set<Mismatch> mismatches(WarLoadout expected) {
        Objects.requireNonNull(expected);
        Set<Mismatch> result = EnumSet.noneOf(Mismatch.class);
        if (expected.mount() != observed.mount()) result.add(Mismatch.MOUNT);
        if (!availableWeapons.contains(expected.primaryWeapon())) result.add(Mismatch.PRIMARY_WEAPON);
        if (expected.shield() != observed.shield()) result.add(Mismatch.SHIELD);
        if (expected.riderArmor() != observed.riderArmor()) result.add(Mismatch.RIDER_ARMOR);
        if (expected.primaryWeapon().needsMeleeBackup() && !meleeBackup) result.add(Mismatch.MELEE_BACKUP);
        return Set.copyOf(result);
    }
}
