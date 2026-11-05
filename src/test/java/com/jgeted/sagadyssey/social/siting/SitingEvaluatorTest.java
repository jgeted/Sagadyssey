package com.jgeted.sagadyssey.social.siting;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.jgeted.sagadyssey.social.industry.common.IndustryContract.*;
import static com.jgeted.sagadyssey.social.siting.SitingSnapshot.*;
import static com.jgeted.sagadyssey.social.siting.SitingEvaluator.Verdict.*;

class SitingEvaluatorTest {
    private final UUID home=UUID.randomUUID();
    private Household h(UUID id,int food,boolean known) { return new Household(id,new Stock(food,0,0,0,known,true),.01,3,3,100,200,known); }
    private SitingSnapshot s(Mode mode,List<Household> h,List<Arrival> a) { return new SitingSnapshot("candidate",mode,100,1000,true,true,h,a); }
    @Test void starvationBeforeFirstHarvestCannotBeRepaidByLaterAbundance() {
        var r=SitingEvaluator.evaluate(s(Mode.CONTROLLED_NEAR,List.of(h(home,4,true)),List.of(new Arrival("harvest",home,"food.carrot",500,100,ArrivalKind.VERIFIED_PRODUCTION_FORECAST))));
        assertEquals(FAIL,r.verdict());assertTrue(r.findings().stream().anyMatch(x->x.reason().equals("FOOD_GAP_BEFORE_PRODUCTION")));
    }
    @Test void verifiedForecastIsSeparateFromPhysicalInventory() {
        var r=SitingEvaluator.evaluate(s(Mode.CONTROLLED_NEAR,List.of(h(home,6,true)),List.of(new Arrival("harvest",home,"food.carrot",500,10,ArrivalKind.VERIFIED_PRODUCTION_FORECAST))));
        assertEquals(PASS,r.verdict());assertEquals(6,r.physicalFoodNow().get(home));
    }
    @Test void unfulfilledImportCannotFillCurrentOrFutureFoodGap() {
        var r=SitingEvaluator.evaluate(s(Mode.CONTROLLED_NEAR,List.of(h(home,4,true)),List.of(new Arrival("import",home,"logistics.delivery",100,100,ArrivalKind.UNDELIVERED_IMPORT))));
        assertEquals(FAIL,r.verdict());assertEquals(4,r.physicalFoodNow().get(home));
    }
    @Test void unimplementedFishIsPotentialNotFood() {
        var r=SitingEvaluator.evaluate(s(Mode.CONTROLLED_NEAR,List.of(h(home,4,true)),List.of(new Arrival("fish",home,"fish.capture",100,100,ArrivalKind.VERIFIED_PRODUCTION_FORECAST))));
        assertEquals(UNKNOWN,r.verdict());assertTrue(r.potentialCapabilities().contains("fish.capture"));assertEquals(4,r.physicalFoodNow().get(home));
    }
    @Test void worldgenCannotBorrowControlledNearProof() {
        var r=SitingEvaluator.evaluate(s(Mode.WORLDGEN,List.of(h(home,4,true)),List.of(new Arrival("harvest",home,"food.carrot",100,100,ArrivalKind.VERIFIED_PRODUCTION_FORECAST))));
        assertEquals(UNKNOWN,r.verdict());
    }
    @Test void checkHouseholdsSeparatelyNotVillageAverage() {
        var other=UUID.randomUUID();var r=SitingEvaluator.evaluate(s(Mode.CONTROLLED_NEAR,List.of(h(home,1,true),h(other,100,true)),List.of()));
        assertEquals(FAIL,r.verdict());assertTrue(r.findings().stream().anyMatch(x->home.equals(x.household())));
    }
    @Test void missingKnowledgeIsUnknownNotZeroInventory() {
        assertEquals(UNKNOWN,SitingEvaluator.evaluate(s(Mode.CONTROLLED_NEAR,List.of(h(home,0,false)),List.of())).verdict());
    }
    @Test void spareFoodCannotReplaceBedsOrLabour() {
        var h=new Household(home,new Stock(100,0,0,0,true,true),.01,3,2,300,200,true);
        var r=SitingEvaluator.evaluate(s(Mode.CONTROLLED_NEAR,List.of(h),List.of()));assertEquals(FAIL,r.verdict());
        assertTrue(r.findings().stream().anyMatch(x->x.reason().contains("BEDS")));assertTrue(r.findings().stream().anyMatch(x->x.reason().contains("LABOUR")));
    }
    @Test void duplicateHouseholdOrForecastCannotMultiplyPopulationOrGoods() {
        assertThrows(IllegalArgumentException.class,()->s(Mode.CONTROLLED_NEAR,List.of(h(home,10,true),h(home,10,true)),List.of()));
        var a=new Arrival("original",home,"food.carrot",100,10,ArrivalKind.VERIFIED_PRODUCTION_FORECAST);
        assertThrows(IllegalArgumentException.class,()->s(Mode.CONTROLLED_NEAR,List.of(h(home,10,true)),List.of(a,a)));
    }
    @Test void readsAreRepeatableAndCannotChangeCandidateState() {
        var snapshot=s(Mode.CONTROLLED_NEAR,List.of(h(home,10,true)),List.of());
        assertEquals(SitingEvaluator.evaluate(snapshot),SitingEvaluator.evaluate(snapshot));
        assertThrows(UnsupportedOperationException.class,()->snapshot.households().clear());
    }
}
