package com.jgeted.sagadyssey.social.work;

import java.util.*;

/** 共享的主劳动占用；只引用原人物与身体，不改变其职业、家庭或生命。 */
public record WorkCommitment(Intent intent, Phase phase, Map<Operation, Receipt> receipts) {
    public enum Phase { PREPARING, ACTIVE, RELEASED }
    public enum Operation { CLAIM, ACTIVATE, RELEASE }
    public record Intent(UUID id, UUID character, UUID body, String ownerKind, UUID owner, UUID principal, String descriptorHash) {
        public Intent {
            Objects.requireNonNull(id); Objects.requireNonNull(character); Objects.requireNonNull(body); Objects.requireNonNull(owner); Objects.requireNonNull(principal);
            if (ownerKind == null || !ownerKind.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || descriptorHash == null || !descriptorHash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid work intent");
        }
    }
    public record Receipt(UUID operationId, long occurredAt) {
        public Receipt { Objects.requireNonNull(operationId); if (occurredAt < 0) throw new IllegalArgumentException("invalid work receipt time"); }
    }
    public WorkCommitment {
        Objects.requireNonNull(intent); Objects.requireNonNull(phase); receipts = Map.copyOf(receipts);
        if (!receipts.containsKey(Operation.CLAIM) || phase == Phase.PREPARING && receipts.size() != 1
                || phase == Phase.ACTIVE && (!receipts.containsKey(Operation.ACTIVATE) || receipts.size() != 2)
                || phase == Phase.RELEASED && !receipts.containsKey(Operation.RELEASE)) throw new IllegalArgumentException("invalid work sequence");
        Set<UUID> ids = new HashSet<>(); long previous = -1;
        for (Operation step : Operation.values()) if (receipts.containsKey(step)) {
            Receipt receipt = receipts.get(step);
            if (!ids.add(receipt.operationId()) || receipt.occurredAt() < previous) throw new IllegalArgumentException("work receipt sequence conflict"); previous = receipt.occurredAt();
        }
    }
    public boolean holdsPerson() { return phase != Phase.RELEASED; }
    public static WorkCommitment claim(Intent intent, UUID operation, long now) { return new WorkCommitment(intent, Phase.PREPARING, Map.of(Operation.CLAIM, new Receipt(operation, now))); }
    public WorkCommitment advance(Operation operation, UUID operationId, long now) {
        if (operation == Operation.CLAIM || phase == Phase.RELEASED || operation == Operation.ACTIVATE && phase != Phase.PREPARING) throw new IllegalArgumentException("invalid work transition");
        Map<Operation, Receipt> next = new EnumMap<>(Operation.class); next.putAll(receipts); next.put(operation, new Receipt(operationId, now));
        return new WorkCommitment(intent, operation == Operation.ACTIVATE ? Phase.ACTIVE : Phase.RELEASED, next);
    }
    public static boolean follows(WorkCommitment previous, WorkCommitment next) {
        return previous.intent.equals(next.intent) && next.receipts.entrySet().containsAll(previous.receipts.entrySet())
                && (previous.phase != Phase.RELEASED || previous.equals(next));
    }
}
