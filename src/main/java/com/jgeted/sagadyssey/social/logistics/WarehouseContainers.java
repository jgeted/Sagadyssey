package com.jgeted.sagadyssey.social.logistics;

import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import javax.annotation.Nullable;

/** 第一版只接受没有加工槽规则的纯储物容器，避免向熔炉结果槽等非法位置写入。 */
public final class WarehouseContainers {
    private WarehouseContainers() {}

    public static boolean isSupported(@Nullable BlockEntity blockEntity) {
        return blockEntity instanceof ChestBlockEntity
                || blockEntity instanceof BarrelBlockEntity
                || blockEntity instanceof ShulkerBoxBlockEntity;
    }

    @Nullable
    public static Container asContainer(@Nullable BlockEntity blockEntity) {
        return isSupported(blockEntity) && blockEntity instanceof Container container ? container : null;
    }
}
