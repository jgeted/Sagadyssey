package com.jgeted.sagadyssey.social.logistics;

import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import com.jgeted.sagadyssey.social.work.NpcWorkContext;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.core.registries.BuiltInRegistries;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 仓库索引和运输订单；库存数量永远从实际容器即时读取。 */
public final class LogisticsData extends SavedData {
    public static final int MAX_ORDER_ITEMS = 1_728;
    public static final int MAX_OPEN_ORDERS_PER_CHARACTER = 64;
    public static final int MAX_WAREHOUSES_PER_SETTLEMENT = 64;
    private static final int MAX_RETAINED_ORDERS = 1_024;
    private static final long STALE_EMPTY_ASSIGNMENT_TICKS = 6_000L;
    private static final String DATA_NAME = "sagadyssey_logistics";
    private static final Factory<LogisticsData> FACTORY =
            new Factory<>(LogisticsData::new, LogisticsData::load);

    private final Map<UUID, WarehouseRecord> warehouses = new HashMap<>();
    private final Map<UUID, LogisticsOrderRecord> orders = new HashMap<>();

    public static LogisticsData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public List<WarehouseRecord> warehouses() {
        return warehouses.values().stream().sorted(Comparator.comparing(WarehouseRecord::name)).toList();
    }

    public List<LogisticsOrderRecord> orders() {
        return orders.values().stream()
                .sorted(Comparator.comparingLong(LogisticsOrderRecord::createdGameTime).reversed()).toList();
    }

    @Nullable public WarehouseRecord warehouse(UUID id) { return warehouses.get(id); }

    @Nullable
    public WarehouseRecord warehouseAt(String dimension, BlockPos position) {
        return warehouses.values().stream()
                .filter(record -> record.dimension().equals(dimension) && record.position().equals(position))
                .findFirst().orElse(null);
    }

    public long warehouseCount(UUID settlementId) {
        return warehouses.values().stream().filter(w -> settlementId.equals(w.settlementId())).count();
    }

    public WarehouseRecord registerWarehouse(MinecraftServer server, UUID settlementId, String name,
                                             String dimension, BlockPos pos, UUID characterId,
                                             long gameTime) {
        WarehouseRecord existing = warehouses.values().stream()
                .filter(record -> record.dimension().equals(dimension) && record.position().equals(pos))
                .findFirst().orElse(null);
        if (existing != null) return existing;
        WarehouseRecord record = new WarehouseRecord(
                ContainerIds.block("physical_storage", dimension, pos), settlementId,
                WarehouseRecord.cleanName(name), dimension, pos.immutable(), characterId, gameTime);
        warehouses.put(record.id(), record);
        setDirty();
        WorldHistoryData.get(server).append(gameTime, "warehouse_registered", settlementId,
                record.name(), record.id().toString(), 15);
        return record;
    }

    @Nullable
    public LogisticsOrderRecord createOrder(UUID sourceId, UUID destinationId, String itemId,
                                            int count, UUID characterId, long gameTime) {
        if (!warehouses.containsKey(sourceId) || !warehouses.containsKey(destinationId)
                || sourceId.equals(destinationId) || count <= 0 || count > MAX_ORDER_ITEMS) return null;
        long openOrders = orders.values().stream()
                .filter(order -> characterId.equals(order.createdByCharacter()))
                .filter(order -> order.status() == LogisticsOrderStatus.PLANNED
                        || order.status() == LogisticsOrderStatus.IN_PROGRESS)
                .count();
        if (openOrders >= MAX_OPEN_ORDERS_PER_CHARACTER) return null;
        WarehouseRecord source = warehouses.get(sourceId);
        WarehouseRecord destination = warehouses.get(destinationId);
        if (!source.dimension().equals(destination.dimension())) return null;
        ResourceLocation item = ResourceLocation.tryParse(itemId);
        if (item == null || !BuiltInRegistries.ITEM.containsKey(item)) return null;
        LogisticsOrderRecord record = new LogisticsOrderRecord(UUID.randomUUID(), sourceId, destinationId,
                item.toString(), count, 0, LogisticsOrderStatus.PLANNED, characterId, gameTime,
                null, 0, gameTime);
        orders.put(record.id(), record);
        pruneClosedOrders();
        setDirty();
        return record;
    }

