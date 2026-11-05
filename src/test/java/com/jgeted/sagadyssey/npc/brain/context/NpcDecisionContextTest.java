package com.jgeted.sagadyssey.npc.brain.context;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NpcDecisionContextTest {
    @Test
    void perceptionDefensivelyCopiesEverySet() {
        UUID visibleNpc = UUID.randomUUID();
        Set<UUID> visibleCharacters = new HashSet<>(Set.of(visibleNpc));
        Set<String> locations = new HashSet<>(Set.of("village:forge"));
        Set<String> facts = new HashSet<>(Set.of("order:iron_pickaxe"));

        NpcPerceptionSnapshot snapshot = new NpcPerceptionSnapshot(10L, visibleCharacters, locations, facts);
        visibleCharacters.clear();
        locations.clear();
        facts.clear();

        assertEquals(Set.of(visibleNpc), snapshot.visibleCharacters());
        assertEquals(Set.of("village:forge"), snapshot.knownLocationKeys());
        assertEquals(Set.of("order:iron_pickaxe"), snapshot.knownFactKeys());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.knownLocationKeys().add("village:market"));
    }

    @Test
    void contextDefensivelyCopiesPrerequisitesAndModifiers() {
        Set<String> prerequisites = new HashSet<>(Set.of("has_hammer"));
        Map<String, Double> modifiers = new HashMap<>(Map.of("work", 3.5D));
        NpcDecisionContext context = new NpcDecisionContext(
                UUID.randomUUID(), 20L, 30L, emptyPerception(), prerequisites, modifiers);

        prerequisites.clear();
        modifiers.clear();

        assertEquals(Set.of("has_hammer"), context.satisfiedPrerequisites());
        assertEquals(Map.of("work", 3.5D), context.utilityModifiers());
        assertThrows(UnsupportedOperationException.class,
                () -> context.utilityModifiers().put("rest", 1.0D));
    }

    @Test
    void contextAndPerceptionRejectNullCollectionsAndInvalidModifierValues() {
        assertThrows(NullPointerException.class,
                () -> new NpcPerceptionSnapshot(0L, null, Set.of(), Set.of()));
        assertThrows(NullPointerException.class,
                () -> new NpcDecisionContext(UUID.randomUUID(), 0L, 0L,
                        emptyPerception(), null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new NpcDecisionContext(UUID.randomUUID(), 0L, 0L,
                        emptyPerception(), Set.of(), Map.of("work", Double.NaN)));
        assertThrows(IllegalArgumentException.class,
                () -> new NpcPerceptionSnapshot(-1L, Set.of(), Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new NpcDecisionContext(UUID.randomUUID(), 5L, 0L,
                        new NpcPerceptionSnapshot(6L, Set.of(), Set.of(), Set.of()),
                        Set.of(), Map.of()));
    }

    private static NpcPerceptionSnapshot emptyPerception() {
        return new NpcPerceptionSnapshot(0L, Set.of(), Set.of(), Set.of());
    }
}
