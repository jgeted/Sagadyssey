package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarCohesionPolicy;
import com.jgeted.sagadyssey.social.war.domain.WarCohesion;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.jgeted.sagadyssey.social.war.WarCohesionTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class WarCohesionPolicyTest {
    final WarCohesionPolicy policy=new WarCohesionPolicy(WarCohesionPolicy.Rules.prototype());
    @Test void organizationAndIndividualMoraleAreDifferentAxes() {
        var lowOrganization=policy.plan(Optional.of(state(1000,WarCohesion.Phase.STEADY)),frame(1,members(10,90,false),List.of()),false);
        assertEquals(1000,lowOrganization.organization()); assertTrue(lowOrganization.routed().isEmpty()); assertEquals(WarCohesion.Phase.WAVERING,lowOrganization.phase());
        var mixed=new ArrayList<>(members(10,90,false)); var one=mixed.getFirst(); mixed.set(0,new WarCohesionFrame.Member(one.contract(),one.character(),one.body(),true,5,false,true));
        var lowMorale=policy.plan(Optional.empty(),frame(1,mixed,List.of()),false);
        assertEquals(10_000,lowMorale.organization()); assertEquals(Set.of(one.contract()),lowMorale.routed()); assertEquals(WarCohesion.Phase.STEADY,lowMorale.phase());
        assertEquals(WarCohesion.Phase.ROUTING,policy.plan(Optional.of(state(0,WarCohesion.Phase.STEADY)),frame(1,members(128,100,false),List.of()),false).phase());
    }
    @Test void repeatedPollingAndFatalWoundDoNotChargeTheSameCasualtyTwice() {
        var roster=members(7,100,false); var wound=wound(1000,0,1);
        var wounded=policy.plan(Optional.empty(),frame(1,roster,List.of(wound)),false);
        assertEquals(10_000-572,wounded.organization()); assertSame(wounded,policy.plan(Optional.of(wounded),frame(2,roster,List.of(wound)),false));
        var second=wound(1001,0,2); var repeated=policy.plan(Optional.of(wounded),frame(2,roster,List.of(wound,second)),false);
        assertEquals(wounded.organization(),repeated.organization());
        var death=new WarCohesionFrame.Event(id(1002),id(200),WarCohesionFrame.Shock.KILLED,3);
        var fatal=policy.plan(Optional.of(repeated),frame(3,roster,List.of(wound,second,death)),false);
        assertEquals(10_000-1429,fatal.organization());
        assertEquals(2,fatal.impacts().values().iterator().next().severity());
        assertEquals(1429,fatal.impacts().values().iterator().next().appliedLoss());
    }
    @Test void casualtyLossIsNormalizedAndReinforcementCannotRefundOldLoss() {
        for(int count:List.of(3,12,128)) {
            var events=new ArrayList<WarCohesionFrame.Event>(); for(int i=0;i<count;i++) events.add(wound(1000+i,i,1));
            int remaining=policy.plan(Optional.empty(),frame(1,members(count,100,false),events),false).organization();
            assertTrue(remaining<=6000 && remaining>=6000-count,"fixed-point rounding is bounded by one basis point per casualty");
        }
        var original=policy.plan(Optional.empty(),frame(1,members(3,100,false),List.of(wound(1000,0,1))),false);
        var reinforced=policy.plan(Optional.of(original),frame(2,members(12,100,false),List.of(wound(1000,0,1))),false);
        assertEquals(original.organization(),reinforced.organization());
    }
    @Test void aShockIsDeterministicAcrossPollingBatchesAndDoesNotRerollEveryTick() {
        var events=List.of(wound(1000,0,1),wound(1001,1,2),wound(1002,2,3)); var roster=members(12,35,false);
        var initial=state(1500,WarCohesion.Phase.WAVERING);
        var batched=policy.plan(Optional.of(initial),frame(3,roster,events),false);
        var split=initial;
        for(int i=1;i<=3;i++) split=policy.plan(Optional.of(split),frame(i,roster,events.subList(0,i)),false);
        assertEquals(batched.routed(),split.routed()); assertEquals(batched.organization(),split.organization());
        for(int tick=4;tick<200;tick++) assertSame(split,policy.plan(Optional.of(split),frame(tick,roster,events),false));
    }
    @Test void lowMoraleMajorityAndZeroOrganizationCannotBeOverriddenByAnotherMove() {
        var broken=policy.plan(Optional.empty(),frame(1,members(3,10,false),List.of()),false);
        assertEquals(WarCohesion.Phase.ROUTING,broken.phase());
        var newOrder=command(frame(2,members(3,10,false),List.of()),2,WarCohesionFrame.OrderKind.COMBAT);
        var retry=policy.plan(Optional.of(broken),newOrder,false); assertEquals(WarCohesion.Phase.ROUTING,retry.phase()); assertEquals(broken.routed(),retry.routed());
        var zero=policy.plan(Optional.of(state(0,WarCohesion.Phase.ROUTING)),frame(2,members(3,90,true),List.of()),false);
        assertEquals(0,zero.organization()); assertEquals(WarCohesion.Phase.REGROUPING,zero.phase());
    }
    @Test void safePhysicalRallyHasQuorumAndRequiresEveryActiveBodyToBeObserved() {
        var roster=new ArrayList<>(members(5,90,true)); var m=roster.getFirst();
        roster.set(0,new WarCohesionFrame.Member(m.contract(),m.character(),m.body(),true,m.morale(),false,false));
        assertTrue(frame(1,roster,List.of()).safeAtRally());
        roster.set(0,new WarCohesionFrame.Member(m.contract(),m.character(),m.body(),false,m.morale(),false,false));
        assertFalse(frame(1,roster,List.of()).safeAtRally());
    }
    @Test void withdrawalAndRallyDoNotChangeMoraleHealthOrRefillOrganization() {
        var before=state(3500,WarCohesion.Phase.WAVERING);
        var withdrawal=policy.plan(Optional.of(before),command(frame(1,members(3,70,false),List.of()),2,WarCohesionFrame.OrderKind.WITHDRAW),false);
        assertEquals(WarCohesion.Phase.WITHDRAWING,withdrawal.phase()); assertEquals(3500,withdrawal.organization()); assertTrue(withdrawal.routed().isEmpty());
        var rally=policy.plan(Optional.of(withdrawal),command(frame(2,members(3,70,true),List.of()),3,WarCohesionFrame.OrderKind.RALLY),false);
        assertEquals(3500,rally.organization()); assertEquals(WarCohesion.Phase.REGROUPING,rally.phase());
    }
    @Test void changedEncounterPrefixesAndStaleCommandsRequireReview() {
        var first=policy.plan(Optional.empty(),frame(1,members(3,80,false),List.of(wound(1000,0,1))),false);
        assertThrows(IllegalArgumentException.class,()->policy.plan(Optional.of(first),frame(2,members(3,80,false),List.of(wound(1001,0,1))),false));
        assertThrows(IllegalArgumentException.class,()->policy.plan(Optional.of(first),new WarCohesionFrame(id(1),id(2),id(999),1,WarCohesionFrame.OrderKind.COMBAT,POINT,POINT,2,members(3,80,false),List.of()),false));
    }
    @Test void moreWoundNotificationsForTheSameCasualtyDoNotMultiplyRoutingRolls() {
        var roster=members(12,35,false);var events=new ArrayList<WarCohesionFrame.Event>();events.add(wound(1000,0,1));
        var first=policy.plan(Optional.of(state(2000,WarCohesion.Phase.WAVERING)),frame(1,roster,events),false);
        for(int i=1;i<100;i++)events.add(wound(1000+i,0,1+i));
        var repeated=policy.plan(Optional.of(first),frame(100,roster,events),false);
        assertEquals(first.organization(),repeated.organization());assertEquals(first.routed(),repeated.routed());
    }
    @Test void veteranHoldingChangesCurrentFormationLossButNotEarlierEvidence() {
        var plain=frame(10,members(4,100,false),List.of(wound(1000,0,2),wound(1001,1,6)));
        var led=new WarCohesionFrame(plain.unit(),plain.army(),plain.order(),plain.sequence(),plain.kind(),plain.target(),plain.origin(),
                plain.now(),plain.members(),plain.encounters(),new WarCohesionFrame.Leadership(16,0,5));
        assertEquals(8000,policy.plan(Optional.empty(),plain,false).organization());
        assertEquals(8160,policy.plan(Optional.empty(),led,false).organization());
    }
    @Test void withdrawalExperienceStillNeedsARealSafeRecoveryWindow() {
        var ready=command(frame(10,members(4,100,true),List.of()),2,WarCohesionFrame.OrderKind.WITHDRAW);
        var led=new WarCohesionFrame(ready.unit(),ready.army(),ready.order(),ready.sequence(),ready.kind(),ready.target(),ready.origin(),
                ready.now(),ready.members(),ready.encounters(),new WarCohesionFrame.Leadership(0,120,2));
        var before=state(6630,WarCohesion.Phase.REGROUPING);
        assertEquals(6630,policy.plan(Optional.of(before),led,false).organization());
        assertEquals(6830,policy.plan(Optional.of(before),ready,true).organization());
        assertEquals(6950,policy.plan(Optional.of(before),led,true).organization());
    }
}
