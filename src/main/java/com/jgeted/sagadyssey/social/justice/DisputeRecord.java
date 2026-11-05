package com.jgeted.sagadyssey.social.justice;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record DisputeRecord(UUID id, UUID forumInstitutionId, UUID settlementId,
                            SubjectRef claimant, SubjectRef respondent, DisputeType type,
                            String claim, List<EvidenceRef> evidence, long filedAt,
                            DisputeStatus status, @Nullable SubjectRef decidedBy,
                            long decidedAt, String decision, JudgmentSanction sanction,
                            @Nullable UUID enforcementRecordId, int revision) {
    public DisputeRecord {
        if (id == null || forumInstitutionId == null || settlementId == null
                || claimant == null || respondent == null || claimant.equals(respondent)
                || type == null || status == null || sanction == null || revision < 0) {
            throw new IllegalArgumentException("invalid dispute");
        }
        claim = claim == null ? "" : claim;
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        decision = decision == null ? "" : decision;
    }

    public DisputeRecord withEvidence(EvidenceRef value) {
        if (!status.open()) throw new IllegalStateException("案件已经结束");
        if (evidence.stream().anyMatch(existing -> existing.id().equals(value.id()))) return this;
        List<EvidenceRef> updated = new ArrayList<>(evidence);
        updated.add(value);
        return new DisputeRecord(id, forumInstitutionId, settlementId, claimant, respondent,
                type, claim, updated, filedAt, DisputeStatus.UNDER_REVIEW, decidedBy,
                decidedAt, decision, sanction, enforcementRecordId, revision + 1);
    }

    public DisputeRecord decide(SubjectRef judge, long now, String finding,
                                JudgmentSanction nextSanction, @Nullable UUID enforcementId) {
        if (!status.open()) throw new IllegalStateException("案件已经结束");
        return new DisputeRecord(id, forumInstitutionId, settlementId, claimant, respondent,
                type, claim, evidence, filedAt, DisputeStatus.DECIDED, judge, now,
                finding, nextSanction, enforcementId, revision + 1);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Forum", forumInstitutionId);
        tag.putUUID("Settlement", settlementId);
        tag.put("Claimant", claimant.save());
        tag.put("Respondent", respondent.save());
        tag.putString("Type", type.name());
        tag.putString("Claim", claim);
        ListTag evidenceTags = new ListTag();
        evidence.forEach(value -> evidenceTags.add(value.save()));
        tag.put("Evidence", evidenceTags);
        tag.putLong("FiledAt", filedAt);
        tag.putString("Status", status.name());
        if (decidedBy != null) tag.put("DecidedBy", decidedBy.save());
        tag.putLong("DecidedAt", decidedAt);
        tag.putString("Decision", decision);
        tag.putString("Sanction", sanction.name());
        if (enforcementRecordId != null) tag.putUUID("Enforcement", enforcementRecordId);
        tag.putInt("Revision", revision);
        return tag;
    }

    static DisputeRecord load(CompoundTag tag) {
        List<EvidenceRef> evidence = new ArrayList<>();
        ListTag evidenceTags = tag.getList("Evidence", Tag.TAG_COMPOUND);
        for (int index = 0; index < evidenceTags.size(); index++) {
            evidence.add(EvidenceRef.load(evidenceTags.getCompound(index)));
        }
        return new DisputeRecord(tag.getUUID("Id"), tag.getUUID("Forum"),
                tag.getUUID("Settlement"), SubjectRef.load(tag.getCompound("Claimant")),
                SubjectRef.load(tag.getCompound("Respondent")),
                DisputeType.valueOf(tag.getString("Type")), tag.getString("Claim"), evidence,
                tag.getLong("FiledAt"), DisputeStatus.valueOf(tag.getString("Status")),
                tag.contains("DecidedBy") ? SubjectRef.load(tag.getCompound("DecidedBy")) : null,
                tag.getLong("DecidedAt"), tag.getString("Decision"),
                JudgmentSanction.valueOf(tag.getString("Sanction")),
                tag.hasUUID("Enforcement") ? tag.getUUID("Enforcement") : null,
                tag.getInt("Revision"));
    }
}
