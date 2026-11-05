package com.jgeted.sagadyssey.social.combat;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

public final class CombatEncounterService {
    private CombatEncounterService() { }

    public static CombatEncounter start(CombatEncounterData encounters, UUID encounterId,
                                        UUID settlementId, String dimension, BlockPos center,
                                        int radius, List<CombatSide> sides, long now) {
        ensureExclusiveMembership(sides);
        CombatEncounter encounter = new CombatEncounter(encounterId, settlementId,
                dimension, center, radius, now, 0L, EncounterStatus.FORMING,
                sides, List.of(), 0);
        if (!encounters.add(encounter)) throw new IllegalStateException("encounter already exists");
        return encounter;
    }

    public static EngagementDecision engagement(CombatEncounter encounter,
                                                UUID actor, UUID target,
                                                boolean targetIsImmediateThreat) {
        CombatSide actorSide = encounter.sideOf(actor);
        CombatSide targetSide = encounter.sideOf(target);
        if (actorSide == null) return new EngagementDecision(EngagementAction.IGNORE,
                "行动者不属于该遭遇的任何一方");
        if (targetSide != null && actorSide.id().equals(targetSide.id())) {
            return new EngagementDecision(EngagementAction.IGNORE, "同一方成员，禁止误伤");
        }
        if (targetSide == null && !targetIsImmediateThreat) {
            return new EngagementDecision(EngagementAction.IGNORE, "身份未明且未构成立即威胁");
        }
        return switch (actorSide.rule()) {
            case SELF_DEFENSE_ONLY -> targetIsImmediateThreat
                    ? new EngagementDecision(EngagementAction.DEFEND, "仅针对正在发生的攻击自卫")
                    : new EngagementDecision(EngagementAction.IGNORE, "自卫规则不允许主动攻击");
            case PROTECT_PERSONS, PROTECT_LOCATION -> targetIsImmediateThreat
                    ? new EngagementDecision(EngagementAction.DEFEND, "阻止对保护对象的即时威胁")
                    : new EngagementDecision(EngagementAction.IGNORE, "尚未触发保护性武力条件");
            case ARREST_IF_POSSIBLE -> new EngagementDecision(EngagementAction.ARREST,
                    "优先控制和移交裁判，而不是直接杀伤");
            case LETHAL_FORCE -> new EngagementDecision(EngagementAction.ATTACK,
                    "明确敌对方且规则允许致命武力");
        };
    }

    public static CombatEncounter record(CombatEncounterData encounters,
                                         CombatMoraleData morale, UUID encounterId,
                                         EncounterEvent event) {
        CombatEncounter encounter = encounters.encounter(encounterId);
        if (encounter == null) throw new IllegalStateException("encounter missing");
        if (encounters.settling(encounterId)) throw new IllegalStateException("frozen encounter cannot accept new events");
        CombatEncounter updated = encounter.addEvent(event);
        encounters.update(updated);
        CombatSide side = encounter.sideOf(event.subjectCharacterId());
        if (side != null && (event.type() == EncounterEventType.WOUNDED
                || event.type() == EncounterEventType.KILLED
                || event.type() == EncounterEventType.ROUTED)) {
            MoraleEventType type = event.type() == EncounterEventType.KILLED
                    ? MoraleEventType.ALLY_KILLED : event.type() == EncounterEventType.ROUTED
                    ? MoraleEventType.LEADER_FLED : MoraleEventType.ALLY_WOUNDED;
            for (UUID ally : side.members()) {
                if (ally.equals(event.subjectCharacterId())) continue;
                morale.ensure(ally, 50, 50, event.occurredAt());
                morale.record(new MoraleEvent(UUID.nameUUIDFromBytes((event.id() + ":" + ally)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)), ally, encounterId,
                        type, type.defaultDelta(), event.sourceRecordId(),
                        event.occurredAt(), "同一战斗群体事件"));
            }
        }
        return updated;
    }

    private static void ensureExclusiveMembership(List<CombatSide> sides) {
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (CombatSide side : sides) {
            for (UUID member : side.members()) {
                if (!seen.add(member)) throw new IllegalArgumentException("character appears on multiple sides");
            }
        }
    }
}
