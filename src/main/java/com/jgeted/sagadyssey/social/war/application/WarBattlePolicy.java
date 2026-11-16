package com.jgeted.sagadyssey.social.war.application;

/** Pure policy. A military-owned encounter is evidence of participation, never its own enemy authority. */
public final class WarBattlePolicy {
    private WarBattlePolicy(){}
    public enum Source{NONE,HOSTILITY,SELF_DEFENSE,GROUP_DEFENSE,EXISTING_ENCOUNTER}
    public record Facts(boolean currentService,boolean validContact,boolean allyOrTruce,boolean originalHostility,
                        boolean recentOwnAssault,boolean recentProtectedAssault,boolean originalEncounterAllows,boolean militaryHosted){}
    public static Source source(Facts f){
        if(!f.currentService()||!f.validContact()||f.allyOrTruce())return Source.NONE;
        if(f.originalHostility())return Source.HOSTILITY;
        if(f.recentOwnAssault())return Source.SELF_DEFENSE;
        if(f.recentProtectedAssault())return Source.GROUP_DEFENSE;
        return f.originalEncounterAllows()&&!f.militaryHosted()?Source.EXISTING_ENCOUNTER:Source.NONE;
    }
}
