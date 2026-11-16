package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.persistence.WarFarReadinessCodec;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarFarReadinessTest {
    private WarFarReadiness approved() {
        var member = new WarTransmission.Member(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        var at = new WarIntent.Position(WarKey.parse("minecraft:overworld"),10,64,30);
        return WarFarReadiness.approve(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                List.of(new WarFarReadiness.Post(member,at)),60,20,1);
    }

    @Test void loadingBeforeTheDeadlineReturnsToNearWithoutInventingPreparation() {
        var initial = approved();
        assertThrows(IllegalArgumentException.class,() -> initial.returned(20));
        var offline = initial.begin(70);
        assertThrows(IllegalArgumentException.class,() -> offline.prepared(129,1));
        var returned = offline.returned(100);
        assertTrue(WarFarReadiness.follows(initial,offline));
        assertTrue(WarFarReadiness.follows(offline,returned));
        assertFalse(WarFarReadiness.follows(initial,returned));
        assertEquals(0,returned.fitCount());
        assertEquals(initial.posts(),returned.posts());
    }

    @Test void completedPreparationKeepsOriginalBodiesAndSameOfflineWindowAcrossCodec() throws Exception {
        var offline = approved().begin(40);
        var codec = new WarFarReadinessCodec();
        var loaded = codec.read(codec.write(List.of(offline))).iterator().next();
        var prepared = loaded.prepared(100,1);
        var returned = prepared.returned(101);
        assertEquals(offline.operation(),returned.operation());
        assertEquals(offline.order(),returned.order());
        assertEquals(40,returned.departedAt());
        assertEquals(1,returned.fitCount());
        assertTrue(WarFarReadiness.follows(loaded,prepared));
        assertFalse(WarFarReadiness.follows(prepared,offline));
    }

    @Test void conflictingBodyAndDuplicateRosterCannotBeSubstitutedForAnApprovedPlan() {
        var first = approved();
        assertThrows(IllegalArgumentException.class,() -> WarFarReadiness.approve(first.unit(),first.army(),first.principal(),first.order(),first.operation(),
                List.of(first.posts().getFirst(),first.posts().getFirst()),60,20,1));
        var original = first.posts().getFirst();
        var m = original.member();
        var replacement = new WarFarReadiness.Post(new WarTransmission.Member(m.contract(),m.character(),UUID.randomUUID()),original.station());
        var other = WarFarReadiness.approve(first.unit(),first.army(),first.principal(),first.order(),first.operation(),List.of(replacement),60,20,1).begin(80);
        assertFalse(WarFarReadiness.follows(first,other));
    }
}
