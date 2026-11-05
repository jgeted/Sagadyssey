package com.jgeted.sagadyssey.npc.brain.persistence;

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
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Strict, bounded NBT representation of one immutable NPC plan snapshot. */
public final class NpcPlanNbtCodec {
    public static final int MAX_STEPS = 64;
    public static final int MAX_PARAMS_PER_STEP = 64;

    private static final int MAX_RESOURCE_LOCATION_LENGTH = 256;
    private static final int MAX_CANDIDATE_KEY_LENGTH = 256;
    private static final int MAX_BLOCKER_CODE_LENGTH = 128;
    private static final int MAX_DEPENDENCY_KEY_LENGTH = 256;
    private static final int MAX_PARAM_KEY_LENGTH = 128;
    private static final int MAX_PARAM_VALUE_LENGTH = 1_024;
    private static final int MAX_DISPLAY_TEXT_LENGTH = 2_048;

    private NpcPlanNbtCodec() {
    }

    public static CompoundTag save(NpcPlan plan) {
        if (plan == null) throw new IllegalArgumentException("plan is required");
        requireStepCount(plan.steps().size());

        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", plan.id());
        tag.putUUID("NpcId", plan.npcId());
        tag.putString("CandidateKey", requireIdentity(
                plan.candidateKey(), MAX_CANDIDATE_KEY_LENGTH, "candidateKey"));
        tag.putString("ProviderId", saveResourceLocation(plan.providerId(), "providerId"));
        tag.putString("Kind", plan.kind().name());
        tag.putString("Priority", plan.priority().name());
        tag.putString("Status", plan.status().name());
        tag.putInt("CurrentStepIndex", plan.currentStepIndex());
        tag.putLong("CreatedAt", plan.createdAt());
        tag.putLong("UpdatedAt", plan.updatedAt());
        tag.putLong("CommitmentUntil", plan.commitmentUntil());
        tag.putLong("ExpiresAt", plan.expiresAt());
        tag.putString("Explanation", truncateDisplay(plan.explanation()));

        ListTag steps = new ListTag();
        for (NpcPlanStep step : plan.steps()) {
            steps.add(saveStep(step));
        }
        tag.put("Steps", steps);

        if (plan.blocker() != null) {
            tag.put("Blocker", saveBlocker(plan.blocker()));
        }
        return tag;
    }

    public static NpcPlan load(CompoundTag tag) {
        if (tag == null) throw new IllegalArgumentException("plan tag is required");

        UUID id = requireUuid(tag, "Id");
        UUID npcId = requireUuid(tag, "NpcId");
        String candidateKey = requireIdentity(
                requireString(tag, "CandidateKey"), MAX_CANDIDATE_KEY_LENGTH, "candidateKey");
        ResourceLocation providerId = parseResourceLocation(
                requireString(tag, "ProviderId"), "providerId");
        NpcPlanKind kind = parseEnum(NpcPlanKind.class, requireString(tag, "Kind"), "kind");
        NpcPlanPriority priority = parseEnum(
                NpcPlanPriority.class, requireString(tag, "Priority"), "priority");
        NpcPlanStatus status = parseEnum(
                NpcPlanStatus.class, requireString(tag, "Status"), "status");

        ListTag stepTags = requireList(tag, "Steps", Tag.TAG_COMPOUND);
        requireStepCount(stepTags.size());
        List<NpcPlanStep> steps = new ArrayList<>(stepTags.size());
        for (int index = 0; index < stepTags.size(); index++) {
            steps.add(loadStep(stepTags.getCompound(index)));
        }

        NpcPlanBlocker blocker = null;
        if (tag.contains("Blocker")) {
            if (!tag.contains("Blocker", Tag.TAG_COMPOUND)) {
                throw new IllegalArgumentException("blocker must be a compound tag");
            }
            blocker = loadBlocker(tag.getCompound("Blocker"));
        }

        return new NpcPlan(id, npcId, candidateKey, providerId, kind, priority, status,
                steps, requireInt(tag, "CurrentStepIndex"), requireLong(tag, "CreatedAt"),
                requireLong(tag, "UpdatedAt"), requireLong(tag, "CommitmentUntil"),
                requireLong(tag, "ExpiresAt"), blocker,
                truncateDisplay(requireString(tag, "Explanation")));
    }

