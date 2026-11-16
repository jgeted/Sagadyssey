package com.jgeted.sagadyssey.npc.inventory;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import java.util.List;

/** 原实体装备的容器视图；不复制物品。界面与统一转移服务共用同一槽位映射。 */
public final class NpcEquipmentInventory implements Container {
    public static final List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND);
    private final NpcBase npc;
    private final boolean enforceSlots;
    public NpcEquipmentInventory(NpcBase npc) { this(npc, false); }
    public NpcEquipmentInventory(NpcBase npc, boolean enforceSlots) { this.npc = java.util.Objects.requireNonNull(npc); this.enforceSlots = enforceSlots; }
    public int getContainerSize() { return SLOTS.size(); }
    public boolean isEmpty() { return SLOTS.stream().allMatch(slot -> npc.getItemBySlot(slot).isEmpty()); }
    public ItemStack getItem(int index) { return npc.getItemBySlot(SLOTS.get(index)); }
    public ItemStack removeItem(int index, int amount) {
        var current = getItem(index).copy(); if (current.isEmpty()) return ItemStack.EMPTY;
        var taken = current.split(amount); setItem(index, current.isEmpty() ? ItemStack.EMPTY : current); return taken;
    }
    public ItemStack removeItemNoUpdate(int index) { var current = getItem(index).copy(); setItem(index, ItemStack.EMPTY); return current; }
    public void setItem(int index, ItemStack stack) { npc.setItemSlot(SLOTS.get(index), stack); }
    public boolean canPlaceItem(int index, ItemStack stack) { return !enforceSlots || npc.getEquipmentSlotForItem(stack) == SLOTS.get(index); }
    public void setChanged() { npc.getPersonalInventory().setChanged(); }
    public boolean stillValid(Player player) { return npc.isAlive(); }
    public void clearContent() { SLOTS.forEach(slot -> npc.setItemSlot(slot, ItemStack.EMPTY)); }
}
