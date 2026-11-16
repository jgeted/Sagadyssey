package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.war.spi.WarBattlePorts.*;
import java.util.*;

/** Safe default for explicitly assembled older hosts: reads existing original encounters only. */
final class WarExistingEngagement implements Engagement<NpcBase>{
    private final CombatEncounterData data;
    WarExistingEngagement(CombatEncounterData data){this.data=Objects.requireNonNull(data);}
    public Optional<Permit> contact(NpcBase actor,NpcBase target,long now){return current(actor,target,now);}
    public Optional<Permit> current(NpcBase actor,NpcBase target,long now){
        boolean immediate=recentAssault(actor,target,now);
        return data.activeEncounters(actor.getCharacterId(),now).stream().filter(e->allows(data,e,actor,target,immediate,now))
                .map(e->new Permit(e.id(),immediate)).findFirst();
    }
    static boolean recentAssault(NpcBase victim,NpcBase attacker,long now){return victim.getLastHurtByMob()==attacker&&victim.getLastDamageGameTime()>=0&&now>=victim.getLastDamageGameTime()&&now-victim.getLastDamageGameTime()<=100;}
    static boolean allows(CombatEncounterData data,CombatEncounter e,NpcBase actor,NpcBase target,boolean immediate,long now){
        if(!e.activeAt(now)||data.settling(e.id())||e.events().size()>=248||actor.level()!=target.level()
                ||!e.dimension().equals(actor.level().dimension().identifier().toString())||e.sideOf(target.getCharacterId())==null
                ||actor.blockPosition().distSqr(e.center())>(double)e.radius()*e.radius()||target.blockPosition().distSqr(e.center())>(double)e.radius()*e.radius())return false;
        var action=CombatEncounterService.engagement(e,actor.getCharacterId(),target.getCharacterId(),immediate).action();
        return action==EngagementAction.ATTACK||action==EngagementAction.DEFEND;
    }
}