    @Nullable
    public LogisticsOrderRecord order(UUID orderId) { return orders.get(orderId); }

    /** Phase-six endpoints and custody are managed by SupplyFlowData, not legacy warehouse goals. */
    public LogisticsOrderRecord createManagedOrder(UUID id, UUID source, UUID destination,
                                                   com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint item,
                                                   int quantity, UUID creator, UUID need, long now) {
        LogisticsOrderRecord existing = orders.get(id);
        if (existing != null) {
            if (!existing.sourceWarehouseId().equals(source)
                    || !existing.destinationWarehouseId().equals(destination)
                    || !existing.itemId().equals(item.itemId())
                    || existing.componentsHash() != item.componentsHash()
                    || existing.requestedCount() != quantity
                    || !java.util.Objects.equals(existing.createdByCharacter(), creator)
                    || !java.util.Objects.equals(existing.needRequestId(), need))
                throw new IllegalStateException("managed transport order identity conflict");
            return existing;
        }
        if (source.equals(destination) || quantity < 1 || quantity > MAX_ORDER_ITEMS || need == null)
            throw new IllegalArgumentException("invalid managed transport order");
        var order = new LogisticsOrderRecord(id, source, destination, item.itemId(), quantity, 0,
                LogisticsOrderStatus.PLANNED, creator, now, null, 0, now, item.componentsHash(), need);
        orders.put(id, order); setDirty(); return order;
    }
    public void claimManagedOrder(UUID id, UUID carrier, long now) {
        var order = orders.get(id);
        if (order == null || order.needRequestId() == null || order.status() == LogisticsOrderStatus.DELIVERED
                || order.status() == LogisticsOrderStatus.CANCELLED
                || order.assignedNpcId() != null && !order.assignedNpcId().equals(carrier))
            throw new IllegalStateException("managed transport claim conflict");
        orders.put(id, order.claimedBy(carrier, now)); setDirty();
    }

    /** 防止自动经济在上一批货物仍在路上时重复创建相同线路。 */
    public boolean hasOpenOrder(UUID sourceId, UUID destinationId, String itemId) {
        return orders.values().stream()
                .anyMatch(order -> order.sourceWarehouseId().equals(sourceId)
                        && order.destinationWarehouseId().equals(destinationId)
                        && order.itemId().equals(itemId)
                        && (order.status() == LogisticsOrderStatus.PLANNED
                        || order.status() == LogisticsOrderStatus.IN_PROGRESS));
    }

    /** 同一角色麾下的运输者领取最早订单；不跨维度，也不强制加载仓库区块。 */
    @Nullable
    public LogisticsOrderRecord claimNextOrder(UUID npcId, NpcWorkContext workContext,
                                               String currentDimension, long gameTime) {
        releaseStaleEmptyAssignments(gameTime);
        LogisticsOrderRecord existing = orders.values().stream()
                .filter(order -> order.needRequestId() == null)
                .filter(order -> npcId.equals(order.assignedNpcId()))
                .filter(order -> order.status() == LogisticsOrderStatus.IN_PROGRESS)
                .findFirst().orElse(null);
        if (existing != null) return existing;

        LogisticsOrderRecord selected = orders.values().stream()
                .filter(order -> order.needRequestId() == null)
                .filter(order -> order.status() == LogisticsOrderStatus.PLANNED)
                .filter(order -> workContext.canClaimOrder(this, order))
                .filter(order -> {
                    WarehouseRecord source = warehouses.get(order.sourceWarehouseId());
                    WarehouseRecord destination = warehouses.get(order.destinationWarehouseId());
                    return source != null && destination != null
                            && source.dimension().equals(currentDimension)
                            && destination.dimension().equals(currentDimension);
                })
                .min(Comparator.comparingLong(LogisticsOrderRecord::createdGameTime))
                .orElse(null);
        if (selected == null) return null;
        LogisticsOrderRecord claimed = selected.claimedBy(npcId, gameTime);
        orders.put(claimed.id(), claimed);
        setDirty();
        return claimed;
    }

