package com.jgeted.sagadyssey.social.service;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/** Spatial and risk limits. Private inventory access is intentionally not a service permission. */
public record ServiceLimits(@Nullable UUID workplaceSettlementId, String dimension,
                            boolean dangerousOrdersAllowed, boolean privateInventoryAccess) {
    public ServiceLimits {
        dimension = dimension == null ? "" : dimension;
        if (privateInventoryAccess) {
            throw new IllegalArgumentException("service relationships cannot expose private inventory");
        }
    }

    public static ServiceLimits ordinary() { return new ServiceLimits(null, "", false, false); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (workplaceSettlementId != null) tag.store("Settlement", UUIDUtil.CODEC, workplaceSettlementId);
        tag.putString("Dimension", dimension);
        tag.putBoolean("Dangerous", dangerousOrdersAllowed);
        tag.putBoolean("PrivateInventory", false);
        return tag;
    }

    public static ServiceLimits load(CompoundTag tag) {
        return new ServiceLimits(tag.read("Settlement", UUIDUtil.CODEC).isPresent() ? tag.read("Settlement", UUIDUtil.CODEC).orElseThrow() : null,
                tag.getStringOr("Dimension", ""), tag.getBooleanOr("Dangerous", false), false);
    }
}
