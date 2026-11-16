package com.jgeted.sagadyssey.social.war.domain;
import com.jgeted.sagadyssey.social.war.api.WarMilitiaRequest;
import com.jgeted.sagadyssey.social.war.api.WarLoadout;

/** Explicitly enabled service duties, independent of profession and institution office. */
public final class WarServiceRoles {
    private WarServiceRoles(){}
    public static boolean supported(WarMilitiaRequest r){
        return r.kind()==WarMilitiaRequest.Kind.MILITIA&&(r.role()==WarMilitiaRequest.Role.COMBATANT
                ||(r.role()==WarMilitiaRequest.Role.STAFF||r.role()==WarMilitiaRequest.Role.ENGINEER)&&r.loadout().equals(new WarLoadout(WarLoadout.Mount.FOOT,WarLoadout.Weapon.SWORD,false,false).id()));
    }
}