    /** 只有完全没有在途货物的失联任务才会重排；实体货物永远不会被账本“回滚”。 */
    private void releaseStaleEmptyAssignments(long gameTime) {
        boolean changed = false;
        for (var entry : List.copyOf(orders.entrySet())) {
            LogisticsOrderRecord order = entry.getValue();
            if (order.needRequestId() != null) continue;
            if (order.status() != LogisticsOrderStatus.IN_PROGRESS || order.inTransitCount() > 0
                    || gameTime - order.lastProgressGameTime() < STALE_EMPTY_ASSIGNMENT_TICKS) continue;
            orders.put(entry.getKey(), order.releasedAfterLoss(gameTime));
            changed = true;
        }
        if (changed) setDirty();
    }

    /** 避免长期服务器的已完成订单无限增长；等待/运输中的订单从不清理。 */
    private void pruneClosedOrders() {
        int excess = orders.size() - MAX_RETAINED_ORDERS;
        if (excess <= 0) return;
        orders.values().stream()
                .filter(order -> order.status() == LogisticsOrderStatus.DELIVERED
                        || order.status() == LogisticsOrderStatus.CANCELLED)
                .sorted(Comparator.comparingLong(LogisticsOrderRecord::lastProgressGameTime))
                .limit(excess)
                .map(LogisticsOrderRecord::id)
                .toList()
                .forEach(orders::remove);
    }

    public LogisticsOrderRecord recordPickup(UUID orderId, UUID npcId, int amount, long gameTime) {
        LogisticsOrderRecord order = orders.get(orderId);
        if (order == null || !npcId.equals(order.assignedNpcId()) || amount <= 0) return order;
        LogisticsOrderRecord updated = order.withPickup(amount, gameTime);
        orders.put(orderId, updated);
        setDirty();
        return updated;
    }

    /** 长途空载前往取货点时写入低频心跳，避免被误判成失联任务。 */
    public void touchOrder(UUID orderId, UUID npcId, long gameTime) {
        LogisticsOrderRecord order = orders.get(orderId);
        if (order == null || order.status() != LogisticsOrderStatus.IN_PROGRESS
                || !npcId.equals(order.assignedNpcId())
                || gameTime - order.lastProgressGameTime() < 200L) return;
        orders.put(orderId, order.claimedBy(npcId, gameTime));
        setDirty();
    }

    public LogisticsOrderRecord recordDelivery(MinecraftServer server, UUID orderId, UUID npcId,
                                               int amount, long gameTime) {
        LogisticsOrderRecord order = orders.get(orderId);
        if (order == null || !npcId.equals(order.assignedNpcId()) || amount <= 0) return order;
        LogisticsOrderRecord updated = order.withDelivery(amount, gameTime);
        orders.put(orderId, updated);
        setDirty();
        if (updated.status() == LogisticsOrderStatus.DELIVERED) {
            WorldHistoryData.get(server).append(gameTime, "logistics_delivered", updated.id(),
                    updated.itemId(), Integer.toString(updated.requestedCount()), 12);
        }
        return updated;
    }

    public void releaseOrdersForNpc(UUID npcId, long gameTime) {
        boolean changed = false;
        for (var entry : List.copyOf(orders.entrySet())) {
            LogisticsOrderRecord order = entry.getValue();
            if (order.needRequestId() != null) continue;
            if (!npcId.equals(order.assignedNpcId()) || order.status() != LogisticsOrderStatus.IN_PROGRESS) continue;
            orders.put(entry.getKey(), order.releasedAfterLoss(gameTime));
            changed = true;
        }
        if (changed) setDirty();
    }

