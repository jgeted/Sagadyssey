package com.jgeted.sagadyssey.social.work;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import net.minecraft.nbt.*;
import java.util.*;

/** 新的共享日志格式；不改动原人物、生活或生产存档。 */
public final class WorkCommitmentCodec extends NbtSnapshotCodec<WorkCommitment> {
    public WorkCommitmentCodec() { super("sagadyssey:work_commitments/v1", 16 * 1024 * 1024, 4096); }
    protected UUID identity(WorkCommitment record) { return record.intent().id(); }
    protected void validate(Collection<WorkCommitment> records) {
        Set<UUID> characters = new HashSet<>(), bodies = new HashSet<>();
        for (var record : records) if (record.holdsPerson() && (!characters.add(record.intent().character()) || !bodies.add(record.intent().body()))) throw new IllegalArgumentException("duplicate held person/body");
    }
    protected CompoundTag encode(WorkCommitment record) {
        var tag = new CompoundTag(); var intent = record.intent();
        tag.store("Id", UUIDUtil.CODEC, intent.id()); tag.store("Character", UUIDUtil.CODEC, intent.character()); tag.store("Body", UUIDUtil.CODEC, intent.body()); tag.putString("OwnerKind", intent.ownerKind());
        tag.store("Owner", UUIDUtil.CODEC, intent.owner()); tag.store("Principal", UUIDUtil.CODEC, intent.principal()); tag.putString("Descriptor", intent.descriptorHash()); tag.putString("Phase", record.phase().name());
        var receipts = new ListTag(); for (var operation : WorkCommitment.Operation.values()) if (record.receipts().containsKey(operation)) {
            var row = new CompoundTag(); var receipt = record.receipts().get(operation); row.putString("Operation", operation.name()); row.store("Id", UUIDUtil.CODEC, receipt.operationId()); row.putLong("At", receipt.occurredAt()); receipts.add(row);
        } tag.put("Receipts", receipts); return tag;
    }
    protected WorkCommitment decode(CompoundTag tag) {
        fields(tag, Set.of("Id", "Character", "Body", "OwnerKind", "Owner", "Principal", "Descriptor", "Phase", "Receipts"));
        var intent = new WorkCommitment.Intent(uuid(tag, "Id"), uuid(tag, "Character"), uuid(tag, "Body"), string(tag, "OwnerKind"), uuid(tag, "Owner"), uuid(tag, "Principal"), string(tag, "Descriptor"));
        if (!(tag.get("Receipts") instanceof ListTag list) || list.size() > 3 || list.isEmpty() || MinecraftApi.elementType(list) != Tag.TAG_COMPOUND) throw new IllegalArgumentException("invalid work receipts");
        Map<WorkCommitment.Operation, WorkCommitment.Receipt> receipts = new EnumMap<>(WorkCommitment.Operation.class);
        for (Tag raw : list) { var row = (CompoundTag) raw; fields(row, Set.of("Operation", "Id", "At"));
            if (receipts.putIfAbsent(WorkCommitment.Operation.valueOf(string(row, "Operation")), new WorkCommitment.Receipt(uuid(row, "Id"), number(row, "At"))) != null) throw new IllegalArgumentException("duplicate work operation"); }
        return new WorkCommitment(intent, WorkCommitment.Phase.valueOf(string(tag, "Phase")), receipts);
    }
}
