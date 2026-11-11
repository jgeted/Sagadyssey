package com.jgeted.sagadyssey.npc.faction.command;

import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FactionCommandArgumentTest {

    @Test
    void parsesAdvertisedNamespacedFactionId() throws Exception {
        Identifier parsed = IdentifierArgument.id()
                .parse(new StringReader("sagadyssey:kingdom"));

        assertEquals("sagadyssey:kingdom", FactionCommand.normalizeFactionId(parsed));
    }

    @Test
    void keepsLegacyShortFactionIdWorking() throws Exception {
        Identifier parsed = IdentifierArgument.id()
                .parse(new StringReader("kingdom"));

        assertEquals("kingdom", FactionCommand.normalizeFactionId(parsed));
    }
}
