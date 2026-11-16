package com.jgeted.sagadyssey.social.war;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.domain.WarSupplyRun.Phase;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry.*;
import com.jgeted.sagadyssey.social.war.api.WarMilitiaRequest;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class WarCampaignRulesTest {
    static WarSupplyRun run(int trips){return new WarSupplyRun(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),8,trips,10,24010,Phase.TO_SOURCE,0,0,1,10,false,"approved");}
    @Test void deliveryRequiresAnActualRoundTripAndConfirmedUnloading(){var r=run(2);r=r.advance(Phase.LOADING,11,"at source").attempted(12,"intent").advance(Phase.TO_TARGET,13,"picked");assertEquals(0,r.delivered());r=r.advance(Phase.UNLOADING,14,"arrived").advance(Phase.TO_SOURCE,15,"original receipt");assertEquals(1,r.delivered());r=r.advance(Phase.LOADING,16,"arrived").advance(Phase.TO_TARGET,17,"picked").advance(Phase.UNLOADING,18,"arrived").advance(Phase.DONE,19,"receipt");assertEquals(2,r.delivered());assertTrue(r.terminal());}
    @Test void cancellationWithCargoMustReturnBeforeRelease(){var r=run(1).advance(Phase.LOADING,11,"arrived").advance(Phase.TO_TARGET,12,"picked");assertThrows(IllegalArgumentException.class,()->r.advance(Phase.CANCELLED,13,"drop responsibility"));var back=r.advance(Phase.RETURNING,13,"cancel");assertTrue(back.custody());assertFalse(back.terminal());assertTrue(back.advance(Phase.RETURN_UNLOAD,14,"arrived").advance(Phase.CANCELLED,15,"receipt").terminal());}
    @Test void retirementIntentDoesNotEraseAPendingEffect(){var r=run(1).advance(Phase.LOADING,11,"arrived");var stopped=r.retirement(12);assertTrue(stopped.retireAfter());assertEquals(Phase.LOADING,stopped.phase());assertTrue(WarSupplyRun.follows(r,stopped));}
    @Test void attemptsAndTransitionsHaveExactMonotonicEvidence(){var r=run(1);assertTrue(WarSupplyRun.follows(r,r.attempted(11,"attempt")));assertFalse(WarSupplyRun.follows(r,run(1)));assertThrows(IllegalArgumentException.class,()->r.advance(Phase.DONE,11,"pretend"));assertThrows(IllegalArgumentException.class,()->r.attempted(9,"rewind"));}
    @Test void finiteTripsAndRecordLimitsPreventAnUnboundedAutoOrder(){assertThrows(IllegalArgumentException.class,()->run(9));var r=run(1);for(int i=0;i<64;i++)r=r.attempted(11+i,"proven denial");var full=r;assertThrows(IllegalArgumentException.class,()->full.attempted(100,"unbounded"));}
    static WarAllowanceTerms terms(){return new WarAllowanceTerms(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),100,48100,2);}
    @Test void stipendAccruesOnlyItsOwnLegalTermAndStopsWithoutDeletingDebt(){var t=terms();assertEquals(BigInteger.ZERO,t.due(101,OptionalLong.empty()));assertEquals(BigInteger.TWO,t.due(24100,OptionalLong.empty()));assertEquals(BigInteger.ONE,t.due(101,OptionalLong.of(101)));assertEquals(BigInteger.ONE,t.due(100000,OptionalLong.of(101)));assertEquals(BigInteger.valueOf(4),t.due(Long.MAX_VALUE,OptionalLong.empty()));}
    @Test void stipendCannotBeBackdatedBeforeItsTerm(){var t=terms();assertThrows(IllegalArgumentException.class,()->t.due(99,OptionalLong.empty()));assertThrows(IllegalArgumentException.class,()->t.due(100,OptionalLong.of(99)));assertThrows(IllegalArgumentException.class,()->t.due(100,OptionalLong.of(48101)));}
    @Test void originalMountFeedingHasNoFreeRestartOrServiceResetWindow(){var h=new WarHorseCare(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),100,1);assertFalse(h.fresh(99));assertTrue(h.fresh(100));assertTrue(h.fresh(12099));assertFalse(h.fresh(12100));}
    @Test void socialOriginNeverBecomesACommandBypassAndActingNeverChangesServiceKind(){assertTrue(WarOfficeQualification.expertise(Role.STAFF_OFFICER,Mode.REGULAR,20,0));assertTrue(WarOfficeQualification.expertise(Role.STAFF_OFFICER,Mode.REGULAR,0,3));assertFalse(WarOfficeQualification.expertise(Role.STAFF_OFFICER,Mode.REGULAR,19,2));assertTrue(WarOfficeQualification.expertise(Role.STAFF_OFFICER,Mode.ACTING,0,0));assertFalse(WarOfficeQualification.compatible(Role.STAFF_OFFICER,WarMilitiaRequest.Role.COMBATANT));assertFalse(WarOfficeQualification.compatible(Role.CAPTAIN,WarMilitiaRequest.Role.STAFF));}
}
