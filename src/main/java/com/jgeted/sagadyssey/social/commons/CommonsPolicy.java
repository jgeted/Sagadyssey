package com.jgeted.sagadyssey.social.commons;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Membership quota over a physical common store, with stock protected for maintenance. */
public record CommonsPolicy(UUID id, UUID institutionId, UUID settlementId,
                            UUID sourceContainerId, ItemFingerprint asset,
                            int dailyQuota, int maintenanceReserve, int minimumStanding,
                            long startsAt, long endsAt, boolean active, String purpose) {
    public CommonsPolicy {
        if (id == null || institutionId == null || settlementId == null
                || sourceContainerId == null || asset == null || dailyQuota <= 0
                || maintenanceReserve < 0 || minimumStanding < 0 || minimumStanding > 100
                || endsAt != 0L && endsAt <= startsAt) {
            throw new IllegalArgumentException("invalid commons policy");
        }
        purpose = purpose == null ? "" : purpose;
    }

    public boolean activeAt(long now) { return active && now >= startsAt && (endsAt == 0L || now < endsAt); }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Institution", institutionId);
        tag.putUUID("Settlement", settlementId);
        tag.putUUID("SourceContainer", sourceContainerId);
        tag.put("Asset", asset.save());
        tag.putInt("DailyQuota", dailyQuota);
        tag.putInt("MaintenanceReserve", maintenanceReserve);
        tag.putInt("MinimumStanding", minimumStanding);
        tag.putLong("StartsAt", startsAt);
        tag.putLong("EndsAt", endsAt);
        tag.putBoolean("Active", active);
        tag.putString("Purpose", purpose);
        return tag;
    }

    static CommonsPolicy load(CompoundTag tag) {
        return new CommonsPolicy(tag.getUUID("Id"), tag.getUUID("Institution"),
                tag.getUUID("Settlement"), tag.getUUID("SourceContainer"),
                ItemFingerprint.load(tag.getCompound("Asset")), tag.getInt("DailyQuota"),
                tag.getInt("MaintenanceReserve"), tag.getInt("MinimumStanding"),
                tag.getLong("StartsAt"), tag.getLong("EndsAt"), tag.getBoolean("Active"),
                tag.getString("Purpose"));
    }
}
