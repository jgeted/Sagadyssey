package com.jgeted.sagadyssey.social.industry;

import com.jgeted.sagadyssey.social.industry.common.*;
import com.jgeted.sagadyssey.social.industry.food.FoodIndustryAdapter;
import com.jgeted.sagadyssey.social.industry.logistics.IndustrySupplyAdapter;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.jgeted.sagadyssey.social.industry.common.IndustryContract.*;
import static org.junit.jupiter.api.Assertions.*;

class IndustryContractTest {
    private final UUID person=UUID.randomUUID(),body=UUID.randomUUID(),home=UUID.randomUUID(),site=UUID.randomUUID();
    private Demand demand(String key,Stock stock,boolean free,boolean tool,boolean station,boolean route) {
        return new Demand("original-demand",person,body,home,site,100,8,Map.of(key,stock),free,tool,station,route);
    }
    @Test void allEightDomainsArePresentWithoutImplicitExecution() {
        assertEquals(8,IndustryAdapters.all().size());
        assertEquals(8,IndustryAdapters.all().stream().map(a->a.capability().industry()).distinct().count());
        assertThrows(UnsupportedOperationException.class,()->IndustryCatalog.all().clear());
    }
    @Test void overlappingSeedAndReservationProtectionIsSubtractedOnce() {
        assertEquals(3,new Stock(10,4,5,2,true,true).available());
        assertThrows(IllegalArgumentException.class,()->new Stock(5,4,4,0,true,true));
    }
    @Test void sevenAndEightExposeOneAndTwoDisposableUnitsWhenSixAreProtected() {
        var a=new IndustrySupplyAdapter();
        assertEquals(1,a.assess(demand("source:disposable_unit",new Stock(7,6,0,0,true,true),true,true,true,true),Mode.CONTROLLED_NEAR).executableBatches());
        assertEquals(2,a.assess(demand("source:disposable_unit",new Stock(8,6,0,0,true,true),true,true,true,true),Mode.CONTROLLED_NEAR).executableBatches());
    }
    @Test void partialLegalStockProducesPartialOfferNotCompletedDemand() {
        var offer=new IndustrySupplyAdapter().assess(demand("source:disposable_unit",new Stock(9,2,0,0,true,true),true,true,true,true),Mode.CONTROLLED_NEAR);
        assertEquals(State.READY,offer.state());assertEquals(7,offer.executableBatches());
        assertTrue(offer.recheckOn().contains("commit-recheck"));
    }
    @Test void observationNeverReservesStockAndOwnsCopies() {
        var inputs=new HashMap<String,Stock>();inputs.put("world:mature_carrot",new Stock(2,0,0,0,true,true));
        var d=new Demand("d",person,body,home,site,1,2,inputs,true,true,true,true);inputs.clear();
        var a=new FoodIndustryAdapter();assertEquals(a.assess(d,Mode.CONTROLLED_NEAR),a.assess(d,Mode.CONTROLLED_NEAR));
        assertEquals(2,d.inputs().get("world:mature_carrot").available());
        assertThrows(UnsupportedOperationException.class,()->d.inputs().clear());
    }
    @Test void knowingForeignInventoryIsNotDispositionAuthority() {
        var offer=new IndustrySupplyAdapter().assess(demand("source:disposable_unit",new Stock(8,0,0,0,true,false),true,true,true,true),Mode.CONTROLLED_NEAR);
        assertEquals(State.BLOCKED,offer.state());assertTrue(offer.causes().contains(Cause.NO_PERMISSION));assertEquals(0,offer.executableBatches());
    }
    @Test void unknownSourceAndUnloadedModeDoNotBecomeFreeInputs() {
        var a=new FoodIndustryAdapter();var d=demand("world:mature_carrot",new Stock(8,0,0,0,false,true),true,true,true,true);
        assertEquals(State.BLOCKED,a.assess(d,Mode.CONTROLLED_NEAR).state());
        assertEquals(State.UNKNOWN,a.assess(d,Mode.REMOTE).state());
        assertEquals(State.UNKNOWN,a.assess(d,Mode.WORLDGEN).state());
    }
    @Test void pendingIndustriesStayClosedEvenWithAllInputs() {
        for(var a:IndustryAdapters.all()) if(a.capability().approvalRequired()) {
            var key=a.capability().inputPerBatch().keySet().iterator().next();
            var offer=a.assess(demand(key,new Stock(8,0,0,0,true,true),true,true,true,true),Mode.CONTROLLED_NEAR);
            assertEquals(State.POTENTIAL,offer.state());assertEquals(0,offer.executableBatches());
            assertFalse(a.capability().verifiedIn(Mode.CONTROLLED_NEAR));
        }
    }
    @Test void actorToolFacilityAndRouteEachPreventUnlawfulStart() {
        var a=new FoodIndustryAdapter();var stock=new Stock(8,0,0,0,true,true);
        assertTrue(a.assess(demand("world:mature_carrot",stock,false,true,true,true),Mode.CONTROLLED_NEAR).causes().contains(Cause.LABOUR_BUSY));
        assertTrue(a.assess(demand("world:mature_carrot",stock,true,false,true,true),Mode.CONTROLLED_NEAR).causes().contains(Cause.NO_TOOL));
        assertTrue(a.assess(demand("world:mature_carrot",stock,true,true,true,false),Mode.CONTROLLED_NEAR).causes().contains(Cause.ROUTE_BLOCKED));
        var w=IndustryAdapters.all().stream().filter(x->x.capability().industry()==Industry.WORKSHOP).findFirst().orElseThrow();
        assertTrue(w.assess(demand("minecraft:oak_log",stock,true,true,false,true),Mode.CONTROLLED_NEAR).causes().contains(Cause.NO_FACILITY));
    }
    @Test void restoredConditionsRecomputeOriginalDemandWithoutNewIdentity() {
        var a=new FoodIndustryAdapter();var s=new Stock(1,0,0,0,true,true);
        var blocked=a.assess(demand("world:mature_carrot",s,true,false,true,true),Mode.CONTROLLED_NEAR);
        var ready=a.assess(demand("world:mature_carrot",s,true,true,true,true),Mode.CONTROLLED_NEAR);
        assertEquals(State.BLOCKED,blocked.state());assertEquals(State.READY,ready.state());assertEquals(blocked.demandId(),ready.demandId());
    }
    @Test void noRemainingDemandDoesNotCreateWork() {
        var d=new Demand("d",person,body,home,site,1,0,Map.of(),true,true,true,true);
        assertEquals(State.SATISFIED,new FoodIndustryAdapter().assess(d,Mode.CONTROLLED_NEAR).state());
    }
    @Test void requestIdentityIncludesQuantityActorEndpointAndIntent() {
        var a=new OperationIdentity("b","TAKE",1,person,body,"a","b","carrot:components",8,"GIFT");
        assertTrue(a.sameRequestAs(new OperationIdentity("b","TAKE",1,person,body,"a","b","carrot:components",8,"GIFT")));
        assertFalse(a.sameRequestAs(new OperationIdentity("b","TAKE",1,person,body,"a","b","carrot:components",7,"GIFT")));
        assertFalse(a.sameRequestAs(new OperationIdentity("b","TAKE",1,UUID.randomUUID(),body,"a","b","carrot:components",8,"GIFT")));
        assertFalse(a.sameRequestAs(new OperationIdentity("b","TAKE",1,person,body,"a","other","carrot:components",8,"GIFT")));
        assertFalse(a.sameRequestAs(new OperationIdentity("b","TAKE",1,person,body,"a","b","carrot:components",8,"DELIVERY")));
    }
    @Test void labourAuditDetectsCrossIndustryOverlapButNotMachineWaitOrOtherPeople() {
        var a=new WorkClaimRef(person,body,UUID.randomUUID(),10,20,true);
        assertTrue(a.conflictsWith(new WorkClaimRef(person,body,UUID.randomUUID(),15,25,true)));
        assertFalse(a.conflictsWith(new WorkClaimRef(person,body,UUID.randomUUID(),20,25,true)));
        assertFalse(a.conflictsWith(new WorkClaimRef(person,body,UUID.randomUUID(),15,25,false)));
        assertFalse(a.conflictsWith(new WorkClaimRef(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),15,25,true)));
    }
}
