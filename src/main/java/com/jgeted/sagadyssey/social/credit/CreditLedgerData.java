package com.jgeted.sagadyssey.social.credit;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable obligations and idempotent physical-payment evidence. */
public final class CreditLedgerData extends SavedData {
    private static final String DATA_NAME = "sagadyssey_credit_ledger";
    private static final Factory<CreditLedgerData> FACTORY =
            new Factory<>(CreditLedgerData::new, CreditLedgerData::load);
    private final Map<UUID, CreditAgreement> agreements = new HashMap<>();
    private final Map<UUID, CreditPaymentRecord> payments = new HashMap<>();

    public static CreditLedgerData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    @Nullable public CreditAgreement agreement(UUID id) { return agreements.get(id); }
    @Nullable public CreditPaymentRecord payment(UUID id) { return payments.get(id); }
    public List<CreditAgreement> agreements() { return List.copyOf(agreements.values()); }

    boolean add(CreditAgreement agreement) {
        if (agreements.containsKey(agreement.id())) return false;
        agreements.put(agreement.id(), agreement);
        setDirty();
        return true;
    }

    public boolean recordPayment(UUID paymentId, UUID agreementId, int emeralds, long now) {
        if (payments.containsKey(paymentId)) return false;
        CreditAgreement agreement = agreements.get(agreementId);
        if (agreement == null) throw new IllegalStateException("债务不存在");
        CreditAgreement paid = agreement.paid(emeralds, now);
        agreements.put(agreementId, paid);
        payments.put(paymentId, new CreditPaymentRecord(paymentId, agreementId, emeralds, now));
        setDirty();
        return true;
    }

    public void reviewDue(long now) {
        boolean changed = false;
        for (CreditAgreement agreement : List.copyOf(agreements.values())) {
            CreditAgreement reviewed = agreement.review(now);
            if (!reviewed.equals(agreement)) {
                agreements.put(reviewed.id(), reviewed);
                changed = true;
            }
        }
        if (changed) setDirty();
    }

    public CreditProfile profile(SubjectRef creditor, SubjectRef debtor,
                                 CreditPurpose purpose, long now) {
        reviewDue(now);
        List<CreditAgreement> matching = agreements.values().stream()
                .filter(value -> value.creditor().equals(creditor)
                        && value.debtor().equals(debtor) && value.purpose() == purpose).toList();
        int principal = matching.stream().mapToInt(CreditAgreement::principalEmeralds).sum();
        int outstanding = matching.stream().filter(value -> value.status().payable())
                .mapToInt(CreditAgreement::outstandingEmeralds).sum();
        int overdue = (int) matching.stream().filter(value ->
                value.status() == CreditStatus.OVERDUE
                        || value.status() == CreditStatus.SETTLED_LATE).count();
        int defaults = (int) matching.stream().filter(value ->
                value.status() == CreditStatus.DEFAULTED
                        || value.status() == CreditStatus.SETTLED_LATE).count();
        return new CreditProfile(creditor, debtor, purpose, matching.size(), principal,
                principal - matching.stream().mapToInt(CreditAgreement::outstandingEmeralds).sum(),
                outstanding, overdue, defaults);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag agreementTags = new ListTag();
        agreements.values().forEach(value -> agreementTags.add(value.save()));
        tag.put("Agreements", agreementTags);
        ListTag paymentTags = new ListTag();
        payments.values().forEach(value -> paymentTags.add(value.save()));
        tag.put("Payments", paymentTags);
        return tag;
    }

    static CreditLedgerData load(CompoundTag tag, HolderLookup.Provider registries) {
        CreditLedgerData data = new CreditLedgerData();
        ListTag agreementTags = tag.getList("Agreements", Tag.TAG_COMPOUND);
        for (int index = 0; index < agreementTags.size(); index++) {
            CreditAgreement value = CreditAgreement.load(agreementTags.getCompound(index));
            data.agreements.put(value.id(), value);
        }
        ListTag paymentTags = tag.getList("Payments", Tag.TAG_COMPOUND);
        for (int index = 0; index < paymentTags.size(); index++) {
            CreditPaymentRecord value = CreditPaymentRecord.load(paymentTags.getCompound(index));
            data.payments.put(value.paymentId(), value);
        }
        return data;
    }
}
