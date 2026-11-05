package com.jgeted.sagadyssey.npc.handover;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Coarse categories keep supply rules useful without turning them into item-spawning recipes. */
public enum SupplyCategory {
    FOOD,
    FUEL,
    TOOL,
    AMMUNITION,
    MATERIAL;

    public boolean matches(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return switch (this) {
            case FOOD -> stack.has(DataComponents.FOOD);
            case FUEL -> stack.is(Items.COAL) || stack.is(Items.CHARCOAL)
                    || stack.is(Items.COAL_BLOCK) || stack.is(Items.BLAZE_ROD);
            case TOOL -> stack.getItem() instanceof DiggerItem;
            case AMMUNITION -> stack.getItem() instanceof ArrowItem;
            case MATERIAL -> !stack.has(DataComponents.FOOD)
                    && !(stack.getItem() instanceof DiggerItem)
                    && !(stack.getItem() instanceof ArrowItem);
        };
    }
}