    private static CompoundTag saveStep(NpcPlanStep step) {
        if (step == null) throw new IllegalArgumentException("plan step is required");
        requireParamCount(step.params().size());

        CompoundTag tag = new CompoundTag();
        tag.putInt("Index", step.index());
        tag.putString("Phase", step.phase().name());
        tag.putString("ExecutorId", saveResourceLocation(step.executorId(), "executorId"));
        tag.putString("Status", step.status().name());
        tag.putLong("UpdatedAt", step.updatedAt());
        tag.putString("Detail", truncateDisplay(step.detail()));

        ListTag params = new ListTag();
        step.params().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    CompoundTag param = new CompoundTag();
                    param.putString("Key", requireIdentity(
                            entry.getKey(), MAX_PARAM_KEY_LENGTH, "parameter key"));
                    param.putString("Value", requireIdentity(
                            entry.getValue(), MAX_PARAM_VALUE_LENGTH, "parameter value"));
                    params.add(param);
                });
        tag.put("Params", params);
        return tag;
    }

    private static NpcPlanStep loadStep(CompoundTag tag) {
        int index = requireInt(tag, "Index");
        NpcPlanPhase phase = parseEnum(
                NpcPlanPhase.class, requireString(tag, "Phase"), "step phase");
        ResourceLocation executorId = parseResourceLocation(
                requireString(tag, "ExecutorId"), "executorId");
        NpcPlanStepStatus status = parseEnum(
                NpcPlanStepStatus.class, requireString(tag, "Status"), "step status");

        ListTag paramTags = requireList(tag, "Params", Tag.TAG_COMPOUND);
        requireParamCount(paramTags.size());
        Map<String, String> params = new LinkedHashMap<>();
        for (int paramIndex = 0; paramIndex < paramTags.size(); paramIndex++) {
            CompoundTag paramTag = paramTags.getCompound(paramIndex);
            String key = requireIdentity(requireString(paramTag, "Key"),
                    MAX_PARAM_KEY_LENGTH, "parameter key");
            String value = requireIdentity(requireString(paramTag, "Value"),
                    MAX_PARAM_VALUE_LENGTH, "parameter value");
            if (params.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate parameter key: " + key);
            }
        }

        return new NpcPlanStep(index, phase, executorId, params, status,
                requireLong(tag, "UpdatedAt"),
                truncateDisplay(requireString(tag, "Detail")));
    }

    private static CompoundTag saveBlocker(NpcPlanBlocker blocker) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Code", requireIdentity(
                blocker.code(), MAX_BLOCKER_CODE_LENGTH, "blocker code"));
        tag.putString("Detail", truncateDisplay(blocker.detail()));
        tag.putLong("RetryAt", blocker.retryAt());
        if (blocker.dependencyKey() != null) {
            tag.putString("DependencyKey", requireIdentity(blocker.dependencyKey(),
                    MAX_DEPENDENCY_KEY_LENGTH, "dependency key"));
        }
        return tag;
    }

    private static NpcPlanBlocker loadBlocker(CompoundTag tag) {
        String dependencyKey = null;
        if (tag.contains("DependencyKey")) {
            dependencyKey = requireIdentity(requireString(tag, "DependencyKey"),
                    MAX_DEPENDENCY_KEY_LENGTH, "dependency key");
        }
        return new NpcPlanBlocker(
                requireIdentity(requireString(tag, "Code"),
                        MAX_BLOCKER_CODE_LENGTH, "blocker code"),
                truncateDisplay(requireString(tag, "Detail")),
                requireLong(tag, "RetryAt"), dependencyKey);
    }

    private static String saveResourceLocation(ResourceLocation id, String field) {
        if (id == null) throw new IllegalArgumentException(field + " is required");
        String value = requireIdentity(id.toString(), MAX_RESOURCE_LOCATION_LENGTH, field);
        if (ResourceLocation.tryParse(value) == null) {
            throw new IllegalArgumentException("invalid " + field + ": " + value);
        }
        return value;
    }

    private static ResourceLocation parseResourceLocation(String value, String field) {
        String checked = requireIdentity(value, MAX_RESOURCE_LOCATION_LENGTH, field);
        ResourceLocation parsed = ResourceLocation.tryParse(checked);
        if (parsed == null) throw new IllegalArgumentException("invalid " + field + ": " + value);
        return parsed;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid " + field + ": " + value, exception);
        }
    }

    private static UUID requireUuid(CompoundTag tag, String key) {
        if (!tag.hasUUID(key)) throw new IllegalArgumentException("missing or invalid UUID: " + key);
        try {
            return tag.getUUID(key);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid UUID: " + key, exception);
        }
    }

    private static String requireString(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_STRING)) {
            throw new IllegalArgumentException("missing or invalid string: " + key);
        }
        return tag.getString(key);
    }

    private static int requireInt(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_INT)) {
            throw new IllegalArgumentException("missing or invalid int: " + key);
        }
        return tag.getInt(key);
    }

    private static long requireLong(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LONG)) {
            throw new IllegalArgumentException("missing or invalid long: " + key);
        }
        return tag.getLong(key);
    }

    private static ListTag requireList(CompoundTag tag, String key, int elementType) {
        Tag raw = tag.get(key);
        if (!(raw instanceof ListTag list)) {
            throw new IllegalArgumentException("missing or invalid list: " + key);
        }
        if (list.getElementType() != Tag.TAG_END && list.getElementType() != elementType) {
            throw new IllegalArgumentException("invalid list element type: " + key);
        }
        return list;
    }

    private static String requireIdentity(@Nullable String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return value;
    }

    private static String truncateDisplay(@Nullable String value) {
        if (value == null || value.length() <= MAX_DISPLAY_TEXT_LENGTH) {
            return value == null ? "" : value;
        }
        int end = MAX_DISPLAY_TEXT_LENGTH;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    private static void requireStepCount(int count) {
        if (count < 1 || count > MAX_STEPS) {
            throw new IllegalArgumentException(
                    "plan step count must be between 1 and " + MAX_STEPS + ": " + count);
        }
    }

    private static void requireParamCount(int count) {
        if (count < 0 || count > MAX_PARAMS_PER_STEP) {
            throw new IllegalArgumentException(
                    "step parameter count exceeds " + MAX_PARAMS_PER_STEP + ": " + count);
        }
    }
}
