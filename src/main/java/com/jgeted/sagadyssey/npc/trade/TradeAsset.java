package com.jgeted.sagadyssey.npc.trade;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.nbt.CompoundTag;

public record TradeAsset(ItemFingerprint fingerprint, int quantity, String knownName) {
    public TradeAsset {
        if (fingerprint == null || quantity <= 0) throw new IllegalArgumentException("invalid trade asset");
        knownName = knownName == null ? "" : knownName;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("Fingerprint", fingerprint.save());
        tag.putInt("Quantity", quantity);
        tag.putString("KnownName", knownName);
        return tag;
    }

    static TradeAsset load(CompoundTag tag) {
        return new TradeAsset(ItemFingerprint.load(tag.getCompound("Fingerprint")),
                tag.getInt("Quantity"), tag.getString("KnownName"));
    }
}
