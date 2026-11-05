package com.jgeted.sagadyssey.npc.work;

import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;

/**
 * 加工设备容器入口。与仓库容器分开，避免仓储的安全白名单误伤熔炉等有槽位规则的设备。
 */
public final class NpcWorkstationContainers {
    private NpcWorkstationContainers() { }

    /** 熔炉和高炉都继承 AbstractFurnaceBlockEntity，并提供输入、燃料、输出三个槽位。 */
    @Nullable
    public static Container asFurnace(@Nullable BlockEntity blockEntity) {
        return blockEntity instanceof AbstractFurnaceBlockEntity furnace ? furnace : null;
    }
}
