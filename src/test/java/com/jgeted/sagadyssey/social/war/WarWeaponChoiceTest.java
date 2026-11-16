package com.jgeted.sagadyssey.social.war;
import com.jgeted.sagadyssey.social.war.api.WarAttackKind;
import com.jgeted.sagadyssey.social.war.application.WarWeaponChoice;
import org.junit.jupiter.api.Test;
import java.util.OptionalDouble;
import static org.junit.jupiter.api.Assertions.*;

class WarWeaponChoiceTest {
    @Test void hysteresisPreventsOscillationAroundTheCloseRangeBoundary() {
        var bow = WarAttackKind.BOW_SHOT;
        assertEquals(WarAttackKind.SWORD_STRIKE, WarWeaponChoice.choose(bow, false, OptionalDouble.of(15.9)));
        assertEquals(bow, WarWeaponChoice.choose(bow, false, OptionalDouble.of(16)));
        assertEquals(WarAttackKind.SWORD_STRIKE, WarWeaponChoice.choose(bow, true, OptionalDouble.of(25)));
        assertEquals(bow, WarWeaponChoice.choose(bow, true, OptionalDouble.of(36)));
    }
    @Test void losingContactRestoresThePrimaryRoleWithoutChangingInfantryOrSpearCapabilities() {
        for (var primary : WarAttackKind.values()) assertEquals(primary, WarWeaponChoice.choose(primary, true, OptionalDouble.empty()));
        assertEquals(WarAttackKind.SPEAR_JAB, WarWeaponChoice.choose(WarAttackKind.SPEAR_JAB, false, OptionalDouble.of(0)));
        assertThrows(IllegalArgumentException.class, () -> WarWeaponChoice.choose(WarAttackKind.CROSSBOW_SHOT, true, OptionalDouble.of(Double.NaN)));
    }
}
