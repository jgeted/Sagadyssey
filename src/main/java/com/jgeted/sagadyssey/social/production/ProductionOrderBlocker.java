package com.jgeted.sagadyssey.social.production;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** 可诊断、可按时间重试的生产订单阻塞原因。 */
public record ProductionOrderBlocker(String code, String detail, long retryAt) {
    private static final int MAX_CODE_LENGTH = 128;
    private static final int MAX_DETAIL_LENGTH = 2_048;

    public ProductionOrderBlocker {
        if (code == null || code.isBlank() || code.length() > MAX_CODE_LENGTH) {
            throw new IllegalArgumentException("invalid production blocker code");
        }
        if (retryAt < 0L) throw new IllegalArgumentException("retryAt cannot be negative");
        detail = truncate(detail == null ? "" : detail, MAX_DETAIL_LENGTH);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Code", code);
        tag.putString("Detail", detail);
        tag.putLong("RetryAt", retryAt);
        return tag;
    }

    static ProductionOrderBlocker load(CompoundTag tag) {
        if (!MinecraftApi.containsTag(tag, "Code", Tag.TAG_STRING)
                || !MinecraftApi.containsTag(tag, "Detail", Tag.TAG_STRING)
                || !MinecraftApi.containsTag(tag, "RetryAt", Tag.TAG_LONG)) {
            throw new IllegalArgumentException("invalid production blocker tag");
        }
        return new ProductionOrderBlocker(tag.getStringOr("Code", ""), tag.getStringOr("Detail", ""),
                tag.getLongOr("RetryAt", 0L));
    }

    private static String truncate(String value, int limit) {
        if (value.length() <= limit) return value;
        int end = limit;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
}
