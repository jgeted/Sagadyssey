package com.jgeted.sagadyssey.worldsim.transfer;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.nbt.*;
import java.util.*;

public final class PhysicalItemOperationCodec extends NbtSnapshotCodec<PhysicalItemOperation> {
    public PhysicalItemOperationCodec() { super("sagadyssey:physical_item_operations/v1", 32 * 1024 * 1024, 8192); }
    protected UUID identity(PhysicalItemOperation record) { return record.intent().id(); }
    protected CompoundTag encode(PhysicalItemOperation record) {
        var tag = record.intent().identity(); tag.putString("Phase", record.phase().name());
        long requested = record.intent().transfer().map(TransferRequest::requestedAt).orElseGet(() -> record.intent().consumption().orElseThrow().requestedAt());
        tag.putLong("Requested", requested); record.proof().ifPresent(proof -> tag.put("Proof", proof.save())); return tag;
    }
    protected PhysicalItemOperation decode(CompoundTag tag) {
        var phase = PhysicalItemOperation.Phase.valueOf(string(tag, "Phase")); boolean closed = phase == PhysicalItemOperation.Phase.APPLIED || phase == PhysicalItemOperation.Phase.DENIED;
        fields(tag, closed ? Set.of("Id", "Mode", "Request", "Phase", "Requested", "Proof") : Set.of("Id", "Mode", "Request", "Phase", "Requested"));
        UUID id = uuid(tag, "Id"); long requested = number(tag, "Requested"); var request = compound(tag, "Request"); PhysicalItemOperation.Intent intent;
        if (string(tag, "Mode").equals("TRANSFER")) {
            fields(request, Set.of("Version", "Actor", "Intent", "Authority", "Legs", "ExpectedRevisions", "SourceAuthorities"));
            if (!MinecraftApi.containsTag(request, "Version", Tag.TAG_INT) || request.getIntOr("Version", 0) != 1) throw new IllegalArgumentException("unsupported transfer identity");
            List<TransferLeg> legs = new ArrayList<>(); for (Tag raw : rows(request, "Legs", 64)) {
                var row = (CompoundTag) raw; var allowed = new HashSet<>(Set.of("Source", "Destination", "Item", "Quantity"));
                for (String key : List.of("SourceSlot", "DestinationSlot")) if (row.contains(key)) {
                    if (!MinecraftApi.containsTag(row, key, Tag.TAG_INT) || row.getIntOr(key, 0) < 0) throw new IllegalArgumentException("invalid explicit transfer slot");
                    allowed.add(key);
                }
                fields(row, allowed);
                if (!MinecraftApi.containsTag(row, "Quantity", Tag.TAG_INT)) throw new IllegalArgumentException("missing transfer quantity");
                legs.add(new TransferLeg(uuid(row, "Source"), uuid(row, "Destination"), item(compound(row, "Item")), row.getIntOr("Quantity", 0),
                        row.contains("SourceSlot") ? row.getIntOr("SourceSlot", 0) : -1, row.contains("DestinationSlot") ? row.getIntOr("DestinationSlot", 0) : -1));
            }
            List<ExpectedRevision> revisions = new ArrayList<>(); for (Tag raw : rows(request, "ExpectedRevisions", 64)) {
                var row = (CompoundTag) raw; fields(row, Set.of("Container", "Revision")); revisions.add(new ExpectedRevision(uuid(row, "Container"), number(row, "Revision")));
            }
            Map<UUID, SubjectRef> authorities = new HashMap<>(); for (Tag raw : rows(request, "SourceAuthorities", 64)) {
                var row = (CompoundTag) raw; fields(row, Set.of("Container", "Actor"));
                if (authorities.putIfAbsent(uuid(row, "Container"), subject(compound(row, "Actor"))) != null) throw new IllegalArgumentException("duplicate source authority");
            }
            intent = PhysicalItemOperation.Intent.transfer(new TransferRequest(id, subject(compound(request, "Actor")), TransferIntent.valueOf(string(request, "Intent")), legs, revisions, string(request, "Authority"), requested, authorities));
        } else if (string(tag, "Mode").equals("CONSUME")) {
            fields(request, Set.of("Id", "Container", "Actor", "Item", "Quantity", "Authority", "Purpose", "ExpectedRevision"));
            if (!id.equals(uuid(request, "Id")) || !MinecraftApi.containsTag(request, "Quantity", Tag.TAG_INT)) throw new IllegalArgumentException("invalid consumption identity");
            intent = PhysicalItemOperation.Intent.consume(new PhysicalItemOperation.Consumption(id, uuid(request, "Container"), subject(compound(request, "Actor")), item(compound(request, "Item")),
                    request.getIntOr("Quantity", 0), string(request, "Authority"), string(request, "Purpose"), number(request, "ExpectedRevision"), requested));
        } else throw new IllegalArgumentException("unknown item operation mode");
        return new PhysicalItemOperation(intent, phase, closed ? Optional.of(proof(compound(tag, "Proof"))) : Optional.empty());
    }
    static PhysicalItemOperation.Proof proof(CompoundTag tag) {
        fields(tag, Set.of("Applied", "Reason", "At", "Revisions"));
        if (!MinecraftApi.containsTag(tag, "Applied", Tag.TAG_BYTE) || tag.getByteOr("Applied", (byte)0) < 0 || tag.getByteOr("Applied", (byte)0) > 1) throw new IllegalArgumentException("invalid item proof boolean");
        Map<UUID, Long> revisions = new HashMap<>(); for (Tag raw : rows(tag, "Revisions", 64)) {
            var row = (CompoundTag) raw; fields(row, Set.of("Container", "Revision"));
            if (revisions.putIfAbsent(uuid(row, "Container"), number(row, "Revision")) != null) throw new IllegalArgumentException("duplicate final revision");
        }
        return new PhysicalItemOperation.Proof(tag.getBooleanOr("Applied", false), string(tag, "Reason"), revisions, number(tag, "At"));
    }
    private static CompoundTag compound(CompoundTag tag, String key) { if (!(tag.get(key) instanceof CompoundTag value)) throw new IllegalArgumentException("missing compound"); return value; }
    private static ListTag rows(CompoundTag tag, String key, int limit) {
        if (!(tag.get(key) instanceof ListTag list) || list.size() > limit || !list.isEmpty() && MinecraftApi.elementType(list) != Tag.TAG_COMPOUND) throw new IllegalArgumentException("invalid item operation list"); return list;
    }
    private static SubjectRef subject(CompoundTag tag) { fields(tag, Set.of("Type", "Id")); uuid(tag, "Id"); string(tag, "Type"); return SubjectRef.load(tag); }
    private static ItemFingerprint item(CompoundTag tag) {
        fields(tag, Set.of("Item", "Components")); string(tag, "Item"); if (!MinecraftApi.containsTag(tag, "Components", Tag.TAG_INT)) throw new IllegalArgumentException("missing component identity"); return ItemFingerprint.load(tag);
    }
}
