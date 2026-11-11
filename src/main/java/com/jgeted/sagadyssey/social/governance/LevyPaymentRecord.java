package com.jgeted.sagadyssey.social.governance;

import net.minecraft.core.UUIDUtil;

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
        tag.store("PaymentId", UUIDUtil.CODEC, paymentId);
        tag.store("AssessmentId", UUIDUtil.CODEC, assessmentId);
        tag.putInt("Quantity", quantity);
        tag.putLong("PaidAt", paidAt);
        tag.store("TransferId", UUIDUtil.CODEC, transferId);
        return tag;
    }

    static LevyPaymentRecord load(CompoundTag tag) {
        return new LevyPaymentRecord(tag.read("PaymentId", UUIDUtil.CODEC).orElseThrow(), tag.read("AssessmentId", UUIDUtil.CODEC).orElseThrow(),
                tag.getIntOr("Quantity", 0), tag.getLongOr("PaidAt", 0L), tag.read("TransferId", UUIDUtil.CODEC).orElseThrow());
    }
}
