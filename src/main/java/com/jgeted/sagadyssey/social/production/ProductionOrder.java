package com.jgeted.sagadyssey.social.production;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 从社会需求到真实生产批次的持久承诺。
 *
 * <p>订单和批次身份从创建起固定；所有变更返回新快照，便于保存层验证。</p>
 */
public record ProductionOrder(UUID id, SubjectRef issuer, SubjectRef outputOwner,
                              String recipeId, List<ProductionMaterial> inputs,
                              ProductionMaterial output, int requiredWorkTicks, UUID batchId,
                              @Nullable UUID assignedWorkerId,
                              @Nullable String workstationDimension,
                              @Nullable BlockPos workstationPosition,
                              UUID targetContainerId, String targetContainerDimension,
                              BlockPos targetContainerPosition, ProductionOrderStatus status,
                              @Nullable ProductionOrderStatus blockedFromStatus,
                              @Nullable ProductionOrderBlocker blocker,
                              long createdAt, long updatedAt, String detail) {
    public static final int MAX_INPUTS = 64;
    private static final int MAX_IDENTITY_LENGTH = 256;
    private static final int MAX_DETAIL_LENGTH = 2_048;

    public ProductionOrder {
        if (id == null || issuer == null || outputOwner == null || output == null
                || batchId == null || targetContainerId == null
                || targetContainerPosition == null || status == null) {
            throw new IllegalArgumentException("production order identity is incomplete");
        }
        if (requiredWorkTicks < 1 || requiredWorkTicks > 72_000) {
            throw new IllegalArgumentException("requiredWorkTicks must be between 1 and 72000");
        }
        recipeId = requireResourceId(recipeId, "recipeId");
        targetContainerDimension = requireResourceId(
                targetContainerDimension, "targetContainerDimension");
        inputs = List.copyOf(inputs == null ? List.of() : inputs);
        if (inputs.isEmpty() || inputs.size() > MAX_INPUTS) {
            throw new IllegalArgumentException("production order requires 1-" + MAX_INPUTS + " inputs");
        }
        Set<ItemFingerprint> fingerprints = new HashSet<>();
        if (inputs.stream().anyMatch(input -> input == null
                || !fingerprints.add(input.fingerprint()))) {
            throw new IllegalArgumentException("production inputs must be non-null and unique");
        }
        boolean hasWorkstationDimension = workstationDimension != null;
        boolean hasWorkstationPosition = workstationPosition != null;
        if (hasWorkstationDimension != hasWorkstationPosition) {
            throw new IllegalArgumentException("workstation dimension and position must appear together");
        }
        if (workstationDimension != null) {
            workstationDimension = requireResourceId(workstationDimension, "workstationDimension");
        }
        if (createdAt < 0L || updatedAt < createdAt) {
            throw new IllegalArgumentException("invalid production order timestamps");
        }
        if (status == ProductionOrderStatus.BLOCKED) {
            if (blockedFromStatus == null || blocker == null || blockedFromStatus.terminal()
                    || blockedFromStatus == ProductionOrderStatus.BLOCKED) {
                throw new IllegalArgumentException("blocked order requires a valid prior state and blocker");
            }
        } else if (blockedFromStatus != null || blocker != null) {
            throw new IllegalArgumentException("only blocked orders may retain blocker state");
        }
        ProductionOrderStatus effective = status == ProductionOrderStatus.BLOCKED
                ? blockedFromStatus : status;
        if (requiresWorker(effective) && assignedWorkerId == null) {
            throw new IllegalArgumentException("claimed production order requires a worker");
        }
        detail = truncate(detail == null ? "" : detail, MAX_DETAIL_LENGTH);
    }

    public static ProductionOrder open(UUID id, SubjectRef issuer, SubjectRef outputOwner,
                                       String recipeId, List<ProductionMaterial> inputs,
                                       ProductionMaterial output, int requiredWorkTicks,
                                       UUID batchId,
                                       @Nullable String workstationDimension,
                                       @Nullable BlockPos workstationPosition,
                                       UUID targetContainerId, String targetContainerDimension,
                                       BlockPos targetContainerPosition, long now, String detail) {
        return new ProductionOrder(id, issuer, outputOwner, recipeId, inputs, output,
                requiredWorkTicks, batchId,
                null, workstationDimension, workstationPosition, targetContainerId,
                targetContainerDimension, targetContainerPosition, ProductionOrderStatus.OPEN,
                null, null, now, now, detail);
    }

    public boolean terminal() {
        return status.terminal();
    }

    public String token() {
        return "production-order:" + id;
    }

    public ProductionOrder claim(UUID workerId, long now, String nextDetail) {
        return claim(workerId, workstationDimension, workstationPosition, now, nextDetail);
    }

    public ProductionOrder claim(UUID workerId, @Nullable String nextWorkstationDimension,
                                 @Nullable BlockPos nextWorkstationPosition,
                                 long now, String nextDetail) {
        if (workerId == null) throw new IllegalArgumentException("workerId is required");
        status.requireTransitionTo(ProductionOrderStatus.CLAIMED);
        if (workstationPosition != null && (!workstationPosition.equals(nextWorkstationPosition)
                || !workstationDimension.equals(nextWorkstationDimension))) {
            throw new IllegalArgumentException("preassigned workstation cannot change");
        }
        return copy(ProductionOrderStatus.CLAIMED, workerId,
                nextWorkstationDimension, nextWorkstationPosition,
                null, null, now, nextDetail);
    }

    public ProductionOrder transition(ProductionOrderStatus next, long now, String nextDetail) {
        if (next == ProductionOrderStatus.CLAIMED) {
            throw new IllegalArgumentException("use claim() to assign a worker");
        }
        if (next == ProductionOrderStatus.BLOCKED) {
            throw new IllegalArgumentException("use block() with a structured blocker");
        }
        if (status == ProductionOrderStatus.BLOCKED && !next.terminal()) {
            throw new IllegalStateException("use resume() to leave BLOCKED");
        }
        status.requireTransitionTo(next);
        return copy(next, assignedWorkerId, workstationDimension, workstationPosition,
                null, null, now, nextDetail);
    }

    public ProductionOrder block(ProductionOrderBlocker nextBlocker,
                                 long now, String nextDetail) {
        if (nextBlocker == null) throw new IllegalArgumentException("blocker is required");
        status.requireTransitionTo(ProductionOrderStatus.BLOCKED);
        return copy(ProductionOrderStatus.BLOCKED, assignedWorkerId,
                workstationDimension, workstationPosition,
                status, nextBlocker, now, nextDetail);
    }

    public ProductionOrder resume(long now, String nextDetail) {
        if (status != ProductionOrderStatus.BLOCKED || blockedFromStatus == null) {
            throw new IllegalStateException("only a blocked order can resume");
        }
        status.requireTransitionTo(blockedFromStatus);
        return copy(blockedFromStatus, assignedWorkerId, workstationDimension,
                workstationPosition, null, null, now, nextDetail);
    }

    boolean sameFixedIdentity(ProductionOrder other) {
        return other != null && id.equals(other.id)
                && issuer.equals(other.issuer)
                && outputOwner.equals(other.outputOwner)
                && recipeId.equals(other.recipeId)
                && inputs.equals(other.inputs)
                && output.equals(other.output)
                && requiredWorkTicks == other.requiredWorkTicks
                && batchId.equals(other.batchId)
                && targetContainerId.equals(other.targetContainerId)
                && targetContainerDimension.equals(other.targetContainerDimension)
                && targetContainerPosition.equals(other.targetContainerPosition);
    }

    private ProductionOrder copy(ProductionOrderStatus nextStatus, @Nullable UUID workerId,
                                 @Nullable String nextWorkstationDimension,
                                 @Nullable BlockPos nextWorkstationPosition,
                                 @Nullable ProductionOrderStatus nextBlockedFrom,
                                 @Nullable ProductionOrderBlocker nextBlocker,
                                 long now, String nextDetail) {
        if (now < updatedAt) throw new IllegalArgumentException("order time cannot move backwards");
        return new ProductionOrder(id, issuer, outputOwner, recipeId, inputs, output,
                requiredWorkTicks, batchId,
                workerId, nextWorkstationDimension, nextWorkstationPosition, targetContainerId,
                targetContainerDimension, targetContainerPosition, nextStatus,
                nextBlockedFrom, nextBlocker, createdAt, now, nextDetail);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.put("Issuer", issuer.save());
        tag.put("OutputOwner", outputOwner.save());
        tag.putString("Recipe", recipeId);
        ListTag inputTags = new ListTag();
        inputs.forEach(input -> inputTags.add(input.save()));
        tag.put("Inputs", inputTags);
        tag.put("Output", output.save());
        tag.putInt("RequiredWorkTicks", requiredWorkTicks);
        tag.putUUID("BatchId", batchId);
        if (assignedWorkerId != null) tag.putUUID("AssignedWorker", assignedWorkerId);
        if (workstationDimension != null && workstationPosition != null) {
            tag.putString("WorkstationDimension", workstationDimension);
            tag.putLong("WorkstationPosition", workstationPosition.asLong());
        }
        tag.putUUID("TargetContainer", targetContainerId);
        tag.putString("TargetDimension", targetContainerDimension);
        tag.putLong("TargetPosition", targetContainerPosition.asLong());
        tag.putString("Status", status.name());
        if (blockedFromStatus != null) tag.putString("BlockedFrom", blockedFromStatus.name());
        if (blocker != null) tag.put("Blocker", blocker.save());
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("UpdatedAt", updatedAt);
        tag.putString("Detail", detail);
        return tag;
    }

    static ProductionOrder load(CompoundTag tag) {
        requireUuid(tag, "Id");
        requireUuid(tag, "BatchId");
        requireUuid(tag, "TargetContainer");
        requireCompound(tag, "Issuer");
        requireCompound(tag, "OutputOwner");
        requireCompound(tag, "Output");
        if (!tag.contains("RequiredWorkTicks", Tag.TAG_INT)) {
            throw new IllegalArgumentException("missing required work ticks");
        }
        requireString(tag, "Recipe");
        requireString(tag, "TargetDimension");
        requireString(tag, "Status");
        requireString(tag, "Detail");
        requireLong(tag, "TargetPosition");
        requireLong(tag, "CreatedAt");
        requireLong(tag, "UpdatedAt");
        if (!tag.contains("Inputs", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("missing production inputs");
        }
        ListTag inputTags = tag.getList("Inputs", Tag.TAG_COMPOUND);
        if (inputTags.isEmpty() || inputTags.size() > MAX_INPUTS) {
            throw new IllegalArgumentException("invalid production input count");
        }
        List<ProductionMaterial> inputs = new ArrayList<>(inputTags.size());
        for (int index = 0; index < inputTags.size(); index++) {
            inputs.add(ProductionMaterial.load(inputTags.getCompound(index)));
        }

        if (tag.contains("AssignedWorker") && !tag.hasUUID("AssignedWorker")) {
            throw new IllegalArgumentException("invalid assigned worker UUID");
        }
        UUID workerId = tag.hasUUID("AssignedWorker") ? tag.getUUID("AssignedWorker") : null;
        String workstationDimension = null;
        BlockPos workstationPosition = null;
        boolean hasWorkstationDimension = tag.contains("WorkstationDimension", Tag.TAG_STRING);
        boolean hasWorkstationPosition = tag.contains("WorkstationPosition", Tag.TAG_LONG);
        if (tag.contains("WorkstationDimension") != hasWorkstationDimension
                || tag.contains("WorkstationPosition") != hasWorkstationPosition) {
            throw new IllegalArgumentException("invalid workstation locator type");
        }
        if (hasWorkstationDimension != hasWorkstationPosition) {
            throw new IllegalArgumentException("incomplete workstation locator");
        }
        if (hasWorkstationDimension) {
            workstationDimension = tag.getString("WorkstationDimension");
            workstationPosition = BlockPos.of(tag.getLong("WorkstationPosition"));
        }

        ProductionOrderStatus status = parseStatus(tag.getString("Status"), "status");
        ProductionOrderStatus blockedFrom = null;
        ProductionOrderBlocker blocker = null;
        if (tag.contains("BlockedFrom") && !tag.contains("BlockedFrom", Tag.TAG_STRING)) {
            throw new IllegalArgumentException("invalid blocked-from status type");
        }
        if (tag.contains("BlockedFrom", Tag.TAG_STRING)) {
            blockedFrom = parseStatus(tag.getString("BlockedFrom"), "blockedFromStatus");
        }
        if (tag.contains("Blocker") && !tag.contains("Blocker", Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("invalid blocker type");
        }
        if (tag.contains("Blocker", Tag.TAG_COMPOUND)) {
            blocker = ProductionOrderBlocker.load(tag.getCompound("Blocker"));
        }
        return new ProductionOrder(tag.getUUID("Id"),
                SubjectRef.load(tag.getCompound("Issuer")),
                SubjectRef.load(tag.getCompound("OutputOwner")), tag.getString("Recipe"),
                inputs, ProductionMaterial.load(tag.getCompound("Output")),
                tag.getInt("RequiredWorkTicks"), tag.getUUID("BatchId"), workerId,
                workstationDimension, workstationPosition,
                tag.getUUID("TargetContainer"), tag.getString("TargetDimension"),
                BlockPos.of(tag.getLong("TargetPosition")), status, blockedFrom, blocker,
                tag.getLong("CreatedAt"), tag.getLong("UpdatedAt"), tag.getString("Detail"));
    }

    private static boolean requiresWorker(ProductionOrderStatus status) {
        return status == ProductionOrderStatus.CLAIMED
                || status == ProductionOrderStatus.RESERVED
                || status == ProductionOrderStatus.IN_PROGRESS
                || status == ProductionOrderStatus.SETTLING
                || status == ProductionOrderStatus.COMPLETED;
    }

    private static String requireResourceId(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_IDENTITY_LENGTH
                || ResourceLocation.tryParse(value) == null) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    private static ProductionOrderStatus parseStatus(String value, String field) {
        try {
            return ProductionOrderStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid " + field + ": " + value, exception);
        }
    }

    private static void requireUuid(CompoundTag tag, String key) {
        if (!tag.hasUUID(key)) throw new IllegalArgumentException("missing UUID: " + key);
    }

    private static void requireCompound(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("missing compound: " + key);
        }
    }

    private static void requireString(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_STRING)) {
            throw new IllegalArgumentException("missing string: " + key);
        }
    }

    private static void requireLong(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LONG)) {
            throw new IllegalArgumentException("missing long: " + key);
        }
    }

    private static String truncate(String value, int limit) {
        if (value.length() <= limit) return value;
        int end = limit;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
}
