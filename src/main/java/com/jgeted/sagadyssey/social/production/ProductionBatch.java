package com.jgeted.sagadyssey.social.production;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record ProductionBatch(UUID id, String recipeId, UUID workerId, SubjectRef outputOwner,
                              UUID containerId, List<ProductionMaterial> inputs,
                              ProductionMaterial output, int requiredWorkTicks,
                              long startedAt, long finishedAt, ProductionBatchStatus status,
                              String explanation) {
    public ProductionBatch {
        if (id == null || recipeId == null || recipeId.isBlank() || workerId == null
                || outputOwner == null || containerId == null || inputs == null || inputs.isEmpty()
                || output == null || requiredWorkTicks <= 0 || status == null) {
            throw new IllegalArgumentException("invalid production batch");
        }
        inputs = List.copyOf(inputs);
        explanation = explanation == null ? "" : explanation;
    }

    public String token() { return "production:" + id; }

    /** 比较可重放生产意图；状态、说明和完成时间不参与。 */
    public boolean sameIntentAs(ProductionBatch other) {
        return other != null
                && id.equals(other.id)
                && recipeId.equals(other.recipeId)
                && workerId.equals(other.workerId)
                && outputOwner.equals(other.outputOwner)
                && containerId.equals(other.containerId)
                && inputs.equals(other.inputs)
                && output.equals(other.output)
                && requiredWorkTicks == other.requiredWorkTicks;
    }

    public ProductionBatch finish(ProductionBatchStatus next, long now, String reason) {
        if (!next.terminal()) throw new IllegalArgumentException("nonterminal finish status");
        return new ProductionBatch(id, recipeId, workerId, outputOwner, containerId, inputs,
                output, requiredWorkTicks, startedAt, now, next, reason);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Recipe", recipeId);
        tag.putUUID("Worker", workerId);
        tag.put("OutputOwner", outputOwner.save());
        tag.putUUID("Container", containerId);
        ListTag inputTags = new ListTag();
        inputs.forEach(input -> inputTags.add(input.save()));
        tag.put("Inputs", inputTags);
        tag.put("Output", output.save());
        tag.putInt("RequiredWork", requiredWorkTicks);
        tag.putLong("Started", startedAt);
        tag.putLong("Finished", finishedAt);
        tag.putString("Status", status.name());
        tag.putString("Explanation", explanation);
        return tag;
    }

    static ProductionBatch load(CompoundTag tag) {
        List<ProductionMaterial> inputs = new ArrayList<>();
        ListTag list = tag.getList("Inputs", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            inputs.add(ProductionMaterial.load(list.getCompound(index)));
        }
        return new ProductionBatch(tag.getUUID("Id"), tag.getString("Recipe"),
                tag.getUUID("Worker"), SubjectRef.load(tag.getCompound("OutputOwner")),
                tag.getUUID("Container"), inputs,
                ProductionMaterial.load(tag.getCompound("Output")), tag.getInt("RequiredWork"),
                tag.getLong("Started"), tag.getLong("Finished"),
                ProductionBatchStatus.valueOf(tag.getString("Status")), tag.getString("Explanation"));
    }
}
