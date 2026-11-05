package com.jgeted.sagadyssey.social.life;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

/** 口粮价值与个人进食恢复值的唯一物品表。 */
public final class NpcFoodItems {
    private static final Map<Item, Integer> RATION_VALUES = new LinkedHashMap<>();
    private static final Map<Item, Integer> HUNGER_RESTORE = new LinkedHashMap<>();

    static {
        register(Items.COOKED_BEEF, 4, 480);
        register(Items.COOKED_PORKCHOP, 4, 480);
        register(Items.BREAD, 4, 400);
        register(Items.COOKED_CHICKEN, 3, 380);
        register(Items.BAKED_POTATO, 3, 340);
        register(Items.COOKED_MUTTON, 3, 380);
        register(Items.COOKED_RABBIT, 3, 360);
        register(Items.APPLE, 2, 260);
        register(Items.HONEY_BOTTLE, 2, 250);
        register(Items.CARROT, 2, 220);
        register(Items.SWEET_BERRIES, 1, 160);
        register(Items.DRIED_KELP, 1, 120);
        register(Items.POTATO, 1, 140);
        register(Items.BEETROOT, 1, 120);
        // Approved finite fish slice: raw cod stays emergency-only in the planned diet.
        register(Items.COD, 1, 160);
        // 小麦可参与聚落的抽象制粮，但 NPC 不会直接生吃小麦。
        RATION_VALUES.put(Items.WHEAT, 1);
    }

    private NpcFoodItems() { }

    private static void register(Item item, int rationValue, int hungerRestore) {
        RATION_VALUES.put(item, rationValue);
        HUNGER_RESTORE.put(item, hungerRestore);
    }

    public static Map<Item, Integer> rationValues() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(RATION_VALUES));
    }

    public static int rationValue(ItemStack stack) {
        return stack.isEmpty() ? 0 : RATION_VALUES.getOrDefault(stack.getItem(), 0);
    }

    public static int hungerRestore(ItemStack stack) {
        return stack.isEmpty() ? 0 : HUNGER_RESTORE.getOrDefault(stack.getItem(), 0);
    }

    /** 优先选择熟食；只有严重饥饿时才吃可能兼作农作物的低价值食物。 */
    public static int findFoodSlot(Container container, boolean emergency) {
        int fallback = -1;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            int restore = hungerRestore(stack);
            if (restore >= 250) return slot;
            if (emergency && restore > 0 && fallback < 0) fallback = slot;
        }
        return fallback;
    }

    /** The planned executor has no vessel-return transaction. Ordinary buffers use emergency=false. */
    public static boolean canConsumePlanned(ItemStack stack, boolean emergency) {
        int restore = hungerRestore(stack);
        return !stack.is(Items.HONEY_BOTTLE) && (restore >= 250 || emergency && restore > 0);
    }

    /** Keep the legacy finder unchanged: only the planned path uses this consumption contract. */
    public static int findPlannedFoodSlot(Container container, boolean emergency) {
        int fallback = -1;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (canConsumePlanned(stack, false)) return slot;
            if (fallback < 0 && canConsumePlanned(stack, emergency)) fallback = slot;
        }
        return fallback;
    }

    public static int edibleItems(Container container) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (hungerRestore(stack) > 0) count += stack.getCount();
        }
        return count;
    }
}
