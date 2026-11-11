package com.jgeted.sagadyssey.social.governance;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** A specific, contestable obligation; no abstract balance or invisible tax payment exists. */
public record LevyAssessment(UUID id, UUID institutionId, UUID settlementId,
                             SubjectRef debtor, LevyType type, ItemFingerprint asset,
                             int assessedQuantity, int paidQuantity, long assessedAt,
                             long dueAt, LevyStatus status, String basis, int revision) {
    public LevyAssessment {
        if (id == null || institutionId == null || settlementId == null || debtor == null
                || type == null || asset == null || assessedQuantity <= 0 || paidQuantity < 0
                || paidQuantity > assessedQuantity || dueAt <= assessedAt || status == null
                || revision < 0) throw new IllegalArgumentException("invalid levy assessment");
        basis = basis == null ? "" : basis;
    }

    public int outstanding() { return assessedQuantity - paidQuantity; }

    public LevyAssessment review(long now) {
        if (!status.payable() || now < dueAt || status == LevyStatus.OVERDUE) return this;
        return copy(paidQuantity, LevyStatus.OVERDUE, "到期未足额履行", revision + 1);
    }

    public LevyAssessment paid(int quantity, long now) {
        if (!status.payable() || quantity <= 0 || quantity > outstanding()) {
            throw new IllegalArgumentException("invalid levy payment");
        }
        int total = paidQuantity + quantity;
        boolean late = status == LevyStatus.OVERDUE || now >= dueAt;
        LevyStatus next = total == assessedQuantity
                ? late ? LevyStatus.SATISFIED_LATE : LevyStatus.SATISFIED
                : late ? LevyStatus.OVERDUE : LevyStatus.PARTIALLY_PAID;
        return copy(total, next, "已实体缴纳 " + quantity, revision + 1);
    }

    private LevyAssessment copy(int paid, LevyStatus next, String nextBasis, int nextRevision) {
        return new LevyAssessment(id, institutionId, settlementId, debtor, type, asset,
                assessedQuantity, paid, assessedAt, dueAt, next, nextBasis, nextRevision);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Institution", UUIDUtil.CODEC, institutionId);
        tag.store("Settlement", UUIDUtil.CODEC, settlementId);
        tag.put("Debtor", debtor.save());
        tag.putString("Type", type.name());
        tag.put("Asset", asset.save());
        tag.putInt("AssessedQuantity", assessedQuantity);
        tag.putInt("PaidQuantity", paidQuantity);
        tag.putLong("AssessedAt", assessedAt);
        tag.putLong("DueAt", dueAt);
        tag.putString("Status", status.name());
        tag.putString("Basis", basis);
        tag.putInt("Revision", revision);
        return tag;
    }

    static LevyAssessment load(CompoundTag tag) {
        return new LevyAssessment(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Institution", UUIDUtil.CODEC).orElseThrow(),
                tag.read("Settlement", UUIDUtil.CODEC).orElseThrow(), SubjectRef.load(tag.getCompoundOrEmpty("Debtor")),
                LevyType.valueOf(tag.getStringOr("Type", "")),
                ItemFingerprint.load(tag.getCompoundOrEmpty("Asset")), tag.getIntOr("AssessedQuantity", 0),
                tag.getIntOr("PaidQuantity", 0), tag.getLongOr("AssessedAt", 0L), tag.getLongOr("DueAt", 0L),
                LevyStatus.valueOf(tag.getStringOr("Status", "")), tag.getStringOr("Basis", ""),
                tag.getIntOr("Revision", 0));
    }
}
