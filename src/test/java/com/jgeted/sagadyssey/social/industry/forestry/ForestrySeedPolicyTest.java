package com.jgeted.sagadyssey.social.industry.forestry;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ForestrySeedPolicyTest {
    @Test void singleSeedFamiliesResolveToTheirNativePublicLogTags() {
        for (String family : List.of("oak", "spruce", "birch", "jungle", "acacia", "cherry")) {
            assertEquals(Identifier.parse("minecraft:" + family + "_logs"),
                    ForestrySeedPolicy.logTag(Identifier.parse("minecraft:" + family + "_sapling")).orElseThrow());
        }
    }

    @Test void multiSeedAndDifferentPlantingSemanticsAreNotAdvertised() {
        for (String item : List.of("dark_oak_sapling", "mangrove_propagule", "azalea", "crimson_fungus", "oak_log")) {
            assertTrue(ForestrySeedPolicy.logTag(Identifier.parse("minecraft:" + item)).isEmpty(), item);
        }
    }
}
