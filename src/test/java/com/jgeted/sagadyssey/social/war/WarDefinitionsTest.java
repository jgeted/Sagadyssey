package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.WarKey;
import com.jgeted.sagadyssey.social.war.domain.WarDefinitions;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WarDefinitionsTest {
    private static WarKey key(String path) { return new WarKey("test", path); }
    private static WarDefinitions.Definition definition(String id, WarKey... dependencies) {
        return new WarDefinitions.Definition(key(id), WarDefinitions.Kind.MODULE, 1, Set.of(dependencies));
    }

    @Test void keysAreStableAndRejectAmbiguousOrUnboundedInput() {
        assertEquals("test:troop/pike", WarKey.parse("test:troop/pike").toString());
        for (String value : List.of("pike", "a:b:c", ":pike", "A:b", "test:", "test:" + "a".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> WarKey.parse(value), value);
        }
    }

    @Test void initialCatalogContains48LoadoutsSixLegacyIdsAndFivePositionOrders() {
        WarDefinitions catalog = InitialWarDefinitions.create();
        assertEquals(48, InitialWarDefinitions.TROOPS.size());
        assertEquals(6, InitialWarDefinitions.LEGACY_TROOPS.size());
        assertEquals(54, catalog.entries().values().stream().filter(d -> d.kind() == WarDefinitions.Kind.TROOP).count());
        assertEquals(5, catalog.entries().values().stream().filter(d -> d.kind() == WarDefinitions.Kind.ORDER).count());
        assertTrue(catalog.supports(InitialWarDefinitions.RETREAT,WarDefinitions.Kind.ORDER,1));
        assertTrue(catalog.supports(InitialWarDefinitions.RALLY,WarDefinitions.Kind.ORDER,1));
        assertTrue(catalog.supports(InitialWarDefinitions.CHARGE,WarDefinitions.Kind.ORDER,1));
        assertFalse(catalog.supports(InitialWarDefinitions.MOVE, WarDefinitions.Kind.TROOP, 1));
        assertFalse(catalog.supports(InitialWarDefinitions.MOVE, WarDefinitions.Kind.ORDER, 2));
        for (var legacy : InitialWarDefinitions.LEGACY_TROOPS) assertTrue(catalog.supports(legacy, WarDefinitions.Kind.TROOP, 1));
    }

    @Test void duplicateMissingAndCyclicRegistrationsCannotBecomeUsable() {
        var duplicate = new WarDefinitions.Builder().register(definition("a"));
        assertThrows(IllegalArgumentException.class, () -> duplicate.register(definition("a")));
        var missing = new WarDefinitions.Builder().register(definition("b", key("a")));
        assertThrows(IllegalArgumentException.class, missing::freeze);
        var cycle = new WarDefinitions.Builder().register(definition("a", key("b"))).register(definition("b", key("a")));
        assertThrows(IllegalArgumentException.class, cycle::freeze);
    }

    @Test void freezingIsDeterministicAndCannotBeMutatedAfterwards() {
        var builder = new WarDefinitions.Builder().register(definition("c", key("a")))
                .register(definition("b")).register(definition("a"));
        var frozen = builder.freeze();
        assertEquals(List.of(key("a"), key("b"), key("c")), new ArrayList<>(frozen.entries().keySet()));
        assertThrows(IllegalStateException.class, builder::freeze);
        assertThrows(IllegalStateException.class, () -> builder.register(definition("d")));
        assertThrows(UnsupportedOperationException.class, () -> frozen.entries().clear());
    }

    @Test void aLongDependencyChainIsValidatedWithoutRecursiveTraversal() {
        var builder = new WarDefinitions.Builder();
        for (int i = 0; i < 3000; i++) {
            builder.register(i == 0 ? definition("m0") : definition("m" + i, key("m" + (i - 1))));
        }
        assertEquals(3000, builder.freeze().entries().size());
    }
}