    /** 只有订单创建者且没有在途货物时可以取消，保证取消不会吞掉实体物资。 */
    public boolean cancelOrder(UUID orderId, UUID requesterCharacterId, long gameTime) {
        LogisticsOrderRecord order = orders.get(orderId);
        if (order == null || order.needRequestId() != null || !requesterCharacterId.equals(order.createdByCharacter())) return false;
        if (order.status() == LogisticsOrderStatus.DELIVERED
                || order.status() == LogisticsOrderStatus.CANCELLED
                || order.inTransitCount() > 0) return false;
        orders.put(orderId, order.cancelled(gameTime));
        setDirty();
        return true;
    }

    /** Managed demands cancel only through the supply service after releasing actual leases. */
    public boolean cancelManagedOrder(UUID orderId, UUID needId, long gameTime) {
        LogisticsOrderRecord order = orders.get(orderId);
        if (order == null || !needId.equals(order.needRequestId()) || order.inTransitCount() > 0
                || order.status() == LogisticsOrderStatus.DELIVERED
                || order.status() == LogisticsOrderStatus.CANCELLED) return false;
        orders.put(orderId, order.cancelled(gameTime));
        setDirty();
        return true;
    }

    /** 管理员重置固定实验场时，作废该实验场签发的未完成订单。 */
    public int cancelOpenOrdersCreatedBy(UUID issuerId, long gameTime) {
        int cancelled = 0;
        for (var entry : List.copyOf(orders.entrySet())) {
            LogisticsOrderRecord order = entry.getValue();
            if (order.needRequestId() != null) continue;
            if (!issuerId.equals(order.createdByCharacter())
                    || order.status() == LogisticsOrderStatus.DELIVERED
                    || order.status() == LogisticsOrderStatus.CANCELLED) continue;
            orders.put(entry.getKey(), order.cancelled(gameTime));
            cancelled++;
        }
        if (cancelled > 0) setDirty();
        return cancelled;
    }

    public StockSnapshot stockForSettlement(MinecraftServer server, UUID settlementId) {
        Map<String, Integer> counts = new HashMap<>();
        int readable = 0;
        int unavailable = 0;
        for (WarehouseRecord warehouse : warehouses.values()) {
            if (!settlementId.equals(warehouse.settlementId())) continue;
            ServerLevel level = level(server, warehouse.dimension());
            if (level == null || !level.hasChunkAt(warehouse.position())) {
                unavailable++;
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(warehouse.position());
            Container container = WarehouseContainers.asContainer(blockEntity);
            if (container == null) {
                unavailable++;
                continue;
            }
            readable++;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) continue;
                String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                counts.merge(itemId, stack.getCount(), Integer::sum);
            }
        }
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return new StockSnapshot(Map.copyOf(sorted), readable, unavailable);
    }

    @Nullable
    private static ServerLevel level(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        if (id == null) return null;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        return server.getLevel(key);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag warehouseList = new ListTag();
        warehouses.values().forEach(record -> warehouseList.add(record.save()));
        tag.put("Warehouses", warehouseList);
        ListTag orderList = new ListTag();
        orders.values().forEach(record -> orderList.add(record.save()));
        tag.put("Orders", orderList);
        return tag;
    }

    private static LogisticsData load(CompoundTag tag, HolderLookup.Provider registries) {
        LogisticsData data = new LogisticsData();
        ListTag warehouseList = tag.getList("Warehouses", Tag.TAG_COMPOUND);
        for (int i = 0; i < warehouseList.size(); i++) {
            WarehouseRecord record = WarehouseRecord.load(warehouseList.getCompound(i));
            data.warehouses.put(record.id(), record);
        }
        ListTag orderList = tag.getList("Orders", Tag.TAG_COMPOUND);
        for (int i = 0; i < orderList.size(); i++) {
            LogisticsOrderRecord record = LogisticsOrderRecord.load(orderList.getCompound(i));
            data.orders.put(record.id(), record);
        }
        return data;
    }
}
