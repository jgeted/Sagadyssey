package com.jgeted.sagadyssey.social.market;

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
        tag.putUUID("Merchant", merchantId);
        tag.putUUID("Settlement", settlementId);
        tag.put("Fingerprint", fingerprint.save());
        tag.putInt("Price", unitEmeraldMilli);
        tag.putInt("Samples", sourceSamples);
        tag.putLong("ObservedAt", observedAt);
        return tag;
    }

    static MerchantPriceMemory load(CompoundTag tag) {
        return new MerchantPriceMemory(tag.getUUID("Merchant"), tag.getUUID("Settlement"),
                ItemFingerprint.load(tag.getCompound("Fingerprint")), tag.getInt("Price"),
                tag.getInt("Samples"), tag.getLong("ObservedAt"));
    }
}
