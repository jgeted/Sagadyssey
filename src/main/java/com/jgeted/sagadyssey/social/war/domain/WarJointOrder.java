package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.WarCommand;
import com.jgeted.sagadyssey.social.war.api.WarMapSheet;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** A joint operation records each original commander's instructions and consent separately. */
public record WarJointOrder(UUID id, UUID organizer, UUID partner, long executeAt, long reportsUntil,
                            List<Instruction> instructions, Set<UUID> signatures, Phase phase,
                            List<Receipt> receipts, long revision) {
    public static final int MAX_UNITS = 16;
    public enum Phase { DRAFT, SCHEDULED, ISSUING, SUBMITTED, STOPPED, REVIEW, CLOSED }
    public enum Outcome { ACCEPTED, REJECTED, UNKNOWN }

    public record Instruction(WarCommand.IssueOrder command, WarMapSheet map, int x, int z, boolean shareReport) {
        public Instruction {
            Objects.requireNonNull(command);
            Objects.requireNonNull(map);
            if (x < 0 || x > 127 || z < 0 || z > 127 || !command.intent().target().dimension().equals(map.dimension())
                    || command.intent().target().x() != map.x(x) || command.intent().target().z() != map.z(z))
                throw new IllegalArgumentException("invalid mapped joint instruction");
        }
    }

    public record Receipt(UUID order, Outcome outcome, long dueAt, String reason) {
        public Receipt {
            Objects.requireNonNull(order);
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(reason);
            if ((outcome == Outcome.ACCEPTED ? dueAt < 0 : dueAt != -1) || reason.length() > 256)
                throw new IllegalArgumentException("invalid joint order receipt");
        }
    }

    public WarJointOrder {
        Objects.requireNonNull(id);
        Objects.requireNonNull(organizer);
        Objects.requireNonNull(partner);
        Objects.requireNonNull(phase);
        instructions = List.copyOf(instructions);
        signatures = Set.copyOf(signatures);
        receipts = List.copyOf(receipts);
        var participants = participants(organizer, partner);
        if (executeAt < 1 || reportsUntil <= executeAt || reportsUntil - executeAt > 24000 || revision < 1
                || instructions.size() > MAX_UNITS || !participants.containsAll(signatures))
            throw new IllegalArgumentException("invalid joint operation");
        var units = new HashSet<UUID>();
        var orders = new HashSet<UUID>();
        for (var leg : instructions) {
            var command = leg.command();
            if (!participants.contains(command.actorId()) || !units.add(command.unitId())
                    || !command.operationId().equals(operationId(id, command.unitId())) || !orders.add(command.operationId()))
                throw new IllegalArgumentException("foreign or repeated joint instruction");
        }
        if (!signatures.isEmpty() && !instructions.stream().map(leg -> leg.command().actorId()).collect(java.util.stream.Collectors.toSet()).containsAll(participants))
            throw new IllegalArgumentException("joint plan is missing a party's instruction");
        if ((phase == Phase.SCHEDULED || phase == Phase.ISSUING || phase == Phase.SUBMITTED || phase == Phase.REVIEW)
                && !signatures.equals(participants)) throw new IllegalArgumentException("missing joint signature");
        if (phase == Phase.SUBMITTED || phase == Phase.REVIEW || phase == Phase.CLOSED && !receipts.isEmpty()) {
            if (receipts.size() != instructions.size() || !receipts.stream().map(Receipt::order).collect(java.util.stream.Collectors.toSet()).equals(orders))
                throw new IllegalArgumentException("incomplete joint receipts");
        } else if (!receipts.isEmpty()) throw new IllegalArgumentException("unexpected joint receipts");
    }

    public static WarJointOrder draft(UUID id, UUID organizer, UUID partner, long executeAt, long reportsUntil) {
        return new WarJointOrder(id, organizer, partner, executeAt, reportsUntil, List.of(), Set.of(), Phase.DRAFT, List.of(), 1);
    }

    public boolean involves(UUID actor) { return organizer.equals(actor) || partner.equals(actor); }
    public boolean waiting() { return phase == Phase.SCHEDULED || phase == Phase.ISSUING; }
    public boolean sharesReports(long now) { return phase == Phase.SUBMITTED && now < reportsUntil; }

    public WarJointOrder add(Instruction instruction) {
        if (phase != Phase.DRAFT || !signatures.isEmpty()) throw new IllegalStateException("joint plan is already signed");
        var next = new ArrayList<>(instructions);
        next.add(instruction);
        return change(next, signatures, phase, receipts);
    }

    public WarJointOrder sign(UUID signer) {
        if (phase != Phase.DRAFT || !involves(signer)) throw new IllegalStateException("joint signature is unavailable");
        if (signatures.contains(signer)) return this;
        var next = new HashSet<>(signatures);
        next.add(signer);
        return change(instructions, next, next.equals(participants(organizer, partner)) ? Phase.SCHEDULED : Phase.DRAFT, receipts);
    }

    public WarJointOrder fence() {
        if (phase != Phase.SCHEDULED) throw new IllegalStateException("joint plan is not scheduled");
        return change(instructions, signatures, Phase.ISSUING, receipts);
    }

    public WarJointOrder finish(List<Receipt> results) {
        if (phase != Phase.ISSUING) throw new IllegalStateException("joint plan is not issuing");
        boolean uncertain = results.stream().anyMatch(row -> row.outcome() == Outcome.UNKNOWN);
        return change(instructions, signatures, uncertain ? Phase.REVIEW : Phase.SUBMITTED, results);
    }

    public WarJointOrder close() {
        return switch (phase) {
            case DRAFT, SCHEDULED, ISSUING -> change(instructions, signatures, Phase.STOPPED, List.of());
            case SUBMITTED, REVIEW -> change(instructions, signatures, Phase.CLOSED, receipts);
            default -> this;
        };
    }

    public static boolean follows(WarJointOrder old, WarJointOrder next) {
        if (old.equals(next)) return true;
        if (!old.id.equals(next.id) || !old.organizer.equals(next.organizer) || !old.partner.equals(next.partner)
                || old.executeAt != next.executeAt || old.reportsUntil != next.reportsUntil || next.revision != old.revision + 1)
            return false;
        try {
            if (old.phase == Phase.DRAFT && next.instructions.size() == old.instructions.size() + 1)
                return old.add(next.instructions.getLast()).equals(next);
            if (old.phase == Phase.DRAFT && next.signatures.size() == old.signatures.size() + 1)
                return old.sign(next.signatures.stream().filter(signer -> !old.signatures.contains(signer)).findFirst().orElseThrow()).equals(next);
            if (next.phase == Phase.ISSUING) return old.fence().equals(next);
            if (next.phase == Phase.SUBMITTED || next.phase == Phase.REVIEW) return old.finish(next.receipts).equals(next);
            return old.close().equals(next);
        } catch (IllegalArgumentException | IllegalStateException invalid) { return false; }
    }

    public static UUID operationId(UUID plan, UUID unit) {
        return UUID.nameUUIDFromBytes(("sagadyssey:war/joint/" + plan + "/" + unit).getBytes(StandardCharsets.UTF_8));
    }

    private static Set<UUID> participants(UUID organizer, UUID partner) {
        return organizer.equals(partner) ? Set.of(organizer) : Set.of(organizer, partner);
    }

    private WarJointOrder change(List<Instruction> legs, Set<UUID> signers, Phase next, List<Receipt> results) {
        return new WarJointOrder(id, organizer, partner, executeAt, reportsUntil, legs, signers, next, results, Math.addExact(revision, 1));
    }
}
