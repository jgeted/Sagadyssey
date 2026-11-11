package com.jgeted.sagadyssey.social.life;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * 有期限、有实物工资的外派合同。工资单位固定为绿宝石，避免凭空创造抽象货币。
 * 欠薪和收款箱不可用分开处理：后者不会被算作雇主违约。
 */
public record NpcEmploymentContract(
        UUID npcId,
        UUID employerCharacterId,
        UUID settlementId,
        int wageEmeralds,
        long payPeriodTicks,
        long startedAt,
        long endsAt,
        long nextPaymentAt,
        int unpaidEmeralds,
        int missedPayments,
        NpcContractStatus status,
        String statusReason,
        long statusChangedAt) {

    public static final long DEFAULT_PAY_PERIOD = 24_000L;
    public static final int DEFAULT_DURATION_DAYS = 7;
    public static final int DEFAULT_WAGE = 2;
    public static final int BREACH_AFTER_MISSED_PAYMENTS = 3;

    public NpcEmploymentContract {
        wageEmeralds = Math.max(1, Math.min(64, wageEmeralds));
        payPeriodTicks = Math.max(1_200L, payPeriodTicks);
        endsAt = Math.max(startedAt + payPeriodTicks, endsAt);
        nextPaymentAt = Math.max(startedAt, nextPaymentAt);
        unpaidEmeralds = Math.max(0, unpaidEmeralds);
        missedPayments = Math.max(0, missedPayments);
        status = status == null ? NpcContractStatus.ACTIVE : status;
        statusReason = statusReason == null ? "" : statusReason;
    }

    public static NpcEmploymentContract begin(UUID npcId, UUID employerCharacterId,
                                              UUID settlementId, int days, int wage,
                                              long gameTime) {
        long duration = Math.max(1, Math.min(30, days)) * 24_000L;
        return new NpcEmploymentContract(npcId, employerCharacterId, settlementId, wage,
                DEFAULT_PAY_PERIOD, gameTime, gameTime + duration,
                gameTime + DEFAULT_PAY_PERIOD, 0, 0, NpcContractStatus.ACTIVE,
                "合同签订，首笔工资将在一个游戏日后支付", gameTime);
    }

    public boolean scheduledPaymentDue(long gameTime) {
        return gameTime >= nextPaymentAt && nextPaymentAt <= endsAt;
    }

    public int amountDue(long gameTime) {
        int scheduled = scheduledPaymentDue(gameTime) ? wageEmeralds : 0;
        return Math.min(4_096, scheduled + unpaidEmeralds);
    }

    public boolean paymentDue(long gameTime) {
        return status.ongoing() && (scheduledPaymentDue(gameTime) || unpaidEmeralds > 0);
    }

    public boolean readyToComplete(long gameTime) {
        return status.ongoing() && gameTime >= endsAt && nextPaymentAt > endsAt
                && unpaidEmeralds == 0;
    }

    public NpcEmploymentContract paid(int amount, long gameTime) {
        boolean scheduled = scheduledPaymentDue(gameTime);
        int paidTowardArrears = Math.max(0, amount - (scheduled ? wageEmeralds : 0));
        return copy(Math.max(0, unpaidEmeralds - paidTowardArrears), 0,
                NpcContractStatus.ACTIVE, "已向私人储物支付 " + amount + " 枚绿宝石",
                scheduled ? nextPaymentAt + payPeriodTicks : nextPaymentAt, endsAt, gameTime);
    }

    public NpcEmploymentContract paymentBlocked(String reason, long gameTime) {
        return copy(unpaidEmeralds, missedPayments, unpaidEmeralds > 0
                        ? NpcContractStatus.IN_ARREARS : NpcContractStatus.PAYMENT_BLOCKED,
                reason, nextPaymentAt, endsAt, gameTime);
    }

    public NpcEmploymentContract missedPayment(long gameTime) {
        boolean scheduled = scheduledPaymentDue(gameTime);
        int missed = missedPayments + (scheduled ? 1 : 0);
        int unpaid = scheduled ? Math.min(4_096, unpaidEmeralds + wageEmeralds) : unpaidEmeralds;
        NpcContractStatus newStatus = missed >= BREACH_AFTER_MISSED_PAYMENTS
                ? NpcContractStatus.BREACHED : NpcContractStatus.IN_ARREARS;
        String reason = newStatus == NpcContractStatus.BREACHED
                ? "连续三期无法支付工资，合同自动违约"
                : (scheduled ? "本期工资未支付，累计欠薪 " : "工资仍未结清，累计欠薪 ")
                        + unpaid + " 枚绿宝石";
        return copy(unpaid, missed, newStatus, reason,
                scheduled ? nextPaymentAt + payPeriodTicks : nextPaymentAt, endsAt, gameTime);
    }

    public NpcEmploymentContract renewed(int days, long gameTime) {
        long base = Math.max(endsAt, gameTime);
        long extension = Math.max(1, Math.min(30, days)) * 24_000L;
        return copy(unpaidEmeralds, missedPayments,
                status.ongoing() ? status : NpcContractStatus.ACTIVE,
                "合同续期 " + Math.max(1, Math.min(30, days)) + " 个游戏日",
                status.ongoing() ? nextPaymentAt : gameTime + payPeriodTicks,
                base + extension, gameTime);
    }

    public NpcEmploymentContract ended(NpcContractStatus finalStatus, String reason, long gameTime) {
        return copy(unpaidEmeralds, missedPayments, finalStatus, reason,
                nextPaymentAt, endsAt, gameTime);
    }

    private NpcEmploymentContract copy(int unpaid, int missed, NpcContractStatus newStatus,
                                       String reason, long nextPay, long newEndsAt, long changedAt) {
        return new NpcEmploymentContract(npcId, employerCharacterId, settlementId,
                wageEmeralds, payPeriodTicks, startedAt, newEndsAt, nextPay,
                unpaid, missed, newStatus, reason, changedAt);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Npc", UUIDUtil.CODEC, npcId);
        tag.store("Employer", UUIDUtil.CODEC, employerCharacterId);
        tag.store("Settlement", UUIDUtil.CODEC, settlementId);
        tag.putInt("Wage", wageEmeralds);
        tag.putLong("PayPeriod", payPeriodTicks);
        tag.putLong("Started", startedAt);
        tag.putLong("Ends", endsAt);
        tag.putLong("NextPayment", nextPaymentAt);
        tag.putInt("Unpaid", unpaidEmeralds);
        tag.putInt("Missed", missedPayments);
        tag.putString("Status", status.name());
        tag.putString("Reason", statusReason);
        tag.putLong("StatusChanged", statusChangedAt);
        return tag;
    }

    public static NpcEmploymentContract load(CompoundTag tag) {
        return new NpcEmploymentContract(tag.read("Npc", UUIDUtil.CODEC).orElseThrow(), tag.read("Employer", UUIDUtil.CODEC).orElseThrow(),
                tag.read("Settlement", UUIDUtil.CODEC).orElseThrow(), tag.getIntOr("Wage", 0),
                tag.contains("PayPeriod") ? tag.getLongOr("PayPeriod", 0L) : DEFAULT_PAY_PERIOD,
                tag.getLongOr("Started", 0L), tag.getLongOr("Ends", 0L), tag.getLongOr("NextPayment", 0L),
                tag.getIntOr("Unpaid", 0), tag.getIntOr("Missed", 0),
                NpcContractStatus.parse(tag.getStringOr("Status", "")), tag.getStringOr("Reason", ""),
                tag.getLongOr("StatusChanged", 0L));
    }
}
