package com.jgeted.sagadyssey.social.industry;

import org.junit.jupiter.api.Test;
import net.minecraft.resources.ResourceLocation;
import static org.junit.jupiter.api.Assertions.*;

class IndustryDiscoveryTest {
    private void check(String phase,int expectedMain,int expectedForest) {
        var a=System.getProperty("sagadyssey.industries.enabled");var b=System.getProperty("sagadyssey.industries.phase");
        try {
            System.setProperty("sagadyssey.industries.enabled","true");System.setProperty("sagadyssey.industries.phase",phase);
            var main=IndustryIntegrationGameTests.tests();var forest=IndustryForestryGameTests.tests();
            assertEquals(expectedMain,main.size());assertEquals(expectedForest,forest.size());
            var all=new java.util.ArrayList<>(main);all.addAll(forest);
            assertTrue(all.stream().allMatch(t->ResourceLocation.parse(t.structureName()).getNamespace().equals("sagadyssey_industries")),
                    "NeoForge filters generated functions by structure namespace, not only holder annotation");
        } finally {
            if(a==null)System.clearProperty("sagadyssey.industries.enabled");else System.setProperty("sagadyssey.industries.enabled",a);
            if(b==null)System.clearProperty("sagadyssey.industries.phase");else System.setProperty("sagadyssey.industries.phase",b);
        }
    }
    @Test void baselineSelectsOnlySixFrozenFunctions(){check("baseline",6,0);}
    @Test void eachForestPhaseSelectsExactlyOne(){check("forest-start",0,1);check("forest-resume",0,1);}
}
