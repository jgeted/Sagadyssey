package com.jgeted.sagadyssey.npc.faction;

import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactionStandingsDiscoveryTest {
    @Test
    void aNewPlayerOnlyKnowsTheirOwnFaction() {
        FactionStandings standings = new FactionStandings();

        assertEquals(java.util.Set.of("sagadyssey:player"), standings.getKnownFactionIds());
        assertFalse(standings.isFactionKnown("sagadyssey:kingdom"));
    }

    @Test
    void discoveredFactionsPersistWithoutExposingUnrelatedStandings() {
        FactionStandings standings = new FactionStandings();
        assertTrue(standings.discoverFaction("sagadyssey:kingdom"));
        assertFalse(standings.discoverFaction("sagadyssey:kingdom"));

        var encoded = FactionStandings.CODEC.encodeStart(NbtOps.INSTANCE, standings).getOrThrow();
        FactionStandings decoded = FactionStandings.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

        assertEquals(java.util.Set.of("sagadyssey:player", "sagadyssey:kingdom"),
                decoded.getKnownFactionIds());
        assertFalse(decoded.isFactionKnown("sagadyssey:bandit"));
    }

    @Test
    void newCharacterForgetsWorldKnowledgeButKeepsSelfIdentity() {
        FactionStandings standings = new FactionStandings();
        standings.discoverFaction("sagadyssey:merchant_guild");

        standings.resetForNewCharacter();

        assertEquals(java.util.Set.of("sagadyssey:player"), standings.getKnownFactionIds());
    }
}
