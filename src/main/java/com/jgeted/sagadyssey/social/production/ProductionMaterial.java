package com.jgeted.sagadyssey.social.production;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.nbt.CompoundTag;

public record ProductionMaterial(ItemFingerprint fingerprint, int quantity) {
    public ProductionMaterial {
        if (fingerprint == null || quantity <= 0) throw new IllegalArgumentException("invalid material");
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("Fingerprint", fingerprint.save());
        tag.putInt("Quantity", quantity);
        return tag;
    }

    static ProductionMaterial load(CompoundTag tag) {
        return new ProductionMaterial(ItemFingerprint.load(tag.getCompoundOrEmpty("Fingerprint")),
                tag.getIntOr("Quantity", 0));
    }
}
