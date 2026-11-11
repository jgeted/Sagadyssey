package com.jgeted.sagadyssey.social.credit;

import net.minecraft.core.UUIDUtil;

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
        tag.store("Payment", UUIDUtil.CODEC, paymentId);
        tag.store("Agreement", UUIDUtil.CODEC, agreementId);
        tag.putInt("Emeralds", emeralds);
        tag.putLong("PaidAt", paidAt);
        return tag;
    }

    static CreditPaymentRecord load(CompoundTag tag) {
        return new CreditPaymentRecord(tag.read("Payment", UUIDUtil.CODEC).orElseThrow(), tag.read("Agreement", UUIDUtil.CODEC).orElseThrow(),
                tag.getIntOr("Emeralds", 0), tag.getLongOr("PaidAt", 0L));
    }
}
