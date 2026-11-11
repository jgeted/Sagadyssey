package com.jgeted.sagadyssey.social.security;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.social.justice.EvidenceRef;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record SecurityIncident(UUID id, UUID settlementId, IncidentType type,
                               String dimension, BlockPos position,
                               @Nullable SubjectRef victim,
                               @Nullable SubjectRef allegedOffender,
                               int severity, SubjectRef reportedBy, long reportedAt,
                               IncidentStatus status, List<WitnessObservation> witnesses,
                               List<EvidenceRef> evidence,
                               @Nullable SubjectRef verifiedBy, String note, int revision) {
    public SecurityIncident {
        if (id == null || settlementId == null || type == null || dimension == null
                || dimension.isBlank() || position == null || severity < 0 || severity > 100
                || reportedBy == null || status == null || revision < 0) {
            throw new IllegalArgumentException("invalid security incident");
        }
        witnesses = List.copyOf(witnesses == null ? List.of() : witnesses);
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        note = note == null ? "" : note;
    }

    public SecurityIncident withWitness(WitnessObservation observation) {
        if (status == IncidentStatus.RESOLVED || status == IncidentStatus.DISMISSED) {
            throw new IllegalStateException("incident closed");
        }
        if (witnesses.stream().anyMatch(value -> value.id().equals(observation.id()))) return this;
        List<WitnessObservation> updated = new ArrayList<>(witnesses);
        updated.add(observation);
        return copy(statusAfterNewEvidence(), updated, evidence, verifiedBy, note);
    }

    public SecurityIncident withEvidence(EvidenceRef value) {
        if (status == IncidentStatus.RESOLVED || status == IncidentStatus.DISMISSED) {
            throw new IllegalStateException("incident closed");
        }
        if (evidence.stream().anyMatch(existing -> existing.id().equals(value.id()))) return this;
        List<EvidenceRef> updated = new ArrayList<>(evidence);
        updated.add(value);
        return copy(statusAfterNewEvidence(), witnesses, updated, verifiedBy, note);
    }

    public SecurityIncident verified(SubjectRef investigator, String finding) {
        return copy(IncidentStatus.VERIFIED, witnesses, evidence, investigator, finding);
    }

    public SecurityIncident responding() {
        return copy(IncidentStatus.RESPONDING, witnesses, evidence, verifiedBy, note);
    }

    private IncidentStatus statusAfterNewEvidence() {
        return status == IncidentStatus.REPORTED ? IncidentStatus.UNDER_REVIEW : status;
    }

    private SecurityIncident copy(IncidentStatus next, List<WitnessObservation> nextWitnesses,
                                  List<EvidenceRef> nextEvidence,
                                  @Nullable SubjectRef nextVerifier, String nextNote) {
        return new SecurityIncident(id, settlementId, type, dimension, position, victim,
                allegedOffender, severity, reportedBy, reportedAt, next,
                nextWitnesses, nextEvidence, nextVerifier, nextNote, revision + 1);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Settlement", UUIDUtil.CODEC, settlementId);
        tag.putString("Type", type.name());
        tag.putString("Dimension", dimension);
        tag.putLong("Position", position.asLong());
        if (victim != null) tag.put("Victim", victim.save());
        if (allegedOffender != null) tag.put("AllegedOffender", allegedOffender.save());
        tag.putInt("Severity", severity);
        tag.put("ReportedBy", reportedBy.save());
        tag.putLong("ReportedAt", reportedAt);
        tag.putString("Status", status.name());
        ListTag witnessTags = new ListTag();
        witnesses.forEach(value -> witnessTags.add(value.save()));
        tag.put("Witnesses", witnessTags);
        ListTag evidenceTags = new ListTag();
        evidence.forEach(value -> evidenceTags.add(value.save()));
        tag.put("Evidence", evidenceTags);
        if (verifiedBy != null) tag.put("VerifiedBy", verifiedBy.save());
        tag.putString("Note", note);
        tag.putInt("Revision", revision);
        return tag;
    }

    static SecurityIncident load(CompoundTag tag) {
        List<WitnessObservation> witnesses = new ArrayList<>();
        ListTag witnessTags = MinecraftApi.listOfType(tag, "Witnesses", Tag.TAG_COMPOUND);
        for (int index = 0; index < witnessTags.size(); index++) {
            witnesses.add(WitnessObservation.load(witnessTags.getCompoundOrEmpty(index)));
        }
        List<EvidenceRef> evidence = new ArrayList<>();
        ListTag evidenceTags = MinecraftApi.listOfType(tag, "Evidence", Tag.TAG_COMPOUND);
        for (int index = 0; index < evidenceTags.size(); index++) {
            evidence.add(EvidenceRef.load(evidenceTags.getCompoundOrEmpty(index)));
        }
        return new SecurityIncident(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Settlement", UUIDUtil.CODEC).orElseThrow(),
                IncidentType.valueOf(tag.getStringOr("Type", "")), tag.getStringOr("Dimension", ""),
                BlockPos.of(tag.getLongOr("Position", 0L)),
                tag.contains("Victim") ? SubjectRef.load(tag.getCompoundOrEmpty("Victim")) : null,
                tag.contains("AllegedOffender")
                        ? SubjectRef.load(tag.getCompoundOrEmpty("AllegedOffender")) : null,
                tag.getIntOr("Severity", 0), SubjectRef.load(tag.getCompoundOrEmpty("ReportedBy")),
                tag.getLongOr("ReportedAt", 0L), IncidentStatus.valueOf(tag.getStringOr("Status", "")),
                witnesses, evidence,
                tag.contains("VerifiedBy") ? SubjectRef.load(tag.getCompoundOrEmpty("VerifiedBy")) : null,
                tag.getStringOr("Note", ""), tag.getIntOr("Revision", 0));
    }
}
