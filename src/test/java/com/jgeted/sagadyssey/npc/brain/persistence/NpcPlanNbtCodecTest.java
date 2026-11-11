package com.jgeted.sagadyssey.npc.brain.persistence;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanBlocker;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPriority;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStepStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcPlanNbtCodecTest {
    private static final Identifier PROVIDER = Identifier.fromNamespaceAndPath(
            "sagadyssey", "blacksmith/provider");
    private static final Identifier RESERVE_EXECUTOR = Identifier.fromNamespaceAndPath(
            "sagadyssey", "reserve_materials");
    private static final Identifier TRAVEL_EXECUTOR = Identifier.fromNamespaceAndPath(
            "sagadyssey", "travel_to_workstation");
    private static final Identifier ACT_EXECUTOR = Identifier.fromNamespaceAndPath(
            "sagadyssey", "forge_order");
    private static final Identifier SETTLE_EXECUTOR = Identifier.fromNamespaceAndPath(
            "sagadyssey", "settle_output");

    @Test
    void roundTripsEveryFieldOfAMultiStepPlan() {
        NpcPlan original = new NpcPlan(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                UUID.fromString("123e4567-e89b-12d3-a456-426614174001"),
                "work:blacksmith:order-17", PROVIDER, NpcPlanKind.WORK,
                NpcPlanPriority.HIGH, NpcPlanStatus.ACTING,
                List.of(
                        step(0, NpcPlanPhase.RESERVING, RESERVE_EXECUTOR,
                                Map.of("item", "minecraft:iron_ingot", "count", "3"),
                                NpcPlanStepStatus.SUCCEEDED, 110L, "材料已预留"),
                        step(1, NpcPlanPhase.TRAVELLING, TRAVEL_EXECUTOR,
                                Map.of("station", "forge:main"),
                                NpcPlanStepStatus.SUCCEEDED, 120L, "已经抵达"),
                        step(2, NpcPlanPhase.ACTING, ACT_EXECUTOR,
                                Map.of("recipe", "minecraft:iron_pickaxe", "order", "17"),
                                NpcPlanStepStatus.RUNNING, 130L, "正在锻造"),
                        step(3, NpcPlanPhase.SETTLING, SETTLE_EXECUTOR,
                                Map.of("destination", "shop:smithy"),
                                NpcPlanStepStatus.PENDING, 100L, "等待结算")),
                2, 100L, 130L, 160L, 400L, null, "履行铁匠订单");

        assertEquals(original, NpcPlanNbtCodec.load(NpcPlanNbtCodec.save(original)));
    }

    @Test
    void roundTripsBlockedAndWaitingPlansWithStructuredBlockers() {
        NpcPlanBlocker blocker = new NpcPlanBlocker(
                "MATERIAL_MISSING", "缺少三个煤炭", 240L, "request:coal:24");
        for (NpcPlanStatus status : List.of(NpcPlanStatus.BLOCKED, NpcPlanStatus.WAITING)) {
            NpcPlan original = new NpcPlan(
                    UUID.randomUUID(), UUID.randomUUID(), "work:blacksmith:waiting",
                    PROVIDER, NpcPlanKind.WORK, NpcPlanPriority.NORMAL, status,
                    List.of(step(0, NpcPlanPhase.RESERVING, RESERVE_EXECUTOR,
                            Map.of("request", "24"), NpcPlanStepStatus.BLOCKED,
                            180L, "等待运输")),
                    0, 100L, 180L, 200L, 500L, blocker, "等待物资请求 #24");

            assertEquals(original, NpcPlanNbtCodec.load(NpcPlanNbtCodec.save(original)));
        }
    }

    @Test
    void roundTripsAllTerminalPlanStates() {
        assertTerminalRoundTrip(NpcPlanStatus.SUCCEEDED, NpcPlanStepStatus.SUCCEEDED);
        assertTerminalRoundTrip(NpcPlanStatus.FAILED, NpcPlanStepStatus.FAILED);
        assertTerminalRoundTrip(NpcPlanStatus.CANCELLED, NpcPlanStepStatus.CANCELLED);
    }

    @Test
    void rejectsInvalidProviderAndExecutorResourceLocations() {
        CompoundTag providerTag = NpcPlanNbtCodec.save(singleStepPlan());
        providerTag.putString("ProviderId", "not a valid resource id");
        assertThrows(IllegalArgumentException.class, () -> NpcPlanNbtCodec.load(providerTag));

        CompoundTag executorTag = NpcPlanNbtCodec.save(singleStepPlan());
        MinecraftApi.listOfType(executorTag, "Steps", Tag.TAG_COMPOUND).getCompoundOrEmpty(0)
                .putString("ExecutorId", "Bad:UPPER_CASE");
        assertThrows(IllegalArgumentException.class, () -> NpcPlanNbtCodec.load(executorTag));
    }

    @Test
    void rejectsUnknownEnumValues() {
        CompoundTag tag = NpcPlanNbtCodec.save(singleStepPlan());
        tag.putString("Status", "PAUSED_SOMEHOW");
        assertThrows(IllegalArgumentException.class, () -> NpcPlanNbtCodec.load(tag));
    }

    @Test
    void rejectsMoreThanSixtyFourStepsOnSaveAndLoad() {
        List<NpcPlanStep> tooMany = new ArrayList<>();
        for (int index = 0; index <= NpcPlanNbtCodec.MAX_STEPS; index++) {
            tooMany.add(step(index, NpcPlanPhase.ACTING, ACT_EXECUTOR,
                    Map.of("index", Integer.toString(index)), NpcPlanStepStatus.PENDING,
                    100L, ""));
        }
        NpcPlan oversized = plan(tooMany, NpcPlanStatus.SELECTED, null, 0);
        assertThrows(IllegalArgumentException.class, () -> NpcPlanNbtCodec.save(oversized));

        NpcPlan maximum = plan(steps(NpcPlanNbtCodec.MAX_STEPS),
                NpcPlanStatus.SELECTED, null, 0);
        CompoundTag tag = NpcPlanNbtCodec.save(maximum);
        ListTag savedSteps = MinecraftApi.listOfType(tag, "Steps", Tag.TAG_COMPOUND);
        savedSteps.add(savedSteps.getCompoundOrEmpty(savedSteps.size() - 1).copy());
        assertThrows(IllegalArgumentException.class, () -> NpcPlanNbtCodec.load(tag));
    }

    @Test
    void rejectsMoreThanSixtyFourParametersAndUsesKeyValueList() {
        Map<String, String> tooManyParams = new LinkedHashMap<>();
        for (int index = 0; index <= NpcPlanNbtCodec.MAX_PARAMS_PER_STEP; index++) {
            tooManyParams.put("key-" + index, "value-" + index);
        }
        NpcPlan oversized = plan(List.of(step(0, NpcPlanPhase.ACTING, ACT_EXECUTOR,
                tooManyParams, NpcPlanStepStatus.PENDING, 100L, "")),
                NpcPlanStatus.SELECTED, null, 0);
        assertThrows(IllegalArgumentException.class, () -> NpcPlanNbtCodec.save(oversized));

        CompoundTag saved = NpcPlanNbtCodec.save(singleStepPlan());
        CompoundTag savedStep = MinecraftApi.listOfType(saved, "Steps", Tag.TAG_COMPOUND).getCompoundOrEmpty(0);
        ListTag params = MinecraftApi.listOfType(savedStep, "Params", Tag.TAG_COMPOUND);
        assertEquals(2, params.size());
        assertTrue(MinecraftApi.containsTag(params.getCompoundOrEmpty(0), "Key", Tag.TAG_STRING));
        assertTrue(MinecraftApi.containsTag(params.getCompoundOrEmpty(0), "Value", Tag.TAG_STRING));
        assertFalse(savedStep.contains("unsafe.key"));
        assertFalse(savedStep.contains("slash/key"));

        CompoundTag maximumTag = NpcPlanNbtCodec.save(plan(List.of(step(0,
                NpcPlanPhase.ACTING, ACT_EXECUTOR, params(NpcPlanNbtCodec.MAX_PARAMS_PER_STEP),
                NpcPlanStepStatus.PENDING, 100L, "")), NpcPlanStatus.SELECTED, null, 0));
        ListTag savedParams = MinecraftApi.listOfType(MinecraftApi.listOfType(maximumTag, "Steps", Tag.TAG_COMPOUND)
                .getCompoundOrEmpty(0), "Params", Tag.TAG_COMPOUND);
        CompoundTag duplicate = new CompoundTag();
        duplicate.putString("Key", "overflow");
        duplicate.putString("Value", "overflow");
        savedParams.add(duplicate);
        assertThrows(IllegalArgumentException.class, () -> NpcPlanNbtCodec.load(maximumTag));
    }

    @Test
    void truncatesDisplayTextButRejectsOversizedIdentityFields() {
        String longDisplay = "文".repeat(2_100);
        NpcPlan displayHeavy = new NpcPlan(
                UUID.randomUUID(), UUID.randomUUID(), "work:test", PROVIDER,
                NpcPlanKind.WORK, NpcPlanPriority.NORMAL, NpcPlanStatus.BLOCKED,
                List.of(step(0, NpcPlanPhase.ACTING, ACT_EXECUTOR, Map.of("key", "value"),
                        NpcPlanStepStatus.BLOCKED, 100L, longDisplay)),
                0, 100L, 100L, 120L, 300L,
                new NpcPlanBlocker("MISSING", longDisplay, 150L, "request:1"), longDisplay);
        NpcPlan loaded = NpcPlanNbtCodec.load(NpcPlanNbtCodec.save(displayHeavy));
        assertEquals(2_048, loaded.explanation().length());
        assertEquals(2_048, loaded.blocker().detail().length());
        assertEquals(2_048, loaded.currentStep().detail().length());

        NpcPlan invalidIdentity = new NpcPlan(
                UUID.randomUUID(), UUID.randomUUID(), "x".repeat(257), PROVIDER,
                NpcPlanKind.WORK, NpcPlanPriority.NORMAL, NpcPlanStatus.SELECTED,
                List.of(step(0, NpcPlanPhase.ACTING, ACT_EXECUTOR, Map.of(),
                        NpcPlanStepStatus.PENDING, 100L, "")),
                0, 100L, 100L, 120L, 300L, null, "");
        assertThrows(IllegalArgumentException.class, () -> NpcPlanNbtCodec.save(invalidIdentity));
    }

    private static void assertTerminalRoundTrip(NpcPlanStatus planStatus,
                                                NpcPlanStepStatus stepStatus) {
        NpcPlan original = plan(List.of(step(0, NpcPlanPhase.ACTING, ACT_EXECUTOR,
                Map.of("order", "17"), stepStatus, 150L, "terminal")),
                planStatus, null, 0);
        assertEquals(original, NpcPlanNbtCodec.load(NpcPlanNbtCodec.save(original)));
    }

    private static NpcPlan singleStepPlan() {
        return plan(List.of(step(0, NpcPlanPhase.ACTING, ACT_EXECUTOR,
                Map.of("unsafe.key", "still-a-value", "slash/key", "also-a-value"),
                NpcPlanStepStatus.PENDING, 100L, "ready")),
                NpcPlanStatus.SELECTED, null, 0);
    }

    private static List<NpcPlanStep> steps(int count) {
        List<NpcPlanStep> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(step(index, NpcPlanPhase.ACTING, ACT_EXECUTOR,
                    Map.of("index", Integer.toString(index)), NpcPlanStepStatus.PENDING,
                    100L, ""));
        }
        return result;
    }

    private static Map<String, String> params(int count) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            result.put("key-" + index, "value-" + index);
        }
        return result;
    }

    private static NpcPlan plan(List<NpcPlanStep> steps, NpcPlanStatus status,
                                NpcPlanBlocker blocker, int currentStepIndex) {
        return new NpcPlan(UUID.randomUUID(), UUID.randomUUID(), "work:blacksmith:test",
                PROVIDER, NpcPlanKind.WORK, NpcPlanPriority.NORMAL, status, steps,
                currentStepIndex, 100L, 150L, 200L, 500L, blocker, "test plan");
    }

    private static NpcPlanStep step(int index, NpcPlanPhase phase, Identifier executor,
                                    Map<String, String> params, NpcPlanStepStatus status,
                                    long updatedAt, String detail) {
        return new NpcPlanStep(index, phase, executor, params, status, updatedAt, detail);
    }
}
