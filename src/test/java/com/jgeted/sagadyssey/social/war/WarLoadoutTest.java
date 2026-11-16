package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarLoadoutCatalog;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WarLoadoutTest {
    @Test void all48ConfigurationsHaveDistinctStableNamesAndKeys() {
        var catalog = WarLoadoutCatalog.initial();
        assertEquals(48, catalog.profiles().size());
        assertEquals(48, catalog.profiles().values().stream().map(WarLoadout::displayName).distinct().count());
        for (var profile : catalog.profiles().values()) {
            assertEquals(profile.id(), WarKey.parse(profile.id().toString()));
            assertEquals(profile, catalog.find(profile.id()).orElseThrow());
        }
        assertThrows(UnsupportedOperationException.class, () -> catalog.profiles().clear());
    }
    @Test void riderArmorAndHorseArmorAreIndependentAndRangedHorseProfilesNeedBackups() {
        var profile = new WarLoadout(WarLoadout.Mount.HEAVY, WarLoadout.Weapon.BOW, false, false);
        assertEquals("无盾无甲弓箭重骑兵", profile.displayName());
        assertTrue(profile.primaryWeapon().needsMeleeBackup());
        assertFalse(WarLoadout.Weapon.SPEAR.needsMeleeBackup());
    }
    @Test void legacyIdsResolveToPresetsWithoutRemovingOldDefinitions() {
        var catalog = WarLoadoutCatalog.initial();
        var shield = catalog.find(WarKey.parse("sagadyssey:war/troop/shield_infantry")).orElseThrow();
        assertTrue(shield.shield()); assertEquals(WarLoadout.Weapon.SWORD, shield.primaryWeapon());
        assertEquals(WarLoadout.Weapon.SPEAR, catalog.find(WarKey.parse("sagadyssey:war/troop/pike_infantry")).orElseThrow().primaryWeapon());
        assertTrue(catalog.find(WarKey.parse("missing:troop")).isEmpty());
    }
    @Test void drawingAMeleeBackupDoesNotChangeTheDeclaredPrimaryRole() {
        var planned = new WarLoadout(WarLoadout.Mount.FOOT, WarLoadout.Weapon.BOW, true, false);
        var observation = new WarEquipmentSnapshot(planned, Set.of(WarLoadout.Weapon.BOW, WarLoadout.Weapon.SWORD), true, false, false);
        assertTrue(observation.mismatches(planned).isEmpty()); assertEquals(WarLoadout.Weapon.BOW, observation.observed().primaryWeapon());
        var unprepared = new WarEquipmentSnapshot(planned, Set.of(WarLoadout.Weapon.BOW), false, false, true);
        assertEquals(Set.of(WarEquipmentSnapshot.Mismatch.MELEE_BACKUP), unprepared.mismatches(planned));
    }
    @Test void dismountAndMissingEquipmentAreReportedWithoutChangingTheTargetPreset() {
        var wanted = new WarLoadout(WarLoadout.Mount.HEAVY, WarLoadout.Weapon.CROSSBOW, true, true);
        var actual = new WarEquipmentSnapshot(new WarLoadout(WarLoadout.Mount.FOOT, WarLoadout.Weapon.CROSSBOW, false, false),
                Set.of(WarLoadout.Weapon.CROSSBOW), false, false, true);
        assertEquals(Set.of(WarEquipmentSnapshot.Mismatch.MOUNT, WarEquipmentSnapshot.Mismatch.SHIELD,
                WarEquipmentSnapshot.Mismatch.RIDER_ARMOR, WarEquipmentSnapshot.Mismatch.MELEE_BACKUP), actual.mismatches(wanted));
        assertEquals(WarLoadout.Mount.HEAVY, wanted.mount());
    }
}
