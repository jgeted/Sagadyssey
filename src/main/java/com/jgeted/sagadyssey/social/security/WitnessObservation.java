package com.jgeted.sagadyssey.social.security;

import net.minecraft.core.UUIDUtil;

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
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.put("Witness", witness.save());
        if (allegedOffender != null) tag.put("AllegedOffender", allegedOffender.save());
        tag.putInt("Confidence", confidence);
        tag.putLong("ObservedAt", observedAt);
        tag.putLong("ReportedAt", reportedAt);
        tag.putString("Account", account);
        return tag;
    }

    static WitnessObservation load(CompoundTag tag) {
        return new WitnessObservation(tag.read("Id", UUIDUtil.CODEC).orElseThrow(),
                SubjectRef.load(tag.getCompoundOrEmpty("Witness")),
                tag.contains("AllegedOffender")
                        ? SubjectRef.load(tag.getCompoundOrEmpty("AllegedOffender")) : null,
                tag.getIntOr("Confidence", 0), tag.getLongOr("ObservedAt", 0L),
                tag.getLongOr("ReportedAt", 0L), tag.getStringOr("Account", ""));
    }
}
