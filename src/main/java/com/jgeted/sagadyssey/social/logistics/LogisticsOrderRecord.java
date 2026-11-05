package com.jgeted.sagadyssey.social.logistics;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;
import javax.annotation.Nullable;

/** 运输需求。创建订单不会移动物品，必须由后续运输者实际取出并交付。 */
public record LogisticsOrderRecord(UUID id, UUID sourceWarehouseId, UUID destinationWarehouseId,
                                   String itemId, int requestedCount, int deliveredCount,
                                   LogisticsOrderStatus status, UUID createdByCharacter,
                                   long createdGameTime, @Nullable UUID assignedNpcId,
                                   int pickedUpCount, long lastProgressGameTime,
                                   int componentsHash, @Nullable UUID needRequestId) {
    public LogisticsOrderRecord(UUID id, UUID source, UUID destination, String item,
                                int requested, int delivered, LogisticsOrderStatus status,
                                UUID creator, long created, UUID assigned, int picked, long progress) {
        this(id, source, destination, item, requested, delivered, status, creator, created,
                assigned, picked, progress, 0, null);
    }
    public int remainingToPickUp() { return Math.max(0, requestedCount - pickedUpCount); }
    public int inTransitCount() { return Math.max(0, pickedUpCount - deliveredCount); }
    public int remainingToDeliver() { return Math.max(0, requestedCount - deliveredCount); }

    public LogisticsOrderRecord claimedBy(UUID npcId, long gameTime) {
        return new LogisticsOrderRecord(id, sourceWarehouseId, destinationWarehouseId, itemId,
                requestedCount, deliveredCount, LogisticsOrderStatus.IN_PROGRESS,
                createdByCharacter, createdGameTime, npcId, pickedUpCount, gameTime, componentsHash, needRequestId);
    }

    public LogisticsOrderRecord withPickup(int amount, long gameTime) {
        return new LogisticsOrderRecord(id, sourceWarehouseId, destinationWarehouseId, itemId,
                requestedCount, deliveredCount, LogisticsOrderStatus.IN_PROGRESS,
                createdByCharacter, createdGameTime, assignedNpcId,
                Math.min(requestedCount, pickedUpCount + Math.max(0, amount)), gameTime, componentsHash, needRequestId);
    }

    public LogisticsOrderRecord withDelivery(int amount, long gameTime) {
        int delivered = Math.min(requestedCount, deliveredCount + Math.max(0, amount));
        LogisticsOrderStatus nextStatus = delivered >= requestedCount
                ? LogisticsOrderStatus.DELIVERED : LogisticsOrderStatus.IN_PROGRESS;
        return new LogisticsOrderRecord(id, sourceWarehouseId, destinationWarehouseId, itemId,
                requestedCount, delivered, nextStatus, createdByCharacter, createdGameTime,
                nextStatus == LogisticsOrderStatus.DELIVERED ? null : assignedNpcId,
                pickedUpCount, gameTime, componentsHash, needRequestId);
    }

    /** 运输者死亡时，已取出的货物会掉在世界中，不计作已交付。 */
    public LogisticsOrderRecord releasedAfterLoss(long gameTime) {
        return new LogisticsOrderRecord(id, sourceWarehouseId, destinationWarehouseId, itemId,
                requestedCount, deliveredCount, LogisticsOrderStatus.PLANNED,
                createdByCharacter, createdGameTime, null, deliveredCount, gameTime, componentsHash, needRequestId);
    }

    public LogisticsOrderRecord cancelled(long gameTime) {
        return new LogisticsOrderRecord(id, sourceWarehouseId, destinationWarehouseId, itemId,
                requestedCount, deliveredCount, LogisticsOrderStatus.CANCELLED,
                createdByCharacter, createdGameTime, null, deliveredCount, gameTime, componentsHash, needRequestId);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Source", sourceWarehouseId);
        tag.putUUID("Destination", destinationWarehouseId);
        tag.putString("Item", itemId);
        tag.putInt("Requested", requestedCount);
        tag.putInt("Delivered", deliveredCount);
        tag.putString("Status", status.name());
        tag.putUUID("CreatedBy", createdByCharacter);
        tag.putLong("Created", createdGameTime);
        if (assignedNpcId != null) tag.putUUID("AssignedNpc", assignedNpcId);
        tag.putInt("PickedUp", pickedUpCount);
        tag.putLong("LastProgress", lastProgressGameTime);
        tag.putInt("Components", componentsHash);
        if (needRequestId != null) tag.putUUID("Need", needRequestId);
        return tag;
    }

    public static LogisticsOrderRecord load(CompoundTag tag) {
        return new LogisticsOrderRecord(tag.getUUID("Id"), tag.getUUID("Source"),
                tag.getUUID("Destination"), tag.getString("Item"),
                Math.max(1, tag.getInt("Requested")), Math.max(0, tag.getInt("Delivered")),
                LogisticsOrderStatus.byName(tag.getString("Status")), tag.getUUID("CreatedBy"),
                tag.getLong("Created"),
                tag.hasUUID("AssignedNpc") ? tag.getUUID("AssignedNpc") : null,
                Math.max(tag.getInt("Delivered"), tag.getInt("PickedUp")),
                tag.contains("LastProgress") ? tag.getLong("LastProgress") : tag.getLong("Created"),
                tag.getInt("Components"), tag.hasUUID("Need") ? tag.getUUID("Need") : null);
    }
}
