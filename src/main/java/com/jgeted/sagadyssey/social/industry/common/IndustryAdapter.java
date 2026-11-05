package com.jgeted.sagadyssey.social.industry.common;

import java.util.*;
import static com.jgeted.sagadyssey.social.industry.common.IndustryContract.*;

/** Shared pure feasibility contract. Offers neither replace normal NPC plans nor bypass authoritative checks. */
public interface IndustryAdapter {
    String capabilityId();
    default Capability capability() { return IndustryCatalog.require(capabilityId()); }
    default Offer assess(Demand demand, Mode mode) {
        Objects.requireNonNull(mode);
        var c = capability();
        if (demand.remainingBatches() == 0) return offer(demand,State.SATISFIED,0,List.of(Cause.NO_DEMAND),Set.of("demand"));
        if (!c.executionConnected() || c.evidence().grade() == Grade.NONE || c.approvalRequired())
            return offer(demand,State.POTENTIAL,0,List.of(c.approvalRequired()?Cause.NEEDS_APPROVAL:Cause.UNVERIFIED),Set.of("capability-verification"));
        if (!c.verifiedIn(mode)) return offer(demand,State.UNKNOWN,0,List.of(Cause.WRONG_SCOPE),Set.of("scope-evidence"));
        var causes = new ArrayList<Cause>();
        var recheck = new LinkedHashSet<String>();
        if (!demand.workerAvailable()) { causes.add(Cause.LABOUR_BUSY); recheck.add("plan-released"); }
        if (c.needsTool() && !demand.toolAvailable()) { causes.add(Cause.NO_TOOL); recheck.add("tool-arrived"); }
        if (c.needsFacility() && !demand.facilityAvailable()) { causes.add(Cause.NO_FACILITY); recheck.add("facility-available"); }
        if (!demand.routeAvailable()) { causes.add(Cause.ROUTE_BLOCKED); recheck.add("route-changed"); }
        int batches=demand.remainingBatches();
        for (var input:c.inputPerBatch().entrySet()) {
            var stock=demand.inputs().get(input.getKey());
            if(stock == null || !stock.known()) { causes.add(Cause.UNKNOWN_SOURCE); recheck.add("source-known:"+input.getKey()); batches=0; }
            else if(!stock.authorized()) { causes.add(Cause.NO_PERMISSION); recheck.add("rights-changed:"+input.getKey()); batches=0; }
            else { batches=Math.min(batches,stock.available()/input.getValue()); }
        }
        if(batches == 0 && causes.isEmpty()) { causes.add(Cause.MISSING_INPUT); recheck.add("legal-stock-changed"); }
        return causes.isEmpty() ? offer(demand,State.READY,batches,List.of(),Set.of("commit-recheck"))
                : offer(demand,State.BLOCKED,0,causes,recheck);
    }
    private Offer offer(Demand d,State state,int batches,List<Cause> causes,Set<String> events) {
        return new Offer(d.id(),capabilityId(),state,batches,causes,events,d.tick());
    }
}
