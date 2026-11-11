package com.jgeted.sagadyssey.social.guild;

import net.minecraft.core.UUIDUtil;

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
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Institution", UUIDUtil.CODEC, institutionId);
        tag.store("Standard", UUIDUtil.CODEC, standardId);
        tag.store("Qualification", UUIDUtil.CODEC, qualificationId);
        tag.store("Appraisal", UUIDUtil.CODEC, appraisalId);
        tag.put("Inspector", inspector.save());
        tag.putBoolean("Passed", passed);
        tag.putLong("InspectedAt", inspectedAt);
        tag.putString("Finding", finding);
        return tag;
    }

    static GuildInspection load(CompoundTag tag) {
        return new GuildInspection(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Institution", UUIDUtil.CODEC).orElseThrow(),
                tag.read("Standard", UUIDUtil.CODEC).orElseThrow(), tag.read("Qualification", UUIDUtil.CODEC).orElseThrow(),
                tag.read("Appraisal", UUIDUtil.CODEC).orElseThrow(), SubjectRef.load(tag.getCompoundOrEmpty("Inspector")),
                tag.getBooleanOr("Passed", false), tag.getLongOr("InspectedAt", 0L), tag.getStringOr("Finding", ""));
    }
}
