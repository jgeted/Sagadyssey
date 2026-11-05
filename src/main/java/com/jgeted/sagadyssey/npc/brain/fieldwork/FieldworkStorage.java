package com.jgeted.sagadyssey.npc.brain.fieldwork;

import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import java.util.Objects;
import java.util.UUID;

/** 交付目标快照。权限仍须在执行时重核，不能把管理员测试绑定当成普通工作权威。 */
public record FieldworkStorage(String dimension, BlockPos position, UUID containerId,
                                SubjectRef owner, AccessPolicy policy, TransferIntent intent, String authority) {
    public FieldworkStorage {
        FieldworkAction.requireText(dimension, 256, "dimension");
        position = Objects.requireNonNull(position).immutable(); FieldworkAction.requirePosition(position);
        Objects.requireNonNull(containerId); Objects.requireNonNull(owner); Objects.requireNonNull(policy); Objects.requireNonNull(intent);
        FieldworkAction.requireText(authority, 256, "authority");
    }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", dimension); tag.putLong("Position", position.asLong()); tag.putUUID("ContainerId", containerId);
        tag.put("Owner", owner.save()); tag.putString("Policy", policy.name()); tag.putString("Intent", intent.name()); tag.putString("Authority", authority);
        return tag;
    }
    public static FieldworkStorage load(CompoundTag tag) {
        if (!tag.contains("Position", net.minecraft.nbt.Tag.TAG_LONG)) throw new IllegalArgumentException("missing storage position");
        return new FieldworkStorage(tag.getString("Dimension"), BlockPos.of(tag.getLong("Position")), tag.getUUID("ContainerId"),
                SubjectRef.load(tag.getCompound("Owner")), AccessPolicy.valueOf(tag.getString("Policy")),
                TransferIntent.valueOf(tag.getString("Intent")), tag.getString("Authority"));
    }

    /** 先保存请求再调用统一转移；重载复用同一个 transferId 和原始请求负载。 */
    public record Pending(UUID npcId, UUID sourceId, SubjectRef actor, FieldworkStorage storage, UUID transferId,
                          ItemFingerprint fingerprint, int quantity, long sourceRevision, long targetRevision, long requestedAt) {
        public Pending {
            Objects.requireNonNull(npcId); Objects.requireNonNull(sourceId); Objects.requireNonNull(actor); Objects.requireNonNull(storage);
            Objects.requireNonNull(transferId); Objects.requireNonNull(fingerprint);
            if (quantity <= 0 || sourceRevision < 0 || targetRevision < 0 || requestedAt < 0) throw new IllegalArgumentException("invalid pending delivery");
        }
        public TransferRequest request() {
            return new TransferRequest(transferId, actor, storage.intent(),
                    java.util.List.of(new TransferLeg(sourceId, storage.containerId(), fingerprint, quantity)),
                    java.util.List.of(new ExpectedRevision(sourceId, sourceRevision), new ExpectedRevision(storage.containerId(), targetRevision)),
                    storage.authority(), requestedAt);
        }
        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("NpcId", npcId); tag.putUUID("SourceId", sourceId); tag.putUUID("TransferId", transferId);
            tag.put("Actor", actor.save()); tag.put("Storage", storage.save()); tag.put("Fingerprint", fingerprint.save());
            tag.putInt("Quantity", quantity); tag.putLong("SourceRevision", sourceRevision); tag.putLong("TargetRevision", targetRevision); tag.putLong("RequestedAt", requestedAt);
            return tag;
        }
        public static Pending load(CompoundTag tag) {
            for (String key : java.util.List.of("Quantity", "SourceRevision", "TargetRevision", "RequestedAt")) {
                int type = key.equals("Quantity") ? net.minecraft.nbt.Tag.TAG_INT : net.minecraft.nbt.Tag.TAG_LONG;
                if (!tag.contains(key, type)) throw new IllegalArgumentException("missing or invalid pending delivery field: " + key);
            }
            return new Pending(tag.getUUID("NpcId"), tag.getUUID("SourceId"), SubjectRef.load(tag.getCompound("Actor")),
                    FieldworkStorage.load(tag.getCompound("Storage")), tag.getUUID("TransferId"), ItemFingerprint.load(tag.getCompound("Fingerprint")),
                    tag.getInt("Quantity"), tag.getLong("SourceRevision"), tag.getLong("TargetRevision"), tag.getLong("RequestedAt"));
        }
    }
}
