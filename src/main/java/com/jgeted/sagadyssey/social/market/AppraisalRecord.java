package com.jgeted.sagadyssey.social.market;

import net.minecraft.core.UUIDUtil;

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
        tag.store("Mark", UUIDUtil.CODEC, markId);
        tag.put("Appraiser", appraiser.save());
        tag.put("Owner", owner.save());
        tag.store("Container", UUIDUtil.CODEC, containerId);
        tag.putInt("Slot", slot);
        tag.put("Fingerprint", fingerprint.save());
        tag.putInt("Count", measuredCount);
        tag.putInt("Damage", damage);
        tag.putInt("MaximumDamage", maximumDamage);
        tag.putString("Grade", grade.name());
        tag.putLong("IssuedAt", issuedAt);
        tag.putLong("ExpiresAt", expiresAt);
        tag.store("Signature", UUIDUtil.CODEC, signature);
        return tag;
    }

    static AppraisalRecord load(CompoundTag tag) {
        return new AppraisalRecord(tag.read("Mark", UUIDUtil.CODEC).orElseThrow(),
                SubjectRef.load(tag.getCompoundOrEmpty("Appraiser")),
                SubjectRef.load(tag.getCompoundOrEmpty("Owner")), tag.read("Container", UUIDUtil.CODEC).orElseThrow(),
                tag.getIntOr("Slot", 0), ItemFingerprint.load(tag.getCompoundOrEmpty("Fingerprint")),
                tag.getIntOr("Count", 0), tag.getIntOr("Damage", 0), tag.getIntOr("MaximumDamage", 0),
                QualityGrade.valueOf(tag.getStringOr("Grade", "")), tag.getLongOr("IssuedAt", 0L),
                tag.getLongOr("ExpiresAt", 0L), tag.read("Signature", UUIDUtil.CODEC).orElseThrow());
    }
}
