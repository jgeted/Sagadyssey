package com.jgeted.sagadyssey.social.combat;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record TreatmentRecord(UUID id, UUID woundId, SubjectRef healer,
                              TreatmentType type, long treatedAt,
                              UUID supplyContainerId, boolean successful, String note) {
    public TreatmentRecord {
        if (id == null || woundId == null || healer == null || type == null
                || supplyContainerId == null) throw new IllegalArgumentException("invalid treatment");
        note = note == null ? "" : note;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Wound", UUIDUtil.CODEC, woundId);
        tag.put("Healer", healer.save());
        tag.putString("Type", type.name());
        tag.putLong("TreatedAt", treatedAt);
        tag.store("SupplyContainer", UUIDUtil.CODEC, supplyContainerId);
        tag.putBoolean("Successful", successful);
        tag.putString("Note", note);
        return tag;
    }

    static TreatmentRecord load(CompoundTag tag) {
        return new TreatmentRecord(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Wound", UUIDUtil.CODEC).orElseThrow(),
                SubjectRef.load(tag.getCompoundOrEmpty("Healer")),
                TreatmentType.valueOf(tag.getStringOr("Type", "")), tag.getLongOr("TreatedAt", 0L),
                tag.read("SupplyContainer", UUIDUtil.CODEC).orElseThrow(), tag.getBooleanOr("Successful", false),
                tag.getStringOr("Note", ""));
    }
}
