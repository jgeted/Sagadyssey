package com.jgeted.sagadyssey.npc.brain.fieldwork;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import java.util.*;

/** 只交付本系统真正生产且仍携带的数量，不能把种子、借用物或原有垫脚材料当成产出。 */
public final class FieldworkCargo {
    private FieldworkCargo() { }

    public record Delivery(UUID transferId, UUID npcId, String fingerprint, int quantity, long committedAt) {
        public Delivery {
            Objects.requireNonNull(transferId);
            Objects.requireNonNull(npcId);
            FieldworkAction.requireText(fingerprint, 1_024, "fingerprint");
            if (quantity <= 0 || committedAt < 0) throw new IllegalArgumentException("invalid delivery");
        }
    }

    public enum ConsumptionState { PENDING, COMMITTED }

    /** Actual final consumption is separate from delivery and from world-mutation receipts. */
    public record OutputConsumption(String key, UUID npcId, UUID personId, UUID containerId,
                                    SubjectRef owner, String fingerprint, int quantity, int outputQuantity,
                                    long startedAt, long committedAt, long beforeRevision, long finalRevision,
                                    ConsumptionState state) {
        public OutputConsumption {
            FieldworkAction.requireText(key, 1_024, "consumption key");
            FieldworkAction.requireText(fingerprint, 1_024, "consumption item");
            Objects.requireNonNull(npcId); Objects.requireNonNull(personId); Objects.requireNonNull(containerId);
            Objects.requireNonNull(owner); Objects.requireNonNull(state);
            if (!containerId.equals(ContainerIds.npcPersonal(personId)) || quantity <= 0
                    || outputQuantity < 0 || outputQuantity > quantity || startedAt < 0 || beforeRevision < 0
                    || outputQuantity > 0 && !owner.equals(SubjectRef.character(personId)))
                throw new IllegalArgumentException("invalid output consumption identity, title or quantity");
            if (state == ConsumptionState.PENDING && (committedAt != -1 || finalRevision != -1)
                    || state == ConsumptionState.COMMITTED && (committedAt < startedAt || beforeRevision == Long.MAX_VALUE || finalRevision != beforeRevision + 1))
                throw new IllegalArgumentException("invalid output consumption checkpoint");
        }
        public UUID ownerId() { return owner.id(); }
        public OutputConsumption commit(int actualConsumed, long revision, long now) {
            if (actualConsumed != quantity) throw new IllegalStateException("actual consumption differs from intent");
            return new OutputConsumption(key, npcId, personId, containerId, owner, fingerprint, quantity,
                    outputQuantity, startedAt, now, beforeRevision, revision, ConsumptionState.COMMITTED);
        }
    }

    private record Event(long time, String key, FieldworkActionReceipt action, Delivery delivery, OutputConsumption consumption) { }

    public static Map<String, Integer> pending(List<FieldworkActionReceipt> actions, List<Delivery> deliveries) {
        return pending(actions, deliveries, List.of());
    }
    public static Map<String, Integer> pending(List<FieldworkActionReceipt> actions, List<Delivery> deliveries,
                                               List<OutputConsumption> consumptions) {
        List<Event> events = new ArrayList<>();
        for (var action : actions) events.add(new Event(action.committedAt(), "a:" + action.action().key(), action, null, null));
        for (var delivery : deliveries) events.add(new Event(delivery.committedAt(), "z:" + delivery.transferId(), null, delivery, null));
        Map<String, OutputConsumption> unique = new HashMap<>();
        for (var consumption : consumptions) {
            var previous = unique.putIfAbsent(consumption.key(), consumption);
            if (previous != null && !previous.equals(consumption)) throw new IllegalStateException("conflicting output consumption");
            if (previous == null && consumption.state() == ConsumptionState.COMMITTED)
                events.add(new Event(consumption.committedAt(), "c:" + consumption.key(), null, null, consumption));
        }
        events.sort(Comparator.comparingLong(Event::time).thenComparing(Event::key));
        Map<String, Integer> cargo = new HashMap<>();
        Map<String, Map<String, Integer>> scaffolds = new HashMap<>();
        for (Event event : events) {
            if (event.consumption != null) {
                var used = event.consumption;
                if (cargo.getOrDefault(used.fingerprint(), 0) < used.outputQuantity())
                    throw new IllegalStateException("output consumption exceeds its recorded prior production");
                change(cargo, used.fingerprint(), -used.outputQuantity());
                continue;
            }
            if (event.delivery != null) {
                change(cargo, event.delivery.fingerprint(), -event.delivery.quantity());
                continue;
            }
            var action = event.action;
            var operation = action.action().operation();
            String location = action.action().dimension() + ":" + action.action().target().asLong();
            if (operation == FieldworkAction.Operation.RECOVER_SCAFFOLD) {
                Map<String, Integer> held = scaffolds.remove(location);
                if (held != null) held.forEach((item, amount) -> change(cargo, item, Math.min(amount,
                        action.inventoryDeltas().stream().filter(delta -> delta.fingerprint().equals(item) && delta.quantity() > 0)
                                .mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum())));
                continue;
            }
            Map<String, Integer> held = new HashMap<>();
            for (var delta : action.inventoryDeltas()) {
                if (operation == FieldworkAction.Operation.PLACE_SCAFFOLD && delta.quantity() < 0) {
                    held.put(delta.fingerprint(), Math.min(cargo.getOrDefault(delta.fingerprint(), 0), -delta.quantity()));
                }
                // 所有投入都会消耗已有产出额度；恢复原有脚手架不会凭空成为新产出。
                if (delta.quantity() < 0 || operation == FieldworkAction.Operation.MINE
                        || operation == FieldworkAction.Operation.CHOP || operation == FieldworkAction.Operation.HARVEST
                        || operation == FieldworkAction.Operation.IRRIGATE && !delta.fingerprint().startsWith("minecraft:bucket")) {
                    change(cargo, delta.fingerprint(), delta.quantity());
                }
            }
            if (operation == FieldworkAction.Operation.PLACE_SCAFFOLD) scaffolds.put(location, held);
        }
        cargo.values().removeIf(value -> value == 0);
        return Map.copyOf(cargo);
    }

    private static void change(Map<String, Integer> cargo, String item, int delta) {
        cargo.put(item, (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) cargo.getOrDefault(item, 0) + delta)));
    }
}
