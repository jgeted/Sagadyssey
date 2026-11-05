package com.jgeted.sagadyssey.npc.inventory;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * NPC 的随身物品栏。布局和玩家的 27 格主背包加 9 格快捷栏一致；
 * 武器、箭矢、食物和工作产物都使用同一份真实库存。
 */
public final class NpcPersonalInventory extends SimpleContainer {
    public static final int SIZE = 36;

    private long revision;

    public NpcPersonalInventory() {
        super(SIZE);
    }

    @Override
    public void setChanged() {
        super.setChanged();
        revision++;
    }

    public long revision() {
        return revision;
    }

    public void restoreRevision(long revision) {
        this.revision = Math.max(0L, revision);
    }

    /**
     * 插入一份物品并返回未能放入的余量。传入的堆不会被修改，便于事务预演。
     */
    public ItemStack insert(ItemStack offered) {
        if (offered == null || offered.isEmpty()) return ItemStack.EMPTY;
        ItemStack remaining = offered.copy();

        for (int slotIndex = 0; slotIndex < getContainerSize() && !remaining.isEmpty(); slotIndex++) {
            ItemStack slot = getItem(slotIndex);
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, remaining)) continue;
            int moved = Math.min(remaining.getCount(), slot.getMaxStackSize() - slot.getCount());
            if (moved <= 0) continue;
            slot.grow(moved);
            remaining.shrink(moved);
            setChanged();
        }

        for (int slotIndex = 0; slotIndex < getContainerSize() && !remaining.isEmpty(); slotIndex++) {
            if (!getItem(slotIndex).isEmpty()) continue;
            int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize());
            setItem(slotIndex, remaining.copyWithCount(moved));
            remaining.shrink(moved);
        }
        return remaining;
    }

    public boolean contains(Predicate<ItemStack> predicate) {
        for (int slotIndex = 0; slotIndex < getContainerSize(); slotIndex++) {
            ItemStack stack = getItem(slotIndex);
            if (!stack.isEmpty() && predicate.test(stack)) return true;
        }
        return false;
    }

    public ItemStack first(Predicate<ItemStack> predicate) {
        for (int slotIndex = 0; slotIndex < getContainerSize(); slotIndex++) {
            ItemStack stack = getItem(slotIndex);
            if (!stack.isEmpty() && predicate.test(stack)) return stack;
        }
        return ItemStack.EMPTY;
    }

    public int count(Predicate<ItemStack> predicate) {
        int total = 0;
        for (int slotIndex = 0; slotIndex < getContainerSize(); slotIndex++) {
            ItemStack stack = getItem(slotIndex);
            if (!stack.isEmpty() && predicate.test(stack)) total += stack.getCount();
        }
        return total;
    }

    /** 数量不足时不修改库存。 */
    public boolean consume(Predicate<ItemStack> predicate, int amount) {
        if (amount <= 0) return true;
        if (count(predicate) < amount) return false;
        int remaining = amount;
        for (int slotIndex = 0; slotIndex < getContainerSize() && remaining > 0; slotIndex++) {
            ItemStack stack = getItem(slotIndex);
            if (stack.isEmpty() || !predicate.test(stack)) continue;
            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            remaining -= taken;
            setChanged();
        }
        return true;
    }
}
