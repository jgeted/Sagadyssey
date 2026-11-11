package com.jgeted.sagadyssey.social.market;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** A quote learned because this merchant was physically present in that market. */
public record MerchantPriceMemory(UUID merchantId, UUID settlementId,
                                  ItemFingerprint fingerprint, int unitEmeraldMilli,
                                  int sourceSamples, long observedAt) {
    public MerchantPriceMemory {
        if (merchantId == null || settlementId == null || fingerprint == null
                || unitEmeraldMilli <= 0 || sourceSamples <= 0) {
            throw new IllegalArgumentException("invalid merchant price memory");
        }
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Merchant", UUIDUtil.CODEC, merchantId);
        tag.store("Settlement", UUIDUtil.CODEC, settlementId);
        tag.put("Fingerprint", fingerprint.save());
        tag.putInt("Price", unitEmeraldMilli);
        tag.putInt("Samples", sourceSamples);
        tag.putLong("ObservedAt", observedAt);
        return tag;
    }

    static MerchantPriceMemory load(CompoundTag tag) {
        return new MerchantPriceMemory(tag.read("Merchant", UUIDUtil.CODEC).orElseThrow(), tag.read("Settlement", UUIDUtil.CODEC).orElseThrow(),
                ItemFingerprint.load(tag.getCompoundOrEmpty("Fingerprint")), tag.getIntOr("Price", 0),
                tag.getIntOr("Samples", 0), tag.getLongOr("ObservedAt", 0L));
    }
}
