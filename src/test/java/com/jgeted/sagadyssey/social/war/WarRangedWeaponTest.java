package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarNativeBindings;
import com.jgeted.sagadyssey.social.war.api.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ChargedProjectiles;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarRangedWeaponTest {
    @Test void vanillaWeaponsExposeSeparateRangedProfiles() {
        var items = WarNativeBindings.currentBuild().items();
        var bow = items.inspect(new ItemStack(Items.BOW)).ranged().orElseThrow();
        var crossbow = items.inspect(new ItemStack(Items.CROSSBOW)).ranged().orElseThrow();
        assertEquals(WarAttackKind.BOW_SHOT, bow.kind()); assertEquals(40, bow.drawTicks() + bow.recoveryTicks());
        assertEquals(WarAttackKind.CROSSBOW_SHOT, crossbow.kind()); assertEquals(55, crossbow.drawTicks() + crossbow.recoveryTicks());
        assertTrue(items.inspect(new ItemStack(Items.IRON_SWORD)).ranged().isEmpty());
        assertTrue(items.inspect(ItemStack.EMPTY).ranged().isEmpty());
    }
    @Test void anAlreadyLoadedCrossbowIsClassifiedButNotAlteredOrClaimedRunnable() {
        var crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStack(Items.ARROW)));
        var before = crossbow.copy(); var inspected = WarNativeBindings.currentBuild().items().inspect(crossbow);
        assertTrue(inspected.weapons().contains(WarLoadout.Weapon.CROSSBOW)); assertTrue(inspected.ranged().isEmpty());
        assertTrue(ItemStack.matches(before, crossbow)); assertTrue(CrossbowItem.isCharged(crossbow));
    }
}
