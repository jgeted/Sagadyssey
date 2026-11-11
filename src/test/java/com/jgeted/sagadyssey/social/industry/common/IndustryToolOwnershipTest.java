package com.jgeted.sagadyssey.social.industry.common;
import com.jgeted.sagadyssey.worldsim.rights.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndustryToolOwnershipTest {
    private final SubjectRef owner=SubjectRef.character(UUID.randomUUID());
    private AssetClaim claim(SubjectRef title,SubjectRef holder,Set<String> burdens,Set<String> reservations){
        return new AssetClaim(UUID.randomUUID(),UUID.randomUUID(),1,new ItemFingerprint("minecraft:iron_axe",1931911076),1,title,holder,burdens,reservations,"fieldwork actual tool wear",51);
    }
    @Test void actualToolWearDoesNotMakeAnOwnedToolUnavailable(){
        assertTrue(IndustryToolTask.ownedUnencumbered(List.of(claim(owner,owner,Set.of(),Set.of())),owner));
        assertTrue(IndustryToolTask.ownedUnencumbered(List.of(),owner));
    }
    @Test void ForeignOrContestedToolsRemainExcluded(){
        var other=SubjectRef.character(UUID.randomUUID());
        assertFalse(IndustryToolTask.ownedUnencumbered(List.of(claim(other,owner,Set.of(),Set.of())),owner));
        assertFalse(IndustryToolTask.ownedUnencumbered(List.of(claim(owner,other,Set.of(),Set.of())),owner));
        assertFalse(IndustryToolTask.ownedUnencumbered(List.of(claim(owner,owner,Set.of("loan"),Set.of())),owner));
        assertFalse(IndustryToolTask.ownedUnencumbered(List.of(claim(owner,owner,Set.of(),Set.of("other-job"))),owner));
    }
}
