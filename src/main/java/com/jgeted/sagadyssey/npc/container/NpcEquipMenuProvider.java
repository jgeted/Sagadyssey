package com.jgeted.sagadyssey.npc.container;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 服务端打开 NpcEquipMenu 的工厂。
 */
public class NpcEquipMenuProvider implements MenuProvider {
    private final NpcBase npc;

    public NpcEquipMenuProvider(NpcBase npc) {
        this.npc = npc;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal(npc.getNpcName());
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new NpcEquipMenu(containerId, inventory, npc);
    }
}
