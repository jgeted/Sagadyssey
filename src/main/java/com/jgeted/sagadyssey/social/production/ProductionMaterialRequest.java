package com.jgeted.sagadyssey.social.production;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * 一张生产订单对某种精确物品的持久缺料请求。
 *
 * <p>幂等键不包含数量：同一订单对同一物品指纹只能存在一张请求，避免每次重试都
 * 追加重复需求。容器和数量属于创建后不可改的请求身份。</p>
 */
public record ProductionMaterialRequest(String idempotencyKey, UUID orderId,
                                        UUID containerId, ProductionMaterial material,
                                        Status status, long createdAt, long updatedAt,
                                        String detail) {
    private static final int MAX_ITEM_ID_LENGTH = 256;
    private static final int MAX_DETAIL_LENGTH = 2_048;

    public ProductionMaterialRequest {
        if (orderId == null || containerId == null || material == null || status == null) {
            throw new IllegalArgumentException("material request identity is incomplete");
        }
        String itemId = material.fingerprint().itemId();
        if (itemId.length() > MAX_ITEM_ID_LENGTH || Identifier.tryParse(itemId) == null) {
            throw new IllegalArgumentException("invalid material item id");
        }
        String expectedKey = keyFor(orderId, material);
        if (!expectedKey.equals(idempotencyKey)) {
            throw new IllegalArgumentException("material request idempotency key does not match identity");
        }
        if (createdAt < 0L || updatedAt < createdAt) {
            throw new IllegalArgumentException("invalid material request timestamps");
        }
        detail = truncate(detail == null ? "" : detail, MAX_DETAIL_LENGTH);
    }

    public enum Status {
        OPEN,
        FULFILLED,
        CANCELLED;

        public boolean terminal() {
            return this != OPEN;
        }
    }

    public static ProductionMaterialRequest open(UUID orderId, UUID containerId,
                                                 ProductionMaterial material,
                                                 long now, String detail) {
        return new ProductionMaterialRequest(keyFor(orderId, material), orderId,
                containerId, material, Status.OPEN, now, now, detail);
    }

    public static String keyFor(UUID orderId, ProductionMaterial material) {
        if (orderId == null || material == null) {
            throw new IllegalArgumentException("orderId and material are required");
        }
        return "production-order:" + orderId + ":"
                + material.fingerprint().itemId() + ":"
                + material.fingerprint().componentsHash();
    }

    public boolean terminal() {
        return status.terminal();
    }

    public ProductionMaterialRequest fulfill(long now, String nextDetail) {
        return transition(Status.FULFILLED, now, nextDetail);
    }

    public ProductionMaterialRequest cancel(long now, String nextDetail) {
        return transition(Status.CANCELLED, now, nextDetail);
    }

    boolean sameFixedIdentity(ProductionMaterialRequest other) {
        return other != null
                && idempotencyKey.equals(other.idempotencyKey)
                && orderId.equals(other.orderId)
                && containerId.equals(other.containerId)
                && material.equals(other.material)
                && createdAt == other.createdAt;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Key", idempotencyKey);
        tag.store("OrderId", UUIDUtil.CODEC, orderId);
        tag.store("ContainerId", UUIDUtil.CODEC, containerId);
        tag.put("Material", material.save());
        tag.putString("Status", status.name());
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("UpdatedAt", updatedAt);
        tag.putString("Detail", detail);
        return tag;
    }

    static ProductionMaterialRequest load(CompoundTag tag) {
        requireString(tag, "Key");
        requireUuid(tag, "OrderId");
        requireUuid(tag, "ContainerId");
        if (!MinecraftApi.containsTag(tag, "Material", Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("missing material");
        }
        requireString(tag, "Status");
        requireLong(tag, "CreatedAt");
        requireLong(tag, "UpdatedAt");
        requireString(tag, "Detail");
        Status loadedStatus;
        try {
            loadedStatus = Status.valueOf(tag.getStringOr("Status", ""));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid material request status", exception);
        }
        return new ProductionMaterialRequest(tag.getStringOr("Key", ""), tag.read("OrderId", UUIDUtil.CODEC).orElseThrow(),
                tag.read("ContainerId", UUIDUtil.CODEC).orElseThrow(),
                ProductionMaterial.load(tag.getCompoundOrEmpty("Material")), loadedStatus,
                tag.getLongOr("CreatedAt", 0L), tag.getLongOr("UpdatedAt", 0L),
                tag.getStringOr("Detail", ""));
    }

    private ProductionMaterialRequest transition(Status next, long now, String nextDetail) {
        if (status != Status.OPEN) {
            throw new IllegalStateException("terminal material request cannot transition again");
        }
        if (next == Status.OPEN) {
            throw new IllegalArgumentException("material request must transition to a terminal state");
        }
        if (now < updatedAt) {
            throw new IllegalArgumentException("material request time cannot move backwards");
        }
        return new ProductionMaterialRequest(idempotencyKey, orderId, containerId,
                material, next, createdAt, now, nextDetail);
    }

    private static void requireUuid(CompoundTag tag, String key) {
        if (!tag.read(key, UUIDUtil.CODEC).isPresent()) throw new IllegalArgumentException("missing UUID: " + key);
    }

    private static void requireString(CompoundTag tag, String key) {
        if (!MinecraftApi.containsTag(tag, key, Tag.TAG_STRING)) {
            throw new IllegalArgumentException("missing string: " + key);
        }
    }

    private static void requireLong(CompoundTag tag, String key) {
        if (!MinecraftApi.containsTag(tag, key, Tag.TAG_LONG)) {
            throw new IllegalArgumentException("missing long: " + key);
        }
    }

    private static String truncate(String value, int limit) {
        if (value.length() <= limit) return value;
        int end = limit;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
}
