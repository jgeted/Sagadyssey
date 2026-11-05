package com.jgeted.sagadyssey.social.market;

import com.jgeted.sagadyssey.worldsim.rights.AssetRight;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsService;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.RightsVerdict;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Trusted scale and appraisal service. Any later stack, component, count, or slot change invalidates its mark. */
public final class AppraisalService {
    private AppraisalService() { }

    public static MarketScaleMeasurement measure(Container container, int slot) {
        if (container == null || slot < 0 || slot >= container.getContainerSize()) {
            throw new IllegalArgumentException("称量目标无效");
        }
        ItemStack stack = container.getItem(slot);
        if (stack.isEmpty()) throw new IllegalArgumentException("称量位置为空");
        return new MarketScaleMeasurement(ItemFingerprint.of(stack), stack.getCount(),
                stack.getDamageValue(), stack.getMaxDamage(), grade(stack));
    }

    public static AppraisalRecord appraise(AppraisalData appraisals, RightsStateData rights,
                                            SubjectRef appraiser, SubjectRef owner,
                                            UUID containerId, Container container, int slot,
                                            long now, long validityTicks) {
        MarketScaleMeasurement measurement = measure(container, slot);
        RightsVerdict verdict = new RightsService(rights).can(appraiser, AssetRight.ACCESS,
                containerId, slot, measurement.fingerprint(), now);
        if (verdict.status() != RightsVerdict.Status.ALLOW) {
            throw new IllegalStateException("鉴定人没有获准接触这批物品");
        }
        long expiresAt = now + Math.max(1L, validityTicks);
        UUID markId = UUID.randomUUID();
        UUID signature = UUID.nameUUIDFromBytes((markId + ":" + appraiser + ':' + owner + ':'
                + containerId + ':' + slot + ':' + measurement.fingerprint().itemId() + ':'
                + measurement.fingerprint().componentsHash() + ':' + measurement.count() + ':'
                + now).getBytes(StandardCharsets.UTF_8));
        AppraisalRecord record = new AppraisalRecord(markId, appraiser, owner, containerId,
                slot, measurement.fingerprint(), measurement.count(), measurement.damage(),
                measurement.maximumDamage(), measurement.grade(), now, expiresAt, signature);
        appraisals.put(record);
        return record;
    }

    private static QualityGrade grade(ItemStack stack) {
        if (!stack.isDamageableItem() || stack.getMaxDamage() <= 0) return QualityGrade.STANDARD_STACK;
        double damage = stack.getDamageValue() / (double) stack.getMaxDamage();
        if (damage <= 0.05D) return QualityGrade.NEW_OR_FINE;
        if (damage <= 0.35D) return QualityGrade.SERVICEABLE;
        if (damage <= 0.75D) return QualityGrade.WORN;
        return QualityGrade.HEAVILY_DAMAGED;
    }
}
