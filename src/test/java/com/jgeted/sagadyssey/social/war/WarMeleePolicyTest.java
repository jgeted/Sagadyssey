package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarMeleePolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.jgeted.sagadyssey.social.war.application.WarMeleePolicy.Action.*;

class WarMeleePolicyTest {
    private final WarWeaponProfile.Melee sword = new WarWeaponProfile.Melee(WarAttackKind.SWORD_STRIKE, 0, 1.9, 20);
    @Test void rangeAndNativeVisibilityMustAllowAStrikeBeforeCooldownIsConsidered() {
        assertEquals(APPROACH, WarMeleePolicy.decide(sword, 25, .6, true, 100, 0));
        assertEquals(APPROACH, WarMeleePolicy.decide(sword, 1, .6, false, 100, 0));
        assertEquals(STRIKE, WarMeleePolicy.decide(sword, 4, .6, true, 100, 100));
        assertEquals(WAIT, WarMeleePolicy.decide(sword, 4, .6, true, 100, 101));
    }
    @Test void minimumReachHasAnExplicitHoldOutcomeRatherThanFakingAHit() {
        // 仅验证通用距离规则；不安装长矛原生实现。
        var range = new WarWeaponProfile.Melee(WarAttackKind.SPEAR_JAB, 2, 4, 30);
        assertEquals(HOLD_DISTANCE, WarMeleePolicy.decide(range, 1, .6, true, 100, 0));
        assertEquals(STRIKE, WarMeleePolicy.decide(range, 9, .6, true, 100, 0));
    }
    @Test void anExhaustedClockAndInvalidObservationsCannotScheduleAnAttack() {
        assertEquals(BLOCKED, WarMeleePolicy.decide(sword, 1, .6, true, Long.MAX_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> WarMeleePolicy.decide(sword, Double.NaN, .6, true, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> WarMeleePolicy.decide(sword, 1, -1, true, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> new WarWeaponProfile.Melee(WarAttackKind.SWORD_STRIKE, 3, 1, 20));
    }
}
