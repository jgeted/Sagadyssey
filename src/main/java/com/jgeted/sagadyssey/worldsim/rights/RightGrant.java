package com.jgeted.sagadyssey.worldsim.rights;

import net.minecraft.nbt.CompoundTag;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

public record RightGrant(UUID id, UUID containerId, SubjectRef holder, Set<AssetRight> rights,
                         String basis, long startsAt, Long endsAt, boolean revocable) {
    public RightGrant {
        if (id == null || containerId == null || holder == null || rights == null || rights.isEmpty()) {
            throw new IllegalArgumentException("invalid right grant");
        }
        rights = Set.copyOf(rights);
        basis = basis == null ? "" : basis;
    }

    public boolean activeAt(long gameTime) {
        return gameTime >= startsAt && (endsAt == null || gameTime <= endsAt);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Container", containerId);
        tag.put("Holder", holder.save());
        int mask = 0;
        for (AssetRight right : rights) mask |= 1 << right.ordinal();
        tag.putInt("Rights", mask);
        tag.putString("Basis", basis);
        tag.putLong("Starts", startsAt);
        if (endsAt != null) tag.putLong("Ends", endsAt);
        tag.putBoolean("Revocable", revocable);
        return tag;
    }

    public static RightGrant load(CompoundTag tag) {
        EnumSet<AssetRight> rights = EnumSet.noneOf(AssetRight.class);
        int mask = tag.getInt("Rights");
        for (AssetRight right : AssetRight.values()) if ((mask & (1 << right.ordinal())) != 0) rights.add(right);
        return new RightGrant(tag.getUUID("Id"), tag.getUUID("Container"),
                SubjectRef.load(tag.getCompound("Holder")), rights, tag.getString("Basis"),
                tag.getLong("Starts"), tag.contains("Ends") ? tag.getLong("Ends") : null,
                tag.getBoolean("Revocable"));
    }
}
