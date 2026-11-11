package com.jgeted.sagadyssey.social.commons;

import net.minecraft.core.UUIDUtil;

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
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Institution", UUIDUtil.CODEC, institutionId);
        tag.store("Settlement", UUIDUtil.CODEC, settlementId);
        tag.store("SourceContainer", UUIDUtil.CODEC, sourceContainerId);
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
        return new CommonsPolicy(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Institution", UUIDUtil.CODEC).orElseThrow(),
                tag.read("Settlement", UUIDUtil.CODEC).orElseThrow(), tag.read("SourceContainer", UUIDUtil.CODEC).orElseThrow(),
                ItemFingerprint.load(tag.getCompoundOrEmpty("Asset")), tag.getIntOr("DailyQuota", 0),
                tag.getIntOr("MaintenanceReserve", 0), tag.getIntOr("MinimumStanding", 0),
                tag.getLongOr("StartsAt", 0L), tag.getLongOr("EndsAt", 0L), tag.getBooleanOr("Active", false),
                tag.getStringOr("Purpose", ""));
    }
}
