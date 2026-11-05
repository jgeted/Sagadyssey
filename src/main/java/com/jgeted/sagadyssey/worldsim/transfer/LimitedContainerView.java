package com.jgeted.sagadyssey.worldsim.transfer;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Restricts a larger physical container to the slots authorized for the current role/order. */
public final class LimitedContainerView implements Container {
    private final Container delegate;
    private final int size;

    public LimitedContainerView(Container delegate, int size) {
        if (delegate == null || size < 0) throw new IllegalArgumentException("invalid container view");
        this.delegate = delegate;
        this.size = Math.min(size, delegate.getContainerSize());
    }

    @Override public int getContainerSize() { return size; }
    @Override public boolean isEmpty() {
        for (int slot = 0; slot < size; slot++) if (!delegate.getItem(slot).isEmpty()) return false;
        return true;
    }
    @Override public ItemStack getItem(int slot) { return delegate.getItem(slot); }
    @Override public ItemStack removeItem(int slot, int amount) { return delegate.removeItem(slot, amount); }
    @Override public ItemStack removeItemNoUpdate(int slot) { return delegate.removeItemNoUpdate(slot); }
    @Override public void setItem(int slot, ItemStack stack) { delegate.setItem(slot, stack); }
    @Override public void setChanged() { delegate.setChanged(); }
    @Override public boolean stillValid(Player player) { return delegate.stillValid(player); }
    @Override public void clearContent() {
        for (int slot = 0; slot < size; slot++) delegate.setItem(slot, ItemStack.EMPTY);
        delegate.setChanged();
    }
}
