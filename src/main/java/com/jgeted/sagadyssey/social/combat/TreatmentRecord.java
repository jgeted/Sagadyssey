package com.jgeted.sagadyssey.social.combat;

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
        tag.putUUID("Id", id);
        tag.putUUID("Wound", woundId);
        tag.put("Healer", healer.save());
        tag.putString("Type", type.name());
        tag.putLong("TreatedAt", treatedAt);
        tag.putUUID("SupplyContainer", supplyContainerId);
        tag.putBoolean("Successful", successful);
        tag.putString("Note", note);
        return tag;
    }

    static TreatmentRecord load(CompoundTag tag) {
        return new TreatmentRecord(tag.getUUID("Id"), tag.getUUID("Wound"),
                SubjectRef.load(tag.getCompound("Healer")),
                TreatmentType.valueOf(tag.getString("Type")), tag.getLong("TreatedAt"),
                tag.getUUID("SupplyContainer"), tag.getBoolean("Successful"),
                tag.getString("Note"));
    }
}
