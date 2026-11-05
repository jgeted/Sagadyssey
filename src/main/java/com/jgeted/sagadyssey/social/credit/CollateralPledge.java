package com.jgeted.sagadyssey.social.credit;

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
        tag.putUUID("Container", containerId);
        tag.put("Fingerprint", fingerprint.save());
        tag.putInt("Quantity", quantity);
        tag.put("Pledgor", pledgor.save());
        return tag;
    }

    static CollateralPledge load(CompoundTag tag) {
        return new CollateralPledge(tag.getUUID("Container"),
                ItemFingerprint.load(tag.getCompound("Fingerprint")), tag.getInt("Quantity"),
                SubjectRef.load(tag.getCompound("Pledgor")));
    }
}
