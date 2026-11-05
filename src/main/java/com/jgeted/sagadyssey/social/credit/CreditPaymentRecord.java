package com.jgeted.sagadyssey.social.credit;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record CreditPaymentRecord(UUID paymentId, UUID agreementId, int emeralds, long paidAt) {
    public CreditPaymentRecord {
        if (paymentId == null || agreementId == null || emeralds <= 0) {
            throw new IllegalArgumentException("invalid credit payment record");
        }
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Payment", paymentId);
        tag.putUUID("Agreement", agreementId);
        tag.putInt("Emeralds", emeralds);
        tag.putLong("PaidAt", paidAt);
        return tag;
    }

    static CreditPaymentRecord load(CompoundTag tag) {
        return new CreditPaymentRecord(tag.getUUID("Payment"), tag.getUUID("Agreement"),
                tag.getInt("Emeralds"), tag.getLong("PaidAt"));
    }
}
