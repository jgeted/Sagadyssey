package com.jgeted.sagadyssey.worldsim.rights;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemFingerprintTest {
    @Test void unmodifiedStacksKeepLegacyZeroHash() {
        assertEquals(0, ItemFingerprint.of(new ItemStack(Items.IRON_PICKAXE)).componentsHash());
    }
    @Test void damagedToolUsesPersistedValueNotComponentIdentity() {
        ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
        tool.setDamageValue(1);
        assertEquals(("persistent-components-v1:{\"minecraft:damage\":1}").hashCode(),
                ItemFingerprint.of(tool).componentsHash());
        assertTrue(ItemFingerprint.load(ItemFingerprint.of(tool).save()).matches(tool.copy()));
    }
    @Test void insertionOrderDoesNotAffectIdentity() {
        ItemStack first = new ItemStack(Items.IRON_PICKAXE);
        ItemStack second = new ItemStack(Items.IRON_PICKAXE);
        first.setDamageValue(2); first.set(DataComponents.CUSTOM_NAME, Component.literal("test"));
        second.set(DataComponents.CUSTOM_NAME, Component.literal("test")); second.setDamageValue(2);
        assertEquals(ItemFingerprint.of(first), ItemFingerprint.of(second));
    }
    @Test void removalAndDamageChangesRemainDistinct() {
        ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
        var original = ItemFingerprint.of(tool);
        tool.remove(DataComponents.DAMAGE);
        assertNotEquals(original, ItemFingerprint.of(tool));
        tool.setDamageValue(3);
        assertFalse(original.matches(tool));
    }
}
