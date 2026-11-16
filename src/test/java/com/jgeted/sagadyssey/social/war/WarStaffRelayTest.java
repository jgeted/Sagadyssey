package com.jgeted.sagadyssey.social.war;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarStaffRelayCodec;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WarStaffRelayTest{
    static WarStaffRelay task(){return new WarStaffRelay(UUID.randomUUID(),UUID.randomUUID(),new WarCommandPost(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),new WarIntent.Position(WarKey.parse("minecraft:overworld"),0,80,0),WarKey.parse("minecraft:white_banner"),1),0,5,10,210,WarStaffRelay.Phase.QUEUED,1,10,"explicit one-shot rally");}
    @Test void ownerRevisionAndAuthorityAreRecheckedBeforeIssuing(){var r=task();assertTrue(r.mayIssue(11,5,true));assertFalse(r.mayIssue(11,6,true));assertFalse(r.mayIssue(11,5,false));}
    @Test void deadlineAndReversedTimeCannotExtendAnApproval(){var r=task();assertFalse(r.mayIssue(9,5,true));assertTrue(r.mayIssue(209,5,true));assertFalse(r.mayIssue(210,5,true));}
    @Test void anIssueFenceIsRequiredAndTerminalEvidenceCannotRearm(){var r=task();var f=r.advance(WarStaffRelay.Phase.ISSUING,11,"fenced");var sent=f.advance(WarStaffRelay.Phase.SENT,12,"accepted");assertThrows(IllegalArgumentException.class,()->sent.advance(WarStaffRelay.Phase.ISSUING,13,"replay"));assertThrows(IllegalArgumentException.class,()->r.advance(WarStaffRelay.Phase.QUEUED,11,"again"));assertTrue(WarStaffRelay.follows(r,f));}
    @Test void cancellationKeepsTheExactOriginalPostAndAppointment(){var r=task();var c=r.advance(WarStaffRelay.Phase.CANCELLED,11,"owner changed order");assertEquals(r.post(),c.post());assertEquals(r.appointment(),c.appointment());assertTrue(c.terminal());}
    @Test void codecRestoresTheExactApprovalAndDetectsCorruption()throws Exception{var c=new WarStaffRelayCodec();var r=task();byte[] data=c.write(List.of(r));assertEquals(List.of(r),List.copyOf(c.read(data)));data[data.length-1]^=1;assertThrows(Exception.class,()->c.read(data));}
}
