package com.jgeted.sagadyssey.social.production;

import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.WarehouseRecord;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/** 把一个物理仓储方块解析为全系统唯一的权利容器身份。 */
public final class ProductionContainerIds {
    private ProductionContainerIds() {
    }

    public static UUID resolve(MinecraftServer server, String dimension, BlockPos position) {
        if (server == null || dimension == null || dimension.isBlank() || position == null) {
            throw new IllegalArgumentException("production container locator is incomplete");
        }
        WarehouseRecord warehouse = LogisticsData.get(server).warehouseAt(dimension, position);
        if (warehouse != null) return warehouse.id();
        UUID physical = ContainerIds.block("physical_storage", dimension, position);
        var rights = com.jgeted.sagadyssey.worldsim.rights.RightsStateData.get(server);
        if (rights.context(physical) != null) return physical;
        // Preserve an already registered household/private asset identity rather than inventing
        // a second rights ledger for the same pantry. Existing physical ids retain precedence.
        for (String role : java.util.List.of("household_storage", "private_storage")) {
            UUID registered = ContainerIds.block(role, dimension, position);
            if (rights.context(registered) != null) return registered;
        }
        // Assigned storage already has a stable role before the first rights write.
        // Discovery must not create a parallel physical id before the deposit/meal path starts.
        if (com.jgeted.sagadyssey.social.household.HouseholdData.get(server).sharedStorageAssigned(dimension,position))
            return ContainerIds.block("household_storage",dimension,position);
        if (com.jgeted.sagadyssey.social.life.NpcLifeData.get(server).privateStorageAssigned(position,dimension))
            return ContainerIds.block("private_storage",dimension,position);
        return physical;
    }
}
