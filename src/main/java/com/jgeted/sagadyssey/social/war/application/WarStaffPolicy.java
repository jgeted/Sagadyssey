package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry.*;
import java.util.*;

/** Replaceable prototype thresholds. Counts come from an explicit review, not instantaneous casualties. */
public final class WarStaffPolicy {
    public record Rules(int squad,int company,int army,int captain) {
        public Rules {if(squad<2||company<=squad||army<=company||captain<1)throw new IllegalArgumentException("invalid staffing thresholds");}
        public static Rules prototype(){return new Rules(21,51,121,12);}
    }
    public record Eligibility(boolean available,boolean implemented,int required,String reason){}
    private final Rules rules;
    public WarStaffPolicy(Rules rules){this.rules=Objects.requireNonNull(rules);}
    public String scale(Establishment e){int n=e.strength();return n<rules.squad()?"随行队":n<rules.company()?"小队":n<rules.army()?"正式军伍":n<=300?"成军":"更大规模（承载量待验）";}
    public Eligibility evaluate(Establishment e,Role role,Optional<UUID> unit){
        int minimum=switch(role){case TEMPORARY_DEPUTY->1;case DEPUTY,CAPTAIN->rules.squad();case STAFF_OFFICER,STANDARD_BEARER->rules.company();default->rules.army();};
        if(e.strength()<minimum)return new Eligibility(false,true,minimum,"COMBATANT_STRENGTH_REQUIRED");
        if(role==Role.CAPTAIN&&(unit.isEmpty()||e.combatants().getOrDefault(unit.get(),0)<rules.captain()))return new Eligibility(false,true,rules.captain(),"CAPTAIN_UNIT_STRENGTH_REQUIRED");
        if(role!=Role.CAPTAIN&&unit.isPresent())return new Eligibility(false,role.partTime(),minimum,"ARMY_ROLE_CANNOT_USE_UNIT_SCOPE");
        return new Eligibility(true,true,minimum,role.partTime()?"PART_TIME_WITHIN_ORIGINAL_MILITARY_SERVICE":"DEDICATED_ORIGINAL_STAFF_SERVICE_REQUIRED");
    }
}
