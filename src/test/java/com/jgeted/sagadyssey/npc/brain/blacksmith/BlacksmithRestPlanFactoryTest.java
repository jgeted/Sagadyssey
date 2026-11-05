package com.jgeted.sagadyssey.npc.brain.blacksmith;

import com.jgeted.sagadyssey.npc.brain.execution.WaitUntilTaskExecutor;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BlacksmithRestPlanFactoryTest {
    @Test void saveReloadRetainsOriginalIdentityAndFiniteDeadline() {
        var p = BlacksmithRestPlanFactory.create(UUID.randomUUID(), "minecraft:overworld", BlockPos.ZERO, 40);
        assertEquals(p, com.jgeted.sagadyssey.npc.brain.persistence.NpcPlanNbtCodec.load(
                com.jgeted.sagadyssey.npc.brain.persistence.NpcPlanNbtCodec.save(p)));
    }
    @Test void finiteAmbientRestIsNotAProductionOrDiagnosticCommitment() {
        var body = UUID.randomUUID();
        var p = BlacksmithRestPlanFactory.create(body, "minecraft:overworld", BlockPos.ZERO, 40);
        assertEquals(body, p.npcId());
        assertEquals(BlacksmithRestPlanFactory.PROVIDER, p.providerId());
        assertNotEquals(BlacksmithTaskIds.PROVIDER, p.providerId());
        assertEquals(NpcPlanPriority.AMBIENT, p.priority());
        assertEquals(WaitUntilTaskExecutor.ID, p.currentStep().executorId());
        assertEquals("140", p.currentStep().params().get(WaitUntilTaskExecutor.UNTIL_GAME_TIME));
        assertEquals(340, p.expiresAt());
        assertFalse(p.currentStep().params().containsKey(BlacksmithTaskIds.ORDER_ID));
    }

    @Test void recordsOnlyTheOriginalBodyAndObservedLocation() {
        var pos = new BlockPos.MutableBlockPos(2, 64, 8);
        var p = BlacksmithRestPlanFactory.create(UUID.randomUUID(), "minecraft:overworld", pos, 0);
        pos.set(9, 9, 9);
        assertEquals("2", p.currentStep().params().get("x"));
        assertEquals("64", p.currentStep().params().get("y"));
        assertEquals("8", p.currentStep().params().get("z"));
        assertEquals(1, p.steps().size());
    }

    @Test void invalidIdentityAndDeadlineOverflowCannotCreateImmortalRest() {
        assertThrows(IllegalArgumentException.class, () -> BlacksmithRestPlanFactory.create(null, "minecraft:overworld", BlockPos.ZERO, 0));
        assertThrows(IllegalArgumentException.class, () -> BlacksmithRestPlanFactory.create(UUID.randomUUID(), "", BlockPos.ZERO, 0));
        assertThrows(IllegalArgumentException.class, () -> BlacksmithRestPlanFactory.create(UUID.randomUUID(), "minecraft:overworld", BlockPos.ZERO, -1));
        assertThrows(ArithmeticException.class, () -> BlacksmithRestPlanFactory.create(UUID.randomUUID(), "minecraft:overworld", BlockPos.ZERO, Long.MAX_VALUE - 200));
    }
}
