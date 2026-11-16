package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarRangedPolicy;
import com.jgeted.sagadyssey.social.war.application.WarRangedPolicy.Action;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarRangedPolicyTest {
    private static final WarWeaponProfile.Ranged BOW = new WarWeaponProfile.Ranged(WarAttackKind.BOW_SHOT, 4, 16, 20, 20);
    @Test void eachShotNeedsItsOwnDrawAndRecoveryInsteadOfCatchingUpAfterASkip() {
        assertEquals(Action.BEGIN, WarRangedPolicy.decide(BOW, 100, true, true, 10, 0, -1));
        assertEquals(Action.DRAW, WarRangedPolicy.decide(BOW, 100, true, true, 29, 0, 10));
        assertEquals(Action.FIRE, WarRangedPolicy.decide(BOW, 100, true, true, 30, 0, 10));
        assertEquals(Action.COOLDOWN, WarRangedPolicy.decide(BOW, 100, true, true, 49, 50, -1));
        assertEquals(Action.BEGIN, WarRangedPolicy.decide(BOW, 100, true, true, 50, 50, -1));
        assertEquals(Action.BEGIN, WarRangedPolicy.decide(BOW, 100, true, true, 100_000, 50, -1));
    }
    @Test void missingAmmoCloseContactAndObstructedLineOfSightCannotFire() {
        assertEquals(Action.NO_AMMO, WarRangedPolicy.decide(BOW, 100, true, false, 100, 0, 0));
        assertEquals(Action.NEED_BACKUP, WarRangedPolicy.decide(BOW, 9, true, true, 100, 0, 0));
        assertEquals(Action.OUT_OF_RANGE, WarRangedPolicy.decide(BOW, 100, false, true, 100, 0, 0));
        assertEquals(Action.OUT_OF_RANGE, WarRangedPolicy.decide(BOW, 257, true, true, 100, 0, 0));
        assertEquals(Action.BEGIN, WarRangedPolicy.decide(BOW, 100, true, true, 101, 0, -1));
    }
    @Test void crossbowHasASeparateLoadDurationAndRecovery() {
        var crossbow = new WarWeaponProfile.Ranged(WarAttackKind.CROSSBOW_SHOT, 4, 12, 25, 30);
        assertEquals(Action.DRAW, WarRangedPolicy.decide(crossbow, 100, true, true, 24, 0, 0));
        assertEquals(Action.FIRE, WarRangedPolicy.decide(crossbow, 100, true, true, 25, 0, 0));
        assertEquals(Action.COOLDOWN, WarRangedPolicy.decide(crossbow, 100, true, true, 54, 55, -1));
        assertEquals(Action.OUT_OF_RANGE, WarRangedPolicy.decide(crossbow, 145, true, true, 60, 0, 0));
    }
    @Test void futureOrOverflowingClocksBlockAndInvalidObservationsAreRejected() {
        assertEquals(Action.BLOCKED, WarRangedPolicy.decide(BOW, 100, true, true, 10, 0, 11));
        assertEquals(Action.BLOCKED, WarRangedPolicy.decide(BOW, 100, true, true, Long.MAX_VALUE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> WarRangedPolicy.decide(BOW, Double.NaN, true, true, 10, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> WarRangedPolicy.decide(BOW, 100, true, true, -1, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new WarWeaponProfile.Ranged(WarAttackKind.SPEAR_JAB, 4, 16, 20, 20));
        assertThrows(IllegalArgumentException.class, () -> new WarWeaponProfile.Ranged(WarAttackKind.BOW_SHOT, 4, 33, 20, 20));
    }
    @Test void aRangedProfileRequiresItsActualWeaponRoleAndOldProfilesRemainUsable() {
        assertThrows(IllegalArgumentException.class, () -> new WarWeaponProfile(Set.of(WarLoadout.Weapon.SWORD), true, false, false, Optional.empty(), Optional.of(BOW)));
        var old = new WarWeaponProfile(Set.of(WarLoadout.Weapon.SWORD), true, false, false, Optional.empty());
        assertTrue(old.ranged().isEmpty());
    }
    @Test void projectileProvenanceExpiresAndCannotBecomeValidBeforeItWasFired() {
        UUID id = UUID.randomUUID(); var shot = new WarShotPermit(id, id, id, id, id, id, id, id, id, false, 100);
        assertFalse(shot.currentAt(99)); assertTrue(shot.currentAt(100)); assertTrue(shot.currentAt(300)); assertFalse(shot.currentAt(301));
        assertFalse(shot.currentAt(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new WarShotPermit(id, id, id, id, id, id, id, id, id, false, -1));
        assertThrows(NullPointerException.class, () -> new WarShotPermit(id, id, null, id, id, id, id, id, id, false, 0));
    }
}
