package com.jgeted.sagadyssey.social.war.domain;
import com.jgeted.sagadyssey.social.war.api.WarMilitiaRequest;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry.*;

/** Soft expertise can be delegated explicitly; life, scope, original service and establishment never can. */
public final class WarOfficeQualification {
    private WarOfficeQualification(){}
    public static boolean compatible(Role role,WarMilitiaRequest.Role service){return role.partTime()?service==WarMilitiaRequest.Role.COMBATANT:service==WarMilitiaRequest.Role.STAFF||role==Role.ENGINEERING_LEAD&&service==WarMilitiaRequest.Role.ENGINEER;}
    public static boolean expertise(Role role,Mode mode,int originalExperience,int confirmedDuties){return role.partTime()||mode!=Mode.REGULAR||originalExperience>=20||confirmedDuties>=3;}
}
