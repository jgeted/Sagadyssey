package com.jgeted.sagadyssey.social.credit;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Specific physical goods promised as security; title does not move until enforcement. */
public record CollateralPledge(UUID containerId, ItemFingerprint fingerprint,
                               int quantity, SubjectRef pledgor) {
    public CollateralPledge {
        if (containerId == null || fingerprint == null || quantity <= 0 || pledgor == null) {
            throw new IllegalArgumentException("invalid collateral pledge");
        }
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Container", UUIDUtil.CODEC, containerId);
        tag.put("Fingerprint", fingerprint.save());
        tag.putInt("Quantity", quantity);
        tag.put("Pledgor", pledgor.save());
        return tag;
    }

    static CollateralPledge load(CompoundTag tag) {
        return new CollateralPledge(tag.read("Container", UUIDUtil.CODEC).orElseThrow(),
                ItemFingerprint.load(tag.getCompoundOrEmpty("Fingerprint")), tag.getIntOr("Quantity", 0),
                SubjectRef.load(tag.getCompoundOrEmpty("Pledgor")));
    }
}
