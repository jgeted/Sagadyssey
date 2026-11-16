package com.jgeted.sagadyssey.social.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
    void massWithdrawalKeepsAllEventIdsAndMembershipAcrossALoad() {
        var actor = UUID.randomUUID();
        var opponent = UUID.randomUUID();
        var data = new CombatEncounterData();
        var morale = new CombatMoraleData();
        var encounter = CombatEncounterService.start(data, UUID.randomUUID(), null, "minecraft:overworld",
                BlockPos.ZERO, 32, List.of(new CombatSide(UUID.randomUUID(), "A", null, Set.of(actor), EngagementRule.LETHAL_FORCE),
                        new CombatSide(UUID.randomUUID(), "B", null, Set.of(opponent), EngagementRule.LETHAL_FORCE)), 0);

        for (int tick = 1; tick <= 300; tick++) {
            var receipt = new EncounterEvent(new UUID(0, tick), encounter.id(), EncounterEventType.RETREATED,
                    actor, null, null, tick, "withdrawal");
            CombatEncounterService.record(data, morale, encounter.id(), receipt);
            CombatEncounterService.record(data, morale, encounter.id(), receipt);
        }
        var stored = data.activeFor(actor, 300);
        assertEquals(300, stored.events().size());
        assertEquals(stored, data.activeFor(opponent, 300));
        var loaded = CombatEncounterData.load(data.save(new CompoundTag(), null), null);
        assertEquals(stored, loaded.activeFor(actor, 300));
    }

    @Test
    void aFullEventWindowContinuesAfterSettlingWithoutChangingItsHostOrHistory() {
        var actor = UUID.randomUUID();
        var opponent = UUID.randomUUID();
        var data = new CombatEncounterData();
        var id = UUID.randomUUID();
        var sides = List.of(new CombatSide(UUID.randomUUID(), "A", null, Set.of(actor), EngagementRule.LETHAL_FORCE),
                new CombatSide(UUID.randomUUID(), "B", null, Set.of(opponent), EngagementRule.SELF_DEFENSE_ONLY));
        var binding = new CombatEncounterBinding("sagadyssey:test", UUID.randomUUID(), actor, id, sides.getFirst().id());
        var events = java.util.stream.IntStream.range(0, CombatEncounter.MAX_EVENTS)
                .mapToObj(n -> new EncounterEvent(new UUID(0, n + 1), id, EncounterEventType.RETREATED,
                        actor, null, null, n + 1, "withdrawal")).toList();
        var full = new CombatEncounter(id, null, "minecraft:overworld", BlockPos.ZERO, 32, 0, 0,
                EncounterStatus.ACTIVE, sides, events, events.size());
        assertTrue(data.add(full, binding));
        assertTrue(data.canContinue(full));
        assertThrows(IllegalStateException.class, () -> data.continueAfter(full, 1025));
        data.freeze(id, () -> List.of(new CombatImpact(id, actor, null, null, null, 0, 0, 0, true, 1025)));
        data.update(new CombatEncounter(id, null, full.dimension(), full.center(), full.radius(), 0, 1025,
                EncounterStatus.ENDED, sides, events, full.revision() + 1));
        var next = data.continueAfter(full, 1025);
        assertEquals(next, data.activeFor(actor, 1025));
        assertEquals(sides, next.sides());
        assertEquals(events, data.encounter(id).events());
        assertEquals(actor, data.binding(next.id(), binding.ownerKind(), binding.owner()).principal());
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
