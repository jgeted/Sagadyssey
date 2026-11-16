package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** 单一物品及组件的物理空间上界。不是物权许可、预留或已经完成的运输。 */
public final class WarCargoCapacity {
    public record Estimate(long personal, long cargo) {
        public long total() { return Math.addExact(personal, cargo); }
    }
    private WarCargoCapacity() {}

    public static Estimate inspect(NpcBase npc, ItemStack sample, boolean includePersonal) {
        if (!(npc.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException("cargo inspection requires server thread");
        }
        long personal = includePersonal ? available(npc.getPersonalInventory(), sample, npc.getPersonalInventory().getContainerSize()) : 0;
        return new Estimate(personal, available(npc.getTransportInventory(), sample, npc.getTransportSlotLimit()));
    }

    public static long available(Container container, ItemStack sample, int permittedSlots) {
        if (sample == null || sample.isEmpty() || permittedSlots < 0 || permittedSlots > container.getContainerSize()) {
            throw new IllegalArgumentException("invalid capacity inspection");
        }
        long total = 0;
        int limit = Math.min(sample.getMaxStackSize(), container.getMaxStackSize(sample));
        for (int slot = 0; slot < permittedSlots; slot++) {
            if (!container.canPlaceItem(slot, sample)) continue;
            ItemStack present = container.getItem(slot);
            if (present.isEmpty()) total += limit;
            else if (ItemStack.isSameItemSameComponents(present, sample)) total += Math.max(0, limit - present.getCount());
        }
        return total;
    }
}
