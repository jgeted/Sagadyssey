package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.WarCavalryRecovery;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.jgeted.sagadyssey.social.war.WarCavalryTestSupport.*;

class WarCavalryRecoveryTest{
    private static WarTransmission received(){
        var state=run(service(new Memory()));var c=new WarCommand.IssueOrder(id(3),id(4),id(2),id(1),1,new WarIntent(InitialWarDefinitions.CHARGE,1,FRONT));var order=new WarUnit.Order(id(3),id(4),1,c.intent(),0,5,3);return new WarTransmission(c,1,WarTransmission.route(REAR,REAR,0,0),state.members(),Optional.of(order));
    }
    private static WarUnit unit(WarUnit.Order order){return new WarUnit(id(1),id(2),WarKey.parse("test:troop"),1,3,order.sequence(),Optional.of(order),List.of());}
    @Test void missingCycleCannotReseedAfterAReceivedChargeEvenIfTheUnitNowHolds(){
        var history=received();assertThrows(IllegalArgumentException.class,()->WarCavalryRecovery.verify(List.of(),List.of(history),Map.of(id(1),unit(history.received().orElseThrow())),InitialWarDefinitions.CHARGE));
        var hold=new WarUnit.Order(id(90),id(4),2,new WarIntent(InitialWarDefinitions.HOLD,1,FRONT),6,12,3);assertThrows(IllegalArgumentException.class,()->WarCavalryRecovery.verify(List.of(),List.of(history),Map.of(id(1),unit(hold)),InitialWarDefinitions.CHARGE));
    }
    @Test void matchingOriginalDispatchRosterAndObjectiveAreRequired(){
        var history=received();var state=run(service(new Memory()));var units=Map.of(id(1),unit(history.received().orElseThrow()));assertDoesNotThrow(()->WarCavalryRecovery.verify(List.of(state),List.of(history),units,InitialWarDefinitions.CHARGE));
        assertThrows(IllegalArgumentException.class,()->WarCavalryRecovery.verify(List.of(state),List.of(),units,InitialWarDefinitions.CHARGE));
    }
    @Test void aReceivedButNotYetObservedNewChargeStaysReviewInsteadOfGuessingANewCycle(){
        var state=run(service(new Memory()));var old=received();var c=new WarCommand.IssueOrder(id(91),id(4),id(2),id(1),3,new WarIntent(InitialWarDefinitions.CHARGE,1,FRONT));var received=new WarUnit.Order(id(91),id(4),2,c.intent(),10,15,4);var latest=new WarTransmission(c,2,WarTransmission.route(REAR,REAR,10,10),state.members(),Optional.of(received));
        assertThrows(IllegalArgumentException.class,()->WarCavalryRecovery.verify(List.of(state),List.of(old,latest),Map.of(id(1),new WarUnit(id(1),id(2),WarKey.parse("test:troop"),1,4,2,Optional.of(received),List.of())),InitialWarDefinitions.CHARGE));
    }
    @Test void anUndeliveredFirstChargeDoesNotRequireAHistoryThatCouldNotHaveExecuted(){
        var pending=new WarUnit.Order(id(3),id(4),1,new WarIntent(InitialWarDefinitions.CHARGE,1,FRONT),0,-1,0);var unit=new WarUnit(id(1),id(2),WarKey.parse("test:troop"),1,2,1,Optional.empty(),List.of(pending));assertDoesNotThrow(()->WarCavalryRecovery.verify(List.of(),List.of(),Map.of(unit.id(),unit),InitialWarDefinitions.CHARGE));
    }
}
