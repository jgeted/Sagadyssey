package com.jgeted.sagadyssey.social.production;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 世界级生产订单簿；活动订单绝不会因容量裁剪而被静默删除。 */
public final class ProductionOrderData extends NbtSavedData {
    public static final String DATA_NAME = "sagadyssey_production_orders_v3";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_ORDERS = 2_048;
    public static final Factory<ProductionOrderData> FACTORY =
            new Factory<>(ProductionOrderData::new, ProductionOrderData::load);

    private static final Comparator<ProductionOrder> ORDERING = Comparator
            .comparingLong(ProductionOrder::createdAt)
            .thenComparing(order -> order.id().toString());
    private static final Comparator<ProductionOrder> OLDEST_TERMINAL = Comparator
            .comparingLong(ProductionOrder::updatedAt)
            .thenComparingLong(ProductionOrder::createdAt)
            .thenComparing(order -> order.id().toString());

    private final Map<UUID, ProductionOrder> orders = new LinkedHashMap<>();

    public static ProductionOrderData get(MinecraftServer server) {
        if (server == null) throw new IllegalArgumentException("server is required");
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.type(DATA_NAME));
    }

    public synchronized List<ProductionOrder> openOrders() {
        return orders.values().stream()
                .filter(order -> order.status() == ProductionOrderStatus.OPEN)
                .sorted(ORDERING).toList();
    }

    public synchronized List<ProductionOrder> assignedTo(UUID workerId) {
        if (workerId == null) throw new IllegalArgumentException("workerId is required");
        return orders.values().stream()
                .filter(order -> !order.terminal() && workerId.equals(order.assignedWorkerId()))
                .sorted(ORDERING).toList();
    }

    /** 管理员审计视图：包含该工人的活动与已终结订单。 */
    public synchronized List<ProductionOrder> ordersForWorker(UUID workerId) {
        if (workerId == null) throw new IllegalArgumentException("workerId is required");
        return orders.values().stream()
                .filter(order -> workerId.equals(order.assignedWorkerId()))
                .sorted(ORDERING.reversed()).toList();
    }

    public synchronized Optional<ProductionOrder> byId(UUID orderId) {
        if (orderId == null) throw new IllegalArgumentException("orderId is required");
        return Optional.ofNullable(orders.get(orderId));
    }

    public synchronized void put(ProductionOrder order) {
        if (order == null) throw new IllegalArgumentException("order is required");
        ProductionOrder previous = orders.get(order.id());
        if (previous == null) {
            if (order.status() != ProductionOrderStatus.OPEN) {
                throw new IllegalStateException("new production order must start OPEN");
            }
            makeRoomForNewOrder();
        } else {
            validateUpdate(previous, order);
        }
        orders.put(order.id(), order);
        setDirty();
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Schema", SCHEMA_VERSION);
        ListTag list = new ListTag();
        orders.values().stream().sorted(ORDERING).forEach(order -> list.add(order.save()));
        tag.put("Orders", list);
        return tag;
    }

    static ProductionOrderData load(CompoundTag tag, HolderLookup.Provider registries) {
        ProductionOrderData data = new ProductionOrderData();
        if (tag == null || !MinecraftApi.containsTag(tag, "Schema", Tag.TAG_INT)
                || tag.getIntOr("Schema", 0) != SCHEMA_VERSION
                || !MinecraftApi.containsTag(tag, "Orders", Tag.TAG_LIST)) {
            return data;
        }
        ListTag list = MinecraftApi.listOfType(tag, "Orders", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            try {
                ProductionOrder order = ProductionOrder.load(list.getCompoundOrEmpty(index));
                if (data.orders.containsKey(order.id())) continue;
                data.addLoaded(order);
            } catch (RuntimeException ignored) {
                // 单条损坏订单不会阻止其余生产承诺恢复。
            }
        }
        return data;
    }

    int size() {
        return orders.size();
    }

    private void makeRoomForNewOrder() {
        if (orders.size() < MAX_ORDERS) return;
        ProductionOrder oldest = orders.values().stream().filter(ProductionOrder::terminal)
                .min(OLDEST_TERMINAL).orElseThrow(() -> new IllegalStateException(
                        "production order capacity reached with no terminal order to prune"));
        orders.remove(oldest.id());
    }

    private void addLoaded(ProductionOrder order) {
        if (orders.size() < MAX_ORDERS) {
            orders.put(order.id(), order);
            return;
        }
        ProductionOrder oldestStored = orders.values().stream()
                .filter(ProductionOrder::terminal).min(OLDEST_TERMINAL).orElse(null);
        if (order.terminal()
                && (oldestStored == null || OLDEST_TERMINAL.compare(order, oldestStored) <= 0)) {
            return;
        }
        if (oldestStored != null) {
            orders.remove(oldestStored.id());
            orders.put(order.id(), order);
        }
        // 超过上限且没有终态可淘汰属于损坏输入；保留先恢复的活动订单。
    }

    private static void validateUpdate(ProductionOrder previous, ProductionOrder next) {
        if (!previous.sameFixedIdentity(next)) {
            throw new IllegalStateException("production order fixed identity cannot change");
        }
        if (next.updatedAt() < previous.updatedAt()) {
            throw new IllegalStateException("production order time cannot move backwards");
        }
        validateAssignment(previous, next);
        validateWorkstation(previous, next);
        if (previous.status() == next.status()) {
            throw new IllegalStateException("production order update must change status");
        }
        if (previous.status() == ProductionOrderStatus.BLOCKED) {
            if (next.status() == ProductionOrderStatus.CANCELLED
                    || next.status() == ProductionOrderStatus.FAILED) {
                previous.status().requireTransitionTo(next.status());
                return;
            }
            if (next.status() != previous.blockedFromStatus()
                    || next.blockedFromStatus() != null || next.blocker() != null) {
                throw new IllegalStateException("blocked order must resume to its exact prior state");
            }
            return;
        }
        previous.status().requireTransitionTo(next.status());
        if (next.status() == ProductionOrderStatus.BLOCKED
                && next.blockedFromStatus() != previous.status()) {
            throw new IllegalStateException("blocked order must remember its prior state");
        }
    }

    private static void validateAssignment(ProductionOrder previous, ProductionOrder next) {
        if (previous.assignedWorkerId() != null
                && !previous.assignedWorkerId().equals(next.assignedWorkerId())) {
            throw new IllegalStateException("assigned worker cannot change");
        }
        if (previous.assignedWorkerId() == null && next.assignedWorkerId() != null
                && !(previous.status() == ProductionOrderStatus.OPEN
                && next.status() == ProductionOrderStatus.CLAIMED)) {
            throw new IllegalStateException("worker may only be assigned while claiming an open order");
        }
    }

    private static void validateWorkstation(ProductionOrder previous, ProductionOrder next) {
        if (previous.workstationPosition() != null
                && (!previous.workstationPosition().equals(next.workstationPosition())
                || !previous.workstationDimension().equals(next.workstationDimension()))) {
            throw new IllegalStateException("assigned workstation cannot change");
        }
        if (previous.workstationPosition() == null && next.workstationPosition() != null
                && !(previous.status() == ProductionOrderStatus.OPEN
                && next.status() == ProductionOrderStatus.CLAIMED)) {
            throw new IllegalStateException("workstation may only be assigned while claiming an open order");
        }
    }
}
