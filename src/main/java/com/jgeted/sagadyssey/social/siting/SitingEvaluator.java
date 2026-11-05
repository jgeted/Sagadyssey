package com.jgeted.sagadyssey.social.siting;

import com.jgeted.sagadyssey.social.industry.common.IndustryCatalog;
import java.util.*;

/** Pure D1 evaluator, not a worldgen hook or a migration allocator. No SavedData access. */
public final class SitingEvaluator {
    public enum Verdict { PASS, FAIL, UNKNOWN }
    public record Finding(UUID household,String reason,long atOffset,double foodBalance) {}
    public record Result(String candidate,Verdict verdict,List<Finding> findings,
                         Map<UUID,Integer> physicalFoodNow,Set<String> potentialCapabilities,
                         String boundary) {
        public Result { findings=List.copyOf(findings);physicalFoodNow=Map.copyOf(physicalFoodNow);potentialCapabilities=Set.copyOf(potentialCapabilities); }
    }
    private SitingEvaluator() {}
    public static Result evaluate(SitingSnapshot s) {
        var findings=new ArrayList<Finding>(); var now=new LinkedHashMap<UUID,Integer>();
        var potential=new LinkedHashSet<String>(); boolean unknown=!s.terrainKnown() || s.households().isEmpty();
        boolean fail=!s.lawfulSpace();
        if(!s.lawfulSpace()) findings.add(new Finding(null,"NO_LAWFUL_SPACE",0,0));
        for(var h:s.households()) {
            int initial=h.foodNow().available(); now.put(h.id(),initial);
            if(!h.knowledgeComplete() || !h.foodNow().known()) unknown=true;
            if(h.usableBeds()<h.people()) { fail=true;findings.add(new Finding(h.id(),"INSUFFICIENT_USABLE_BEDS",0,initial)); }
            if(h.labourRequired()>h.labourAvailable()) { fail=true;findings.add(new Finding(h.id(),"INSUFFICIENT_REAL_LABOUR",0,initial)); }
            double balance=initial;long last=0;
            boolean foodEvidenceComplete=h.knowledgeComplete() && h.foodNow().known()
                    && s.arrivals().stream().filter(a->a.household().equals(h.id()) && a.atOffset()<=s.horizonTicks()
                            && a.kind()==SitingSnapshot.ArrivalKind.VERIFIED_PRODUCTION_FORECAST)
                    .allMatch(a->IndustryCatalog.find(a.capability()).filter(c->c.verifiedIn(s.mode())
                            && c.industry()==com.jgeted.sagadyssey.social.industry.common.IndustryContract.Industry.FOOD).isPresent());
            var events=s.arrivals().stream().filter(a->a.household().equals(h.id()) && a.atOffset()<=s.horizonTicks())
                    .sorted(Comparator.comparingLong(SitingSnapshot.Arrival::atOffset).thenComparing(SitingSnapshot.Arrival::id)).toList();
            for(var a:events) {
                var cap=IndustryCatalog.find(a.capability());
                if(a.kind()==SitingSnapshot.ArrivalKind.UNDELIVERED_IMPORT) {
                    potential.add(a.capability());findings.add(new Finding(h.id(),"IMPORT_NOT_DELIVERED:"+a.id(),a.atOffset(),balance));continue;
                }
                if(cap.isEmpty() || !cap.get().verifiedIn(s.mode()) || cap.get().industry()!=com.jgeted.sagadyssey.social.industry.common.IndustryContract.Industry.FOOD) {
                    unknown=true;potential.add(a.capability());continue;
                }
                balance-=h.foodPerTick()*(a.atOffset()-last);last=a.atOffset();
                if(balance < -1e-9) { if(foodEvidenceComplete)fail=true;else unknown=true;
                    findings.add(new Finding(h.id(),foodEvidenceComplete?"FOOD_GAP_BEFORE_PRODUCTION":"FOOD_BUDGET_UNRESOLVED",last,balance)); }
                balance+=a.foodUnits();
            }
            balance-=h.foodPerTick()*(s.horizonTicks()-last);
            if(balance < -1e-9) { if(foodEvidenceComplete)fail=true;else unknown=true;
                findings.add(new Finding(h.id(),foodEvidenceComplete?"FOOD_GAP_WITHIN_WINDOW":"FOOD_BUDGET_UNRESOLVED",s.horizonTicks(),balance)); }
            if(!h.foodNow().authorized() && h.foodNow().physicalOwned()>0)
                findings.add(new Finding(h.id(),"STOCK_NOT_DISPOSABLE",0,initial));
        }
        return new Result(s.candidate(),fail?Verdict.FAIL:unknown?Verdict.UNKNOWN:Verdict.PASS,findings,now,potential,
                "Bounded feasibility only; forecast is not physical inventory. No natural initialization, exploration or perpetual self-sufficiency proven.");
    }
}
