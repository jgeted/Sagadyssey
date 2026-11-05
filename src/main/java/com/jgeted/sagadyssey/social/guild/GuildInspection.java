package com.jgeted.sagadyssey.social.guild;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record GuildInspection(UUID id, UUID institutionId, UUID standardId,
                              UUID qualificationId, UUID appraisalId, SubjectRef inspector,
                              boolean passed, long inspectedAt, String finding) {
    public GuildInspection {
        if (id == null || institutionId == null || standardId == null
                || qualificationId == null || appraisalId == null || inspector == null) {
            throw new IllegalArgumentException("invalid guild inspection");
        }
        finding = finding == null ? "" : finding;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Institution", institutionId);
        tag.putUUID("Standard", standardId);
        tag.putUUID("Qualification", qualificationId);
        tag.putUUID("Appraisal", appraisalId);
        tag.put("Inspector", inspector.save());
        tag.putBoolean("Passed", passed);
        tag.putLong("InspectedAt", inspectedAt);
        tag.putString("Finding", finding);
        return tag;
    }

    static GuildInspection load(CompoundTag tag) {
        return new GuildInspection(tag.getUUID("Id"), tag.getUUID("Institution"),
                tag.getUUID("Standard"), tag.getUUID("Qualification"),
                tag.getUUID("Appraisal"), SubjectRef.load(tag.getCompound("Inspector")),
                tag.getBoolean("Passed"), tag.getLong("InspectedAt"), tag.getString("Finding"));
    }
}
