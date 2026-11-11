package com.jgeted.sagadyssey.social.combat;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/** Persistent trauma independent of vanilla health regeneration. */
public record WoundRecord(UUID id, UUID patientCharacterId,
                          @Nullable UUID attackerCharacterId, WoundType type,
                          BodyRegion region, WoundSeverity severity,
                          int trauma, int bleeding, int infectionRisk, int infection,
                          int stability, long inflictedAt, long lastUpdatedAt,
                          WoundStatus status, @Nullable UUID sourceEvidenceId,
                          int revision) {
    public static final long PROGRESSION_STEP = 1_200L;

    public WoundRecord {
        if (id == null || patientCharacterId == null || type == null || region == null
                || severity == null || status == null || revision < 0
                || lastUpdatedAt < inflictedAt) throw new IllegalArgumentException("invalid wound");
        trauma = clamp(trauma);
        bleeding = clamp(bleeding);
        infectionRisk = clamp(infectionRisk);
        infection = clamp(infection);
        stability = clamp(stability);
    }

    public WoundRecord progress(long now) {
        if (!status.active() || now <= lastUpdatedAt) return this;
        long steps = Math.max(0L, (now - lastUpdatedAt) / PROGRESSION_STEP);
        if (steps == 0L) return this;
        int nextTrauma = trauma;
        int nextInfection = infection;
        int nextStability = stability;
        WoundStatus nextStatus = status;
        if (status == WoundStatus.ACTIVE) {
            nextTrauma = clamp(nextTrauma + (int) Math.min(100L,
                    steps * Math.max(0, bleeding - 10) / 12L));
            nextInfection = clamp(nextInfection + (int) Math.min(100L,
                    steps * Math.max(0, infectionRisk - stability / 2) / 35L));
            nextStability = clamp(nextStability - (int) Math.min(20L, steps));
        } else {
            nextTrauma = clamp(nextTrauma - (int) Math.min(trauma, steps * 2L));
            nextInfection = clamp(nextInfection - (int) Math.min(infection, steps));
            nextStability = clamp(nextStability + (int) Math.min(100 - stability, steps));
            if (nextTrauma <= 10 && nextInfection <= 5) {
                nextStatus = severity == WoundSeverity.CRITICAL || severity == WoundSeverity.SEVERE
                        ? WoundStatus.SCARRED : WoundStatus.HEALED;
            } else if (nextTrauma < 50 && nextInfection < 50) {
                nextStatus = WoundStatus.HEALING;
            }
        }
        if (nextTrauma >= 100 || nextInfection >= 100) nextStatus = WoundStatus.FATAL;
        return copy(nextTrauma, bleeding, infectionRisk, nextInfection, nextStability,
                now, nextStatus);
    }

    public WoundRecord treat(TreatmentType treatment, long now) {
        if (!status.active()) throw new IllegalStateException("伤口已经无法治疗或已痊愈");
        WoundRecord current = progress(now);
        int nextTrauma = current.trauma;
        int nextBleeding = current.bleeding;
        int nextRisk = current.infectionRisk;
        int nextInfection = current.infection;
        int nextStability = current.stability;
        switch (treatment) {
            case BANDAGE -> {
                nextBleeding = clamp(nextBleeding - 55);
                nextStability = clamp(nextStability + 35);
            }
            case SPLINT -> {
                if (type != WoundType.FRACTURE && type != WoundType.BLUNT) {
                    throw new IllegalArgumentException("夹板不适用于这种伤口");
                }
                nextTrauma = clamp(nextTrauma - 8);
                nextStability = clamp(nextStability + 45);
            }
            case ANTISEPTIC -> {
                nextRisk = clamp(nextRisk - 55);
                nextInfection = clamp(nextInfection - 45);
                nextStability = clamp(nextStability + 15);
            }
        }
        WoundStatus next = current.status;
        if (next != WoundStatus.FATAL && nextBleeding <= 20 && nextStability >= 45) {
            next = nextTrauma < 50 && nextInfection < 50
                    ? WoundStatus.HEALING : WoundStatus.STABILIZED;
        }
        return copy(nextTrauma, nextBleeding, nextRisk, nextInfection,
                nextStability, now, next);
    }

    private WoundRecord copy(int nextTrauma, int nextBleeding, int nextRisk,
                             int nextInfection, int nextStability, long now,
                             WoundStatus nextStatus) {
        return new WoundRecord(id, patientCharacterId, attackerCharacterId, type, region,
                severity, nextTrauma, nextBleeding, nextRisk, nextInfection, nextStability,
                inflictedAt, now, nextStatus, sourceEvidenceId, revision + 1);
    }

    private static int clamp(int value) { return Math.max(0, Math.min(100, value)); }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Patient", UUIDUtil.CODEC, patientCharacterId);
        if (attackerCharacterId != null) tag.store("Attacker", UUIDUtil.CODEC, attackerCharacterId);
        tag.putString("Type", type.name());
        tag.putString("Region", region.name());
        tag.putString("Severity", severity.name());
        tag.putInt("Trauma", trauma);
        tag.putInt("Bleeding", bleeding);
        tag.putInt("InfectionRisk", infectionRisk);
        tag.putInt("Infection", infection);
        tag.putInt("Stability", stability);
        tag.putLong("InflictedAt", inflictedAt);
        tag.putLong("LastUpdatedAt", lastUpdatedAt);
        tag.putString("Status", status.name());
        if (sourceEvidenceId != null) tag.store("SourceEvidence", UUIDUtil.CODEC, sourceEvidenceId);
        tag.putInt("Revision", revision);
        return tag;
    }

    static WoundRecord load(CompoundTag tag) {
        return new WoundRecord(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Patient", UUIDUtil.CODEC).orElseThrow(),
                tag.read("Attacker", UUIDUtil.CODEC).isPresent() ? tag.read("Attacker", UUIDUtil.CODEC).orElseThrow() : null,
                WoundType.valueOf(tag.getStringOr("Type", "")),
                BodyRegion.valueOf(tag.getStringOr("Region", "")),
                WoundSeverity.valueOf(tag.getStringOr("Severity", "")), tag.getIntOr("Trauma", 0),
                tag.getIntOr("Bleeding", 0), tag.getIntOr("InfectionRisk", 0),
                tag.getIntOr("Infection", 0), tag.getIntOr("Stability", 0),
                tag.getLongOr("InflictedAt", 0L), tag.getLongOr("LastUpdatedAt", 0L),
                WoundStatus.valueOf(tag.getStringOr("Status", "")),
                tag.read("SourceEvidence", UUIDUtil.CODEC).isPresent() ? tag.read("SourceEvidence", UUIDUtil.CODEC).orElseThrow() : null,
                tag.getIntOr("Revision", 0));
    }
}
