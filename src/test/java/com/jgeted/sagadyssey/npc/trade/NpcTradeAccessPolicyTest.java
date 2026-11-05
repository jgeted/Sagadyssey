package com.jgeted.sagadyssey.npc.trade;

import com.jgeted.sagadyssey.npc.faction.Faction;
import com.jgeted.sagadyssey.npc.faction.FactionStandings;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcTradeAccessPolicyTest {
    private static final Faction KINGDOM = new Faction("sagadyssey:kingdom", "test.kingdom",
            0x336699, 0, true, true,
            ResourceLocation.fromNamespaceAndPath("sagadyssey", "textures/gui/faction/kingdom.png"),
            Optional.empty(), Optional.empty(), 1.0F, true, 0, 1);

    @Test
    void hatedFactionRefusesTradeAndColdFactionRaisesReserve() {
        FactionStandings standings = new FactionStandings();
        standings.setValue(KINGDOM, -80);
        assertFalse(NpcTradeAccessPolicy.evaluate(standings, KINGDOM, false).allowed());

        standings.setValue(KINGDOM, -40);
        NpcTradeAccessPolicy.Decision cold = NpcTradeAccessPolicy.evaluate(
                standings, KINGDOM, false);
        assertTrue(cold.allowed());
        assertEquals(12_000, cold.priceBasisPoints());
    }

    @Test
    void directServiceRelationshipOverridesFactionPenaltyWithoutGrantingDiscount() {
        FactionStandings standings = new FactionStandings();
        standings.setValue(KINGDOM, -100);

        NpcTradeAccessPolicy.Decision result = NpcTradeAccessPolicy.evaluate(
                standings, KINGDOM, true);

        assertTrue(result.allowed());
        assertEquals(10_000, result.priceBasisPoints());
    }
}
