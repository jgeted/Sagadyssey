package com.jgeted.sagadyssey.social.market;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.nbt.CompoundTag;

/** One physically transferred line in a completed market basket. */
public record MarketBasketEntry(ItemFingerprint fingerprint, int quantity) {
    public MarketBasketEntry {
        if (fingerprint == null || quantity <= 0) throw new IllegalArgumentException("invalid basket entry");
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("Fingerprint", fingerprint.save());
        tag.putInt("Quantity", quantity);
        return tag;
    }

    static MarketBasketEntry load(CompoundTag tag) {
        return new MarketBasketEntry(ItemFingerprint.load(tag.getCompound("Fingerprint")),
                tag.getInt("Quantity"));
    }
}
