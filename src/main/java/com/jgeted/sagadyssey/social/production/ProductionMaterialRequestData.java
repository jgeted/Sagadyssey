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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/** 世界级生产缺料请求簿；容量压力只允许淘汰已经终结的请求。 */
public final class ProductionMaterialRequestData extends NbtSavedData {
    public static final String DATA_NAME = "sagadyssey_production_material_requests_v3";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_REQUESTS = 4_096;
    public static final Factory<ProductionMaterialRequestData> FACTORY =
            new Factory<>(ProductionMaterialRequestData::new,
                    ProductionMaterialRequestData::load);

    private static final Comparator<ProductionMaterialRequest> ORDERING = Comparator
            .comparingLong(ProductionMaterialRequest::createdAt)
            .thenComparing(ProductionMaterialRequest::idempotencyKey);
    private static final Comparator<ProductionMaterialRequest> OLDEST_TERMINAL = Comparator
            .comparingLong(ProductionMaterialRequest::updatedAt)
            .thenComparingLong(ProductionMaterialRequest::createdAt)
            .thenComparing(ProductionMaterialRequest::idempotencyKey);

    private final Map<String, ProductionMaterialRequest> requests = new LinkedHashMap<>();

    public static ProductionMaterialRequestData get(MinecraftServer server) {
        if (server == null) throw new IllegalArgumentException("server is required");
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.type(DATA_NAME));
    }

    /** 创建一张请求，或返回相同固定身份的既有请求。 */
    public synchronized ProductionMaterialRequest openOrGet(UUID orderId, UUID containerId,
                                                             ProductionMaterial material,
                                                             long now, String detail) {
        if (now < 0L) throw new IllegalArgumentException("request time cannot be negative");
        String key = ProductionMaterialRequest.keyFor(orderId, material);
        ProductionMaterialRequest existing = requests.get(key);
        if (existing != null) {
            ProductionMaterialRequest proposed = ProductionMaterialRequest.open(
                    orderId, containerId, material, existing.createdAt(), detail);
            if (!existing.sameFixedIdentity(proposed)) {
                throw new IllegalStateException("idempotency key conflicts with existing request identity");
            }
            return existing;
        }
        ProductionMaterialRequest request = ProductionMaterialRequest.open(
                orderId, containerId, material, now, detail);
        makeRoomForNewRequest();
        requests.put(request.idempotencyKey(), request);
        setDirty();
        return request;
    }

    public synchronized ProductionMaterialRequest fulfill(String idempotencyKey,
                                                          long now, String detail) {
        ProductionMaterialRequest current = requireRequest(idempotencyKey);
        ProductionMaterialRequest next = current.fulfill(now, detail);
        requests.put(idempotencyKey, next);
        setDirty();
        return next;
    }

    public synchronized ProductionMaterialRequest cancel(String idempotencyKey,
                                                         long now, String detail) {
        ProductionMaterialRequest current = requireRequest(idempotencyKey);
        ProductionMaterialRequest next = current.cancel(now, detail);
        requests.put(idempotencyKey, next);
        setDirty();
        return next;
    }

    public synchronized Optional<ProductionMaterialRequest> byKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        return Optional.ofNullable(requests.get(idempotencyKey));
    }

    public synchronized List<ProductionMaterialRequest> openRequests() {
        return requests.values().stream()
                .filter(request -> request.status() == ProductionMaterialRequest.Status.OPEN)
                .sorted(ORDERING).toList();
    }

    public synchronized List<ProductionMaterialRequest> forOrder(UUID orderId) {
        if (orderId == null) throw new IllegalArgumentException("orderId is required");
        return requests.values().stream().filter(request -> orderId.equals(request.orderId()))
                .sorted(ORDERING).toList();
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Schema", SCHEMA_VERSION);
        ListTag list = new ListTag();
        requests.values().stream().sorted(ORDERING)
                .forEach(request -> list.add(request.save()));
        tag.put("Requests", list);
        return tag;
    }

    static ProductionMaterialRequestData load(CompoundTag tag,
                                              HolderLookup.Provider registries) {
        ProductionMaterialRequestData data = new ProductionMaterialRequestData();
        if (tag == null || !MinecraftApi.containsTag(tag, "Schema", Tag.TAG_INT)
                || tag.getIntOr("Schema", 0) != SCHEMA_VERSION
                || !MinecraftApi.containsTag(tag, "Requests", Tag.TAG_LIST)) {
            return data;
        }
        ListTag list = MinecraftApi.listOfType(tag, "Requests", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            try {
                ProductionMaterialRequest request = ProductionMaterialRequest.load(
                        list.getCompoundOrEmpty(index));
                if (data.requests.containsKey(request.idempotencyKey())) continue;
                data.addLoaded(request);
            } catch (RuntimeException ignored) {
                // 单条坏记录不得阻止其他缺料请求恢复。
            }
        }
        return data;
    }

    int size() {
        return requests.size();
    }

    private ProductionMaterialRequest requireRequest(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        ProductionMaterialRequest request = requests.get(idempotencyKey);
        if (request == null) {
            throw new NoSuchElementException("unknown material request: " + idempotencyKey);
        }
        return request;
    }

    private void makeRoomForNewRequest() {
        if (requests.size() < MAX_REQUESTS) return;
        ProductionMaterialRequest oldest = requests.values().stream()
                .filter(ProductionMaterialRequest::terminal).min(OLDEST_TERMINAL)
                .orElseThrow(() -> new IllegalStateException(
                        "material request capacity reached with no terminal request to prune"));
        requests.remove(oldest.idempotencyKey());
    }

    private void addLoaded(ProductionMaterialRequest request) {
        if (requests.size() < MAX_REQUESTS) {
            requests.put(request.idempotencyKey(), request);
            return;
        }
        ProductionMaterialRequest oldestStored = requests.values().stream()
                .filter(ProductionMaterialRequest::terminal).min(OLDEST_TERMINAL).orElse(null);
        if (request.terminal() && (oldestStored == null
                || OLDEST_TERMINAL.compare(request, oldestStored) <= 0)) {
            return;
        }
        if (oldestStored != null) {
            requests.remove(oldestStored.idempotencyKey());
            requests.put(request.idempotencyKey(), request);
        }
        // 超限且没有终态可淘汰时保留先恢复的活动请求。
    }
}
