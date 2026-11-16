package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.application.WarBattlePolicy;
import com.jgeted.sagadyssey.social.war.application.WarBattlePolicy.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarBattlePolicyTest {
    @Test void ordersAndDangerousServiceAloneNeverAuthorizeAnEnemy(){assertEquals(Source.NONE,WarBattlePolicy.source(new Facts(true,true,false,false,false,false,false,false)));}
    @Test void truceLifecycleAndInvalidContactVetoEveryCombatSource(){
        assertEquals(Source.NONE,WarBattlePolicy.source(new Facts(true,true,true,true,true,true,true,false)));
        assertEquals(Source.NONE,WarBattlePolicy.source(new Facts(false,true,false,true,true,true,true,false)));
        assertEquals(Source.NONE,WarBattlePolicy.source(new Facts(true,false,false,true,true,true,true,false)));
    }
    @Test void militaryEpisodeCannotLaunderExpiredDefenseOrEndedHostility(){assertEquals(Source.NONE,WarBattlePolicy.source(new Facts(true,true,false,false,false,false,true,true)));assertEquals(Source.EXISTING_ENCOUNTER,WarBattlePolicy.source(new Facts(true,true,false,false,false,false,true,false)));}
    @Test void recentAssaultOnConsentingGroupIsDistinctFromStandingHostility(){assertEquals(Source.SELF_DEFENSE,WarBattlePolicy.source(new Facts(true,true,false,false,true,false,false,true)));assertEquals(Source.GROUP_DEFENSE,WarBattlePolicy.source(new Facts(true,true,false,false,false,true,false,true)));assertEquals(Source.HOSTILITY,WarBattlePolicy.source(new Facts(true,true,false,true,false,false,false,true)));}
}
