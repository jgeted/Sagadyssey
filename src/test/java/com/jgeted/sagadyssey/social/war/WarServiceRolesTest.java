package com.jgeted.sagadyssey.social.war;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarServiceRoles;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WarServiceRolesTest {
    private WarMilitiaRequest request(WarMilitiaRequest.Kind kind,WarMilitiaRequest.Role role,WarLoadout loadout){var r=WarMusterBookTest.offer().request();return new WarMilitiaRequest(r.id(),r.actorId(),r.armyId(),r.unitId(),r.characterId(),loadout.id(),1,kind,role,r.wage(),r.requestedAtTick());}
    @Test void preservesCombatantProfiles(){for(var m:WarLoadout.Mount.values())assertTrue(WarServiceRoles.supported(request(WarMilitiaRequest.Kind.MILITIA,WarMilitiaRequest.Role.COMBATANT,new WarLoadout(m,WarLoadout.Weapon.SWORD,false,false))));}
    @Test void staffRequiresExplicitPlainFootKit(){assertTrue(WarServiceRoles.supported(request(WarMilitiaRequest.Kind.MILITIA,WarMilitiaRequest.Role.STAFF,new WarLoadout(WarLoadout.Mount.FOOT,WarLoadout.Weapon.SWORD,false,false))));assertFalse(WarServiceRoles.supported(request(WarMilitiaRequest.Kind.MILITIA,WarMilitiaRequest.Role.STAFF,new WarLoadout(WarLoadout.Mount.FOOT,WarLoadout.Weapon.BOW,false,false))));}
    @Test void otherServiceCategoriesStayDisabled(){for(var role:WarMilitiaRequest.Role.values()){var kit=new WarLoadout(WarLoadout.Mount.FOOT,WarLoadout.Weapon.SWORD,false,false);assertFalse(WarServiceRoles.supported(request(WarMilitiaRequest.Kind.REGULAR,role,kit)));if(role!=WarMilitiaRequest.Role.COMBATANT&&role!=WarMilitiaRequest.Role.STAFF&&role!=WarMilitiaRequest.Role.ENGINEER)assertFalse(WarServiceRoles.supported(request(WarMilitiaRequest.Kind.MILITIA,role,kit)));}}
}
