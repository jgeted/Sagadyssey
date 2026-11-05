package com.jgeted.sagadyssey.social.combat;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CombatEncounterServiceTest {
    @Test
    void rulesOfEngagementPreventFriendlyFireAndPreferArrestWhenOrdered() {
        UUID guardA = UUID.randomUUID();
        UUID guardB = UUID.randomUUID();
        UUID bandit = UUID.randomUUID();
        CombatEncounterData data = new CombatEncounterData();
        CombatEncounter encounter = CombatEncounterService.start(data, UUID.randomUUID(),
                UUID.randomUUID(), "minecraft:overworld", BlockPos.ZERO, 32,
                List.of(new CombatSide(UUID.randomUUID(), "守备队", UUID.randomUUID(),
                                Set.of(guardA, guardB), EngagementRule.ARREST_IF_POSSIBLE),
                        new CombatSide(UUID.randomUUID(), "劫掠者", null,
                                Set.of(bandit), EngagementRule.LETHAL_FORCE)), 0L);

        assertEquals(EngagementAction.IGNORE,
                CombatEncounterService.engagement(encounter, guardA, guardB, true).action());
        assertEquals(EngagementAction.ARREST,
                CombatEncounterService.engagement(encounter, guardA, bandit, false).action());
        assertEquals(EngagementAction.IGNORE,
                CombatEncounterService.engagement(encounter, guardA,
                        UUID.randomUUID(), false).action());
    }

    @Test
    void casualtyEvidencePropagatesMoraleOnlyToTheSameCombatSide() {
        UUID allyA = UUID.randomUUID();
        UUID allyB = UUID.randomUUID();
        UUID enemy = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        CombatEncounterData encounters = new CombatEncounterData();
        CombatMoraleData morale = new CombatMoraleData();
        CombatEncounterService.start(encounters, encounterId, null,
                "minecraft:overworld", BlockPos.ZERO, 24,
                List.of(new CombatSide(UUID.randomUUID(), "A", null,
                                Set.of(allyA, allyB), EngagementRule.SELF_DEFENSE_ONLY),
                        new CombatSide(UUID.randomUUID(), "B", null,
                                Set.of(enemy), EngagementRule.LETHAL_FORCE)), 0L);
        EncounterEvent death = new EncounterEvent(UUID.randomUUID(), encounterId,
                EncounterEventType.KILLED, allyA, enemy, UUID.randomUUID(), 10L, "阵亡");

        CombatEncounterService.record(encounters, morale, encounterId, death);
        CombatEncounterService.record(encounters, morale, encounterId, death);

        assertEquals(30, morale.profile(allyB, 10L).current());
        assertEquals(50, morale.profile(enemy, 10L).current());
        assertEquals(EncounterStatus.ACTIVE, encounters.encounter(encounterId).status());
    }

    @Test
    void selfDefenseRuleNeedsAnImmediateThreat() {
        UUID defender = UUID.randomUUID();
        UUID opponent = UUID.randomUUID();
        CombatEncounter encounter = CombatEncounterService.start(new CombatEncounterData(),
                UUID.randomUUID(), null, "minecraft:overworld", BlockPos.ZERO, 16,
                List.of(new CombatSide(UUID.randomUUID(), "居民", null,
                                Set.of(defender), EngagementRule.SELF_DEFENSE_ONLY),
                        new CombatSide(UUID.randomUUID(), "陌生武装", null,
                                Set.of(opponent), EngagementRule.LETHAL_FORCE)), 0L);
        assertEquals(EngagementAction.IGNORE,
                CombatEncounterService.engagement(encounter, defender, opponent, false).action());
        assertEquals(EngagementAction.DEFEND,
                CombatEncounterService.engagement(encounter, defender, opponent, true).action());
    }
}
