package com.jgeted.sagadyssey.social.credit;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Pair-specific debt denominated in the existing emerald item, never an abstract wallet. */
public record CreditAgreement(UUID id, UUID settlementId, SubjectRef creditor,
                              SubjectRef debtor, CreditPurpose purpose,
                              int principalEmeralds, int outstandingEmeralds,
                              long issuedAt, long dueAt, long statusChangedAt,
                              CreditStatus status, List<CollateralPledge> collateral,
                              List<CreditGuarantee> guarantees, String note, int revision) {
    public static final long DEFAULT_GRACE = 2L * 24_000L;

    public CreditAgreement {
        if (id == null || settlementId == null || creditor == null || debtor == null
                || creditor.equals(debtor) || purpose == null || principalEmeralds <= 0
                || outstandingEmeralds < 0 || outstandingEmeralds > principalEmeralds
                || dueAt <= issuedAt || status == null || revision < 0) {
            throw new IllegalArgumentException("invalid credit agreement");
        }
        collateral = List.copyOf(collateral == null ? List.of() : collateral);
        guarantees = List.copyOf(guarantees == null ? List.of() : guarantees);
        note = note == null ? "" : note;
    }

    public String reservationToken() { return "credit:" + id; }

    public CreditAgreement paid(int emeralds, long now) {
        if (!status.payable() || emeralds <= 0 || emeralds > outstandingEmeralds) {
            throw new IllegalArgumentException("invalid credit payment");
        }
        int remaining = outstandingEmeralds - emeralds;
        boolean late = status == CreditStatus.OVERDUE || status == CreditStatus.DEFAULTED;
        CreditStatus next = remaining == 0
                ? late ? CreditStatus.SETTLED_LATE : CreditStatus.SETTLED
                : late ? status : CreditStatus.PARTIALLY_PAID;
        return copy(remaining, now, next, "已用实体绿宝石偿还 " + emeralds + " 枚");
    }

    public CreditAgreement review(long now) {
        if (status.terminal() || !status.payable() || now < dueAt) return this;
        CreditStatus next = now >= dueAt + DEFAULT_GRACE ? CreditStatus.DEFAULTED : CreditStatus.OVERDUE;
        if (status == next) return this;
        return copy(outstandingEmeralds, now, next,
                next == CreditStatus.DEFAULTED ? "超过宽限期仍未清偿" : "债务已经到期");
    }

    private CreditAgreement copy(int outstanding, long now, CreditStatus next, String nextNote) {
        return new CreditAgreement(id, settlementId, creditor, debtor, purpose,
                principalEmeralds, outstanding, issuedAt, dueAt, now, next,
                collateral, guarantees, nextNote, revision + 1);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Settlement", settlementId);
        tag.put("Creditor", creditor.save());
        tag.put("Debtor", debtor.save());
        tag.putString("Purpose", purpose.name());
        tag.putInt("Principal", principalEmeralds);
        tag.putInt("Outstanding", outstandingEmeralds);
        tag.putLong("IssuedAt", issuedAt);
        tag.putLong("DueAt", dueAt);
        tag.putLong("StatusChangedAt", statusChangedAt);
        tag.putString("Status", status.name());
        ListTag collateralTags = new ListTag();
        collateral.forEach(value -> collateralTags.add(value.save()));
        tag.put("Collateral", collateralTags);
        ListTag guaranteeTags = new ListTag();
        guarantees.forEach(value -> guaranteeTags.add(value.save()));
        tag.put("Guarantees", guaranteeTags);
        tag.putString("Note", note);
        tag.putInt("Revision", revision);
        return tag;
    }

    static CreditAgreement load(CompoundTag tag) {
        List<CollateralPledge> collateral = new ArrayList<>();
        ListTag collateralTags = tag.getList("Collateral", Tag.TAG_COMPOUND);
        for (int index = 0; index < collateralTags.size(); index++) {
            collateral.add(CollateralPledge.load(collateralTags.getCompound(index)));
        }
        List<CreditGuarantee> guarantees = new ArrayList<>();
        ListTag guaranteeTags = tag.getList("Guarantees", Tag.TAG_COMPOUND);
        for (int index = 0; index < guaranteeTags.size(); index++) {
            guarantees.add(CreditGuarantee.load(guaranteeTags.getCompound(index)));
        }
        return new CreditAgreement(tag.getUUID("Id"), tag.getUUID("Settlement"),
                SubjectRef.load(tag.getCompound("Creditor")),
                SubjectRef.load(tag.getCompound("Debtor")),
                CreditPurpose.valueOf(tag.getString("Purpose")), tag.getInt("Principal"),
                tag.getInt("Outstanding"), tag.getLong("IssuedAt"), tag.getLong("DueAt"),
                tag.getLong("StatusChangedAt"), CreditStatus.valueOf(tag.getString("Status")),
                collateral, guarantees, tag.getString("Note"), tag.getInt("Revision"));
    }
}
