package com.jgeted.sagadyssey.npc.brain.persistence;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPriority;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStepStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcBrainDataTest {
    private static final ResourceLocation PROVIDER = ResourceLocation.fromNamespaceAndPath(
            "sagadyssey", "brain_data_test");
    private static final ResourceLocation EXECUTOR = ResourceLocation.fromNamespaceAndPath(
            "sagadyssey", "wait");

    @Test
    void unknownNpcDefaultsToLegacyAndExplicitModeRoundTrips() {
        UUID npcId = UUID.randomUUID();
        NpcBrainData data = new NpcBrainData();

        assertEquals(NpcBehaviorMode.LEGACY, data.mode(npcId));
        assertFalse(data.isPlanned(npcId));

        data.setMode(npcId, NpcBehaviorMode.PLANNED);
        NpcBrainData loaded = roundTrip(data);

        assertEquals(NpcBehaviorMode.PLANNED, loaded.mode(npcId));
        assertTrue(loaded.isPlanned(npcId));
        loaded.setMode(npcId, NpcBehaviorMode.LEGACY);
        assertEquals(NpcBehaviorMode.LEGACY, roundTrip(loaded).mode(npcId));
    }

    @Test
    void activeAndNewestFirstBoundedHistoryRoundTrip() {
        UUID npcId = UUID.randomUUID();
        NpcBrainData data = new NpcBrainData();
        for (int index = 0; index < 35; index++) {
            long createdAt = 10L + index * 10L;
            NpcPlan plan = selected(UUID.randomUUID(), npcId, createdAt);
            data.save(plan);
            data.save(plan.transition(NpcPlanStatus.CANCELLED,
                    createdAt + 1L, "test complete"));
        }
        NpcPlan active = selected(UUID.randomUUID(), npcId, 1_000L);
        data.save(active);

        NpcBrainData loaded = roundTrip(data);

        assertEquals(active.id(), loaded.active(npcId).orElseThrow().id());
        assertEquals(32, loaded.history(npcId).size());
        assertTrue(loaded.history(npcId).getFirst().createdAt()
                > loaded.history(npcId).getLast().createdAt());
        assertThrows(UnsupportedOperationException.class, () -> loaded.history(npcId).clear());
    }

    @Test
    void malformedPlanRejectsLoadWithoutChangingOriginalRecords() {
        UUID npcId = UUID.randomUUID();
        NpcBrainData data = new NpcBrainData();
        data.setMode(npcId, NpcBehaviorMode.PLANNED);
        NpcPlan finishedSource = selected(UUID.randomUUID(), npcId, 10L);
        data.save(finishedSource);
        data.save(finishedSource.transition(NpcPlanStatus.CANCELLED, 11L, "done"));
        data.save(selected(UUID.randomUUID(), npcId, 20L));

        CompoundTag tag = data.save(new CompoundTag(), null);
        CompoundTag brain = tag.getList("Brains", CompoundTag.TAG_COMPOUND).getCompound(0);
        brain.put("Active", new CompoundTag());
        brain.getList("History", CompoundTag.TAG_COMPOUND).add(new CompoundTag());

        var original = tag.copy();
        assertThrows(IllegalArgumentException.class, () -> NpcBrainData.load(tag, null));
        assertEquals(original, tag);
        assertEquals("PLANNED", brain.getString("Mode"));
        assertEquals(finishedSource.id(), brain.getList("History", CompoundTag.TAG_COMPOUND)
                .getCompound(0).getUUID("Id"));
    }

    @Test
    void invalidSchemaCannotBecomeAnEmptyLegacyState() {
        UUID npcId = UUID.randomUUID();
        NpcBrainData source = new NpcBrainData();
        source.setMode(npcId, NpcBehaviorMode.PLANNED);
        source.save(selected(UUID.randomUUID(), npcId, 10L));
        CompoundTag tag = source.save(new CompoundTag(), null);
        tag.putInt("Schema", NpcBrainData.SCHEMA_VERSION + 1);

        var original = tag.copy();
        assertThrows(IllegalArgumentException.class, () -> NpcBrainData.load(tag, null));
        assertEquals(original, tag);
    }

    @Test
    void storeRejectsASecondActivePlanAndIllegalMidLifecycleStart() {
        UUID npcId = UUID.randomUUID();
        NpcBrainData data = new NpcBrainData();
        NpcPlan first = selected(UUID.randomUUID(), npcId, 10L);
        data.save(first);

        assertThrows(IllegalStateException.class,
                () -> data.save(selected(UUID.randomUUID(), npcId, 11L)));

        NpcBrainData empty = new NpcBrainData();
        NpcPlan midLifecycle = selected(UUID.randomUUID(), UUID.randomUUID(), 20L)
                .transition(NpcPlanStatus.ACTING, 21L, "already running");
        assertThrows(IllegalStateException.class, () -> empty.save(midLifecycle));
    }

    @Test
    void unknownModeAndDuplicateIdentityAreRejectedWithoutDroppingRecords() {
        var source = new NpcBrainData();
        UUID npc = UUID.randomUUID();
        source.setMode(npc, NpcBehaviorMode.PLANNED);
        var tag = source.save(new CompoundTag(), null);
        var brains = tag.getList("Brains", CompoundTag.TAG_COMPOUND);
        brains.getCompound(0).putString("Mode", "FUTURE_MODE");
        assertThrows(IllegalArgumentException.class, () -> NpcBrainData.load(tag, null));
        brains.getCompound(0).putString("Mode", "PLANNED");
        brains.add(brains.getCompound(0).copy());
        assertThrows(IllegalArgumentException.class, () -> NpcBrainData.load(tag, null));
        assertEquals(2, brains.size());
    }

    @Test
    void wrongListElementTypeCannotMasqueradeAsEmptyLedger() {
        var tag = new NpcBrainData().save(new CompoundTag(), null);
        var list = new ListTag();
        list.add(net.minecraft.nbt.StringTag.valueOf("not a brain"));
        tag.put("Brains", list);
        assertThrows(IllegalArgumentException.class, () -> NpcBrainData.load(tag, null));
    }

    @Test
    void malformedHistoryWithoutActivePlanIsRejected() {
        var source = new NpcBrainData();
        source.setMode(UUID.randomUUID(), NpcBehaviorMode.PLANNED);
        var tag = source.save(new CompoundTag(), null);
        tag.getList("Brains", CompoundTag.TAG_COMPOUND).getCompound(0)
                .getList("History", CompoundTag.TAG_COMPOUND).add(new CompoundTag());
        assertThrows(IllegalArgumentException.class, () -> NpcBrainData.load(tag, null));
    }

    private static NpcBrainData roundTrip(NpcBrainData data) {
        return NpcBrainData.load(data.save(new CompoundTag(), null), null);
    }

    private static NpcPlan selected(UUID planId, UUID npcId, long now) {
        NpcPlanStep step = new NpcPlanStep(0, NpcPlanPhase.ACTING, EXECUTOR,
                Map.of("until", Long.toString(now + 5L)), NpcPlanStepStatus.PENDING, now, "");
        return new NpcPlan(planId, npcId, "ambient:wait:" + planId, PROVIDER,
                NpcPlanKind.AMBIENT, NpcPlanPriority.AMBIENT, NpcPlanStatus.SELECTED,
                List.of(step), 0, now, now, now + 5L, now + 20L, null, "selected");
    }
}
