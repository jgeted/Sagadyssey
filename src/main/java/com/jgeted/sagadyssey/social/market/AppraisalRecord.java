package com.jgeted.sagadyssey.social.market;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Signed observation bound to an exact physical slot and component fingerprint. */
public record AppraisalRecord(UUID markId, SubjectRef appraiser, SubjectRef owner,
                              UUID containerId, int slot, ItemFingerprint fingerprint,
                              int measuredCount, int damage, int maximumDamage,
                              QualityGrade grade, long issuedAt, long expiresAt,
                              UUID signature) {
    public AppraisalRecord {
        if (markId == null || appraiser == null || owner == null || containerId == null
                || slot < 0 || fingerprint == null || measuredCount <= 0 || damage < 0
                || maximumDamage < 0 || grade == null || expiresAt <= issuedAt
                || signature == null) throw new IllegalArgumentException("invalid appraisal");
    }

    public boolean verifies(UUID currentContainerId, int currentSlot,
                            Container container, long now) {
        if (now >= expiresAt || !containerId.equals(currentContainerId) || slot != currentSlot
                || slot >= container.getContainerSize()) return false;
        ItemStack current = container.getItem(slot);
        return fingerprint.matches(current) && current.getCount() == measuredCount
                && current.getDamageValue() == damage && current.getMaxDamage() == maximumDamage;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Mark", markId);
        tag.put("Appraiser", appraiser.save());
        tag.put("Owner", owner.save());
        tag.putUUID("Container", containerId);
        tag.putInt("Slot", slot);
        tag.put("Fingerprint", fingerprint.save());
        tag.putInt("Count", measuredCount);
        tag.putInt("Damage", damage);
        tag.putInt("MaximumDamage", maximumDamage);
        tag.putString("Grade", grade.name());
        tag.putLong("IssuedAt", issuedAt);
        tag.putLong("ExpiresAt", expiresAt);
        tag.putUUID("Signature", signature);
        return tag;
    }

    static AppraisalRecord load(CompoundTag tag) {
        return new AppraisalRecord(tag.getUUID("Mark"),
                SubjectRef.load(tag.getCompound("Appraiser")),
                SubjectRef.load(tag.getCompound("Owner")), tag.getUUID("Container"),
                tag.getInt("Slot"), ItemFingerprint.load(tag.getCompound("Fingerprint")),
                tag.getInt("Count"), tag.getInt("Damage"), tag.getInt("MaximumDamage"),
                QualityGrade.valueOf(tag.getString("Grade")), tag.getLong("IssuedAt"),
                tag.getLong("ExpiresAt"), tag.getUUID("Signature"));
    }
}
