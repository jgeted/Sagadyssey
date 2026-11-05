package com.jgeted.sagadyssey.npc.brain.life;

import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcPlanNbtCodec;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LifePlanFactoryTest {
    @Test void needsHaveSeparateReservationTravelAndAction() {
        var p=LifePlanFactory.create(UUID.randomUUID(),"EAT","minecraft:overworld",BlockPos.ZERO,40,10,Map.of("source","BAG"));
        assertEquals(NpcPlanKind.NEED,p.kind());assertEquals(3,p.steps().size());
        assertEquals(NpcPlanPhase.RESERVING,p.steps().get(0).phase());assertEquals(NpcPlanPhase.TRAVELLING,p.steps().get(1).phase());
        assertEquals(NpcPlanPhase.ACTING,p.steps().get(2).phase());assertTrue(p.expiresAt()>10);
    }
    @Test void actorAndTargetRemainImmutableAndPersistent() {
        var pos=new BlockPos.MutableBlockPos(1,64,2);var npc=UUID.randomUUID();
        var p=LifePlanFactory.create(npc,"READ","minecraft:overworld",pos,100,10,Map.of());pos.set(8,9,10);
        assertEquals(new BlockPos(1,64,2),LifePlanFactory.target(p.currentStep().params()));
        assertEquals(npc,p.npcId());
    }
    @Test void selfCareAndSocialMotivationsAreDistinct() {
        var npc=UUID.randomUUID();
        assertEquals(NpcPlanKind.HOUSEHOLD,LifePlanFactory.create(npc,"CARE","minecraft:overworld",BlockPos.ZERO,40,10,Map.of()).kind());
        assertEquals(NpcPlanKind.SOCIAL,LifePlanFactory.create(npc,"TALK","minecraft:overworld",BlockPos.ZERO,100,10,Map.of()).kind());
    }
}
