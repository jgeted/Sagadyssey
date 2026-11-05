package com.jgeted.sagadyssey.social.security;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/** A fallible statement; confidence is evidence weight, not objective truth. */
public record WitnessObservation(UUID id, SubjectRef witness,
                                 @Nullable SubjectRef allegedOffender,
                                 int confidence, long observedAt,
                                 long reportedAt, String account) {
    public WitnessObservation {
        if (id == null || witness == null || confidence < 0 || confidence > 100
                || reportedAt < observedAt) throw new IllegalArgumentException("invalid witness observation");
        account = account == null ? "" : account;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.put("Witness", witness.save());
        if (allegedOffender != null) tag.put("AllegedOffender", allegedOffender.save());
        tag.putInt("Confidence", confidence);
        tag.putLong("ObservedAt", observedAt);
        tag.putLong("ReportedAt", reportedAt);
        tag.putString("Account", account);
        return tag;
    }

    static WitnessObservation load(CompoundTag tag) {
        return new WitnessObservation(tag.getUUID("Id"),
                SubjectRef.load(tag.getCompound("Witness")),
                tag.contains("AllegedOffender")
                        ? SubjectRef.load(tag.getCompound("AllegedOffender")) : null,
                tag.getInt("Confidence"), tag.getLong("ObservedAt"),
                tag.getLong("ReportedAt"), tag.getString("Account"));
    }
}
