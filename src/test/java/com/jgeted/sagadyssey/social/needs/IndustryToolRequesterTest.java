package com.jgeted.sagadyssey.social.needs;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class IndustryToolRequesterTest {
    @Test void existingFarmerHoeRequestReachesNamedManufacturingWithoutBecomingAWorkshopOperator(){
        assertTrue(SupplyFlowService.industryToolRequester(NpcProfession.FARMER,"minecraft:iron_hoe"));
        assertFalse(SupplyFlowService.industryToolRequester(NpcProfession.FARMER,"minecraft:iron_axe"));
        assertFalse(SupplyFlowService.industryToolRequester(NpcProfession.FARMER,"minecraft:shears"));
    }
    @Test void originalWorkerToolsStaySupportedAndUnrelatedRolesOrProductsStayClosed(){
        assertTrue(SupplyFlowService.industryToolRequester(NpcProfession.WORKER,"minecraft:iron_axe"));
        assertTrue(SupplyFlowService.industryToolRequester(NpcProfession.WORKER,"minecraft:shears"));
        assertFalse(SupplyFlowService.industryToolRequester(NpcProfession.COURIER,"minecraft:iron_hoe"));
        assertFalse(SupplyFlowService.industryToolRequester(NpcProfession.WORKER,"minecraft:diamond"));
    }
}
