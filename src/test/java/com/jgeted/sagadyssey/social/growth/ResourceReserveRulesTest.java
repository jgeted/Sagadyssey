package com.jgeted.sagadyssey.social.growth;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourceReserveRulesTest {
    private ResourceReserveRules.Rule rule(String json){return ResourceReserveRules.parse(Identifier.parse("example:material"),JsonParser.parseString(json).getAsJsonObject());}
    @Test void dataDefinesArbitraryPublicTagsInsteadOfConcreteItemIds(){
        var r=rule("{\"source_tag\":\"example:ores/cobalt\",\"stock_tag\":\"c:raw_materials/cobalt\",\"base\":3,\"per_person\":2,\"ceiling\":20}");
        assertEquals("example:ores/cobalt",r.sources().location().toString());
        assertEquals("c:raw_materials/cobalt",r.stock().location().toString());
        assertEquals(9,r.target(3));
    }
    @Test void PopulationAndCeilingAreBoundedWithoutIntegerOverflow(){
        var r=rule("{\"source_tag\":\"c:stones\",\"stock_tag\":\"c:stones\",\"base\":16,\"per_person\":8,\"ceiling\":128}");
        assertEquals(16,r.target(0));assertEquals(40,r.target(3));assertEquals(128,r.target(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->r.target(-1));
    }
    @Test void invalidPolicyFailsRatherThanSilentlyGrantingAnUnlimitedReserve(){
        for(String fields:new String[]{"\"base\":0,\"per_person\":1,\"ceiling\":20","\"base\":8,\"per_person\":-1,\"ceiling\":20","\"base\":8,\"per_person\":1,\"ceiling\":7","\"base\":8,\"per_person\":1,\"ceiling\":5000"})
            assertThrows(IllegalArgumentException.class,()->rule("{\"source_tag\":\"c:stones\",\"stock_tag\":\"c:stones\","+fields+"}"));
    }
}
