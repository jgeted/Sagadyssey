package com.jgeted.sagadyssey.social.governance;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record LevyPaymentRecord(UUID paymentId, UUID assessmentId, int quantity,
                                long paidAt, UUID transferId) {
    public LevyPaymentRecord {
        if (paymentId == null || assessmentId == null || quantity <= 0 || transferId == null) {
            throw new IllegalArgumentException("invalid levy payment record");
        }
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PaymentId", paymentId);
        tag.putUUID("AssessmentId", assessmentId);
        tag.putInt("Quantity", quantity);
        tag.putLong("PaidAt", paidAt);
        tag.putUUID("TransferId", transferId);
        return tag;
    }

    static LevyPaymentRecord load(CompoundTag tag) {
        return new LevyPaymentRecord(tag.getUUID("PaymentId"), tag.getUUID("AssessmentId"),
                tag.getInt("Quantity"), tag.getLong("PaidAt"), tag.getUUID("TransferId"));
    }
}
