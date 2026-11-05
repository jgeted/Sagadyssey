package com.jgeted.sagadyssey.social.combat;

import javax.annotation.Nullable;
import java.util.UUID;

public final class InjuryService {
    private InjuryService() { }

    public static WoundRecord recordHit(InjuryData injuries, UUID woundId,
                                        UUID patientCharacterId,
                                        @Nullable UUID attackerCharacterId,
                                        WoundType type, float observedDamage,
                                        double armorCoverage, long now,
                                        @Nullable UUID sourceEvidenceId) {
        if (observedDamage <= 0.0F) throw new IllegalArgumentException("damage must be positive");
        double protection = Math.max(0.0D, Math.min(0.85D, armorCoverage));
        double effective = observedDamage * (1.0D - protection * 0.35D);
        WoundSeverity severity = effective < 2.0D ? WoundSeverity.MINOR
                : effective < 5.0D ? WoundSeverity.MODERATE
                : effective < 9.0D ? WoundSeverity.SEVERE : WoundSeverity.CRITICAL;
        BodyRegion[] regions = BodyRegion.values();
        int regionIndex = Math.floorMod((int) (woundId.getMostSignificantBits()
                ^ woundId.getLeastSignificantBits()), regions.length);
        BodyRegion region = regions[regionIndex];
        int severityFactor = severity.ordinal() + 1;
        int trauma = Math.min(100, (int) Math.ceil(effective * 7.0D) + severityFactor * 4);
        int bleedingBase = switch (type) {
            case CUT, PUNCTURE -> 14;
            case FRACTURE -> 8;
            case BURN -> 5;
            default -> 2;
        };
        int bleeding = Math.max(0, bleedingBase * severityFactor
                - (int) Math.round(protection * 20.0D));
        int infectionRisk = switch (type) {
            case PUNCTURE -> 18 + severityFactor * 8;
            case CUT, BURN -> 10 + severityFactor * 7;
            default -> 5 + severityFactor * 4;
        };
        WoundRecord wound = new WoundRecord(woundId, patientCharacterId,
                attackerCharacterId, type, region, severity, trauma, bleeding,
                infectionRisk, 0, Math.max(0, 45 - severityFactor * 9),
                now, now, WoundStatus.ACTIVE, sourceEvidenceId, 0);
        if (!injuries.add(wound)) throw new IllegalStateException("wound id already exists");
        return wound;
    }

    public static WoundType classify(String damageMessageId) {
        String id = damageMessageId == null ? "" : damageMessageId.toLowerCase(java.util.Locale.ROOT);
        if (id.contains("arrow") || id.contains("trident")) return WoundType.PUNCTURE;
        if (id.contains("fire") || id.contains("lava") || id.contains("burn")) return WoundType.BURN;
        if (id.contains("magic") || id.contains("wither")) return WoundType.MAGIC;
        if (id.contains("poison")) return WoundType.POISON;
        if (id.contains("fall") || id.contains("cram") || id.contains("explosion")) return WoundType.BLUNT;
        return WoundType.CUT;
    }
}
