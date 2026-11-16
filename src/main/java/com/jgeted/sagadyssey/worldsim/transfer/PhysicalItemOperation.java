package com.jgeted.sagadyssey.worldsim.transfer;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** 共享物资的持久请求与效果证据；不是库存副本。 */
public record PhysicalItemOperation(Intent intent, Phase phase, Optional<Proof> proof) {
    public enum Phase { INTENT, ISSUING, APPLIED, DENIED }
    public record Consumption(UUID operationId, UUID container, SubjectRef actor, ItemFingerprint item, int quantity,
                              String authority, String purpose, long expectedRevision, long requestedAt) {
        public Consumption {
            Objects.requireNonNull(operationId); Objects.requireNonNull(container); Objects.requireNonNull(actor); Objects.requireNonNull(item);
            if (quantity < 1 || quantity > 1_000_000 || authority == null || authority.length() > 256 || purpose == null || purpose.isBlank()
                    || purpose.length() > 256 || expectedRevision < 0 || requestedAt < 0) throw new IllegalArgumentException("invalid consumption intent");
        }
        public CompoundTag identity() {
            var tag = new CompoundTag(); tag.store("Id", UUIDUtil.CODEC, operationId); tag.store("Container", UUIDUtil.CODEC, container); tag.put("Actor", actor.save());
            tag.put("Item", item.save()); tag.putInt("Quantity", quantity); tag.putString("Authority", authority); tag.putString("Purpose", purpose);
            tag.putLong("ExpectedRevision", expectedRevision); return tag;
        }
    }
    public record Intent(UUID id, Optional<TransferRequest> transfer, Optional<Consumption> consumption) {
        public Intent {
            Objects.requireNonNull(id); Objects.requireNonNull(transfer); Objects.requireNonNull(consumption);
            if (transfer.isPresent() == consumption.isPresent()) throw new IllegalArgumentException("exactly one item operation required");
            if (transfer.isPresent() && (!transfer.get().transferId().equals(id) || transfer.get().legs().size() > 64
                    || transfer.get().expectedRevisions().size() > 64 || transfer.get().sourceAuthorities().size() > 64 || transfer.get().authority().length() > 256
                    || transfer.get().requestedAt() < 0)) throw new IllegalArgumentException("invalid transfer intent");
            if (consumption.isPresent() && !consumption.get().operationId().equals(id)) throw new IllegalArgumentException("consumption identity mismatch");
        }
        public static Intent transfer(TransferRequest value) { return new Intent(value.transferId(), Optional.of(value), Optional.empty()); }
        public static Intent consume(Consumption value) { return new Intent(value.operationId(), Optional.empty(), Optional.of(value)); }
        public Set<UUID> endpoints() {
            Set<UUID> result = new HashSet<>(); transfer.ifPresent(request -> request.legs().forEach(leg -> { result.add(leg.sourceContainerId()); result.add(leg.destinationContainerId()); }));
            consumption.ifPresent(request -> result.add(request.container())); return Set.copyOf(result);
        }
        public CompoundTag identity() {
            var tag = new CompoundTag(); tag.store("Id", UUIDUtil.CODEC, id); tag.putString("Mode", transfer.isPresent() ? "TRANSFER" : "CONSUME");
            tag.put("Request", transfer.map(TransferRequest::identity).orElseGet(() -> consumption.orElseThrow().identity())); return tag;
        }
    }
    public record Proof(boolean applied, String reason, Map<UUID, Long> finalRevisions, long occurredAt) {
        public Proof {
            finalRevisions = Map.copyOf(finalRevisions);
            if (reason == null || reason.length() > 256 || finalRevisions.size() > 64 || finalRevisions.values().stream().anyMatch(value -> value < 0) || occurredAt < 0) throw new IllegalArgumentException("invalid item proof");
        }
        public CompoundTag save() {
            var tag = new CompoundTag(); tag.putBoolean("Applied", applied); tag.putString("Reason", reason); tag.putLong("At", occurredAt);
            var list = new net.minecraft.nbt.ListTag(); finalRevisions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                var row = new CompoundTag(); row.store("Container", UUIDUtil.CODEC, entry.getKey()); row.putLong("Revision", entry.getValue()); list.add(row);
            }); tag.put("Revisions", list); return tag;
        }
    }
    public PhysicalItemOperation {
        Objects.requireNonNull(intent); Objects.requireNonNull(phase); Objects.requireNonNull(proof);
        if ((phase == Phase.APPLIED || phase == Phase.DENIED) != proof.isPresent()
                || proof.isPresent() && (phase == Phase.APPLIED) != proof.get().applied()
                || phase == Phase.APPLIED && !proof.orElseThrow().finalRevisions().keySet().equals(intent.endpoints())) throw new IllegalArgumentException("invalid item operation phase/proof");
    }
    public static boolean follows(PhysicalItemOperation old, PhysicalItemOperation next) {
        return old.intent.equals(next.intent) && (old.equals(next) || old.phase == Phase.INTENT && (next.phase == Phase.ISSUING || next.phase == Phase.APPLIED)
                || old.phase == Phase.ISSUING && (next.phase == Phase.APPLIED || next.phase == Phase.DENIED));
    }
    public CompoundTag witness() {
        if (phase != Phase.APPLIED) throw new IllegalStateException("no applied witness"); var tag = intent.identity(); tag.put("Proof", proof.orElseThrow().save()); return tag;
    }
}
