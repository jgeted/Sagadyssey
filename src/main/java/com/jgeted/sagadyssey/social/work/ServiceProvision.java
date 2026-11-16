package com.jgeted.sagadyssey.social.work;

import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 共享服役粮饷协议；只保存约定与共享效果引用，不另造钱包、库存或饥饿值。 */
public record ServiceProvision(Terms terms, long revision, long changedAt, Map<Step, Mark> marks,
                               Optional<Attempt> pending, List<Settlement> settlements, long nextSequence) {
    public enum Step { OPEN_PAYROLL, SUSTAIN, END_SUSTAIN, CLOSE_PAYROLL }
    public enum Purpose { WAGE, MEAL }
    public record Terms(UUID id, String ownerKind, UUID owner, UUID person, UUID body, SubjectRef payer,
                        ItemLocation treasury, ItemFingerprint wageItem, long rate, long period) {
        public Terms {
            Objects.requireNonNull(id); Objects.requireNonNull(owner); Objects.requireNonNull(person); Objects.requireNonNull(body);
            Objects.requireNonNull(payer); Objects.requireNonNull(treasury); Objects.requireNonNull(wageItem);
            if (ownerKind == null || ownerKind.isBlank() || ownerKind.length() > 128 || rate < 1 || period < 1
                    || treasury.kind() != ItemLocation.Kind.BLOCK && treasury.kind() != ItemLocation.Kind.PLAYER
                    || treasury.kind() == ItemLocation.Kind.PLAYER && !payer.equals(SubjectRef.character(treasury.character()))
                    || wageItem.itemId().length() > 256) throw new IllegalArgumentException("invalid service terms");
        }
        public ItemLocation bag() { return ItemLocation.personal(person, body); }
    }
    public record Mark(UUID operation, long at, long acknowledgedAt, boolean outstanding) {
        public Mark { Objects.requireNonNull(operation); if (at < 0 || acknowledgedAt < at) throw new IllegalArgumentException("invalid service mark"); }
    }
    public record Attempt(Purpose purpose, long sequence, PhysicalItemOperation.Intent intent, int value) {
        public Attempt { Objects.requireNonNull(purpose); Objects.requireNonNull(intent); if (sequence < 0 || value < 1 || value > 1000) throw new IllegalArgumentException("invalid service attempt"); }
    }
    public record Settlement(Attempt attempt, boolean applied, long occurredAt) {
        public Settlement { Objects.requireNonNull(attempt); if (occurredAt < 0) throw new IllegalArgumentException("invalid settlement time"); }
    }
    public ServiceProvision {
        Objects.requireNonNull(terms); Objects.requireNonNull(pending); marks = Map.copyOf(marks); settlements = List.copyOf(settlements);
        if (revision < 1 || changedAt < 0 || nextSequence < 0 || settlements.size() > 1024 || nextSequence != settlements.size()) throw new IllegalArgumentException("invalid service counters");
        if (marks.containsKey(Step.SUSTAIN) && !marks.containsKey(Step.OPEN_PAYROLL) || marks.containsKey(Step.END_SUSTAIN) && !marks.containsKey(Step.SUSTAIN)
                || marks.containsKey(Step.CLOSE_PAYROLL) && (!marks.containsKey(Step.OPEN_PAYROLL) || marks.containsKey(Step.SUSTAIN) && !marks.containsKey(Step.END_SUSTAIN))) throw new IllegalArgumentException("service marks out of sequence");
        Set<UUID> operations = new HashSet<>();
        for (var entry : marks.entrySet()) { var mark = entry.getValue(); if (mark.acknowledgedAt() > changedAt || !operations.add(mark.operation())
                || mark.outstanding() && entry.getKey() != Step.CLOSE_PAYROLL) throw new IllegalArgumentException("duplicate/future service mark"); }
        for (int i = 0; i < settlements.size(); i++) { var s = settlements.get(i); validateAttempt(terms, s.attempt(), i);
            if (s.occurredAt() > changedAt) throw new IllegalArgumentException("future settlement"); }
        pending.ifPresent(value -> validateAttempt(terms, value, nextSequence));
        if (marks.containsKey(Step.CLOSE_PAYROLL) && marks.get(Step.CLOSE_PAYROLL).at() < marks.get(Step.OPEN_PAYROLL).at()) throw new IllegalArgumentException("payroll time reversed");
    }
    private static void validateAttempt(Terms terms, Attempt value, long sequence) {
        if (value.sequence() != sequence || !value.intent().id().equals(operation(terms.id(), value.purpose(), sequence))) throw new IllegalArgumentException("foreign service attempt");
        if (value.purpose() == Purpose.WAGE) {
            var transfer = value.intent().transfer().orElseThrow();
            if (transfer.intent() != TransferIntent.PAYMENT || !transfer.initiatedBy().equals(terms.payer()) || !transfer.sourceAuthorities().isEmpty()
                    || !transfer.legs().equals(List.of(new TransferLeg(terms.treasury().id(), terms.bag().id(), terms.wageItem(), value.value())))) throw new IllegalArgumentException("foreign payroll transfer");
        } else { var consume = value.intent().consumption().orElseThrow();
            if (!consume.container().equals(terms.bag().id()) || !consume.actor().equals(SubjectRef.character(terms.person())) || consume.quantity() != 1) throw new IllegalArgumentException("foreign ration consumption"); }
    }
    public static ServiceProvision configured(Terms terms, long now) { return new ServiceProvision(terms, 1, now, Map.of(), Optional.empty(), List.of(), 0); }
    public boolean ownsNeeds() { return marks.containsKey(Step.SUSTAIN) && !marks.containsKey(Step.END_SUSTAIN) || pending.filter(a -> a.purpose() == Purpose.MEAL).isPresent(); }
    public BigInteger due(long now) {
        var opened = marks.get(Step.OPEN_PAYROLL); if (opened == null) return BigInteger.ZERO;
        long end = marks.containsKey(Step.CLOSE_PAYROLL) ? marks.get(Step.CLOSE_PAYROLL).at() : now;
        if (end < opened.at()) throw new IllegalArgumentException("payroll time reversed");
        var numerator = BigInteger.valueOf(end - opened.at()).multiply(BigInteger.valueOf(terms.rate())); var period = BigInteger.valueOf(terms.period());
        return marks.containsKey(Step.CLOSE_PAYROLL) ? numerator.add(period).subtract(BigInteger.ONE).divide(period) : numerator.divide(period);
    }
    public BigInteger paid() { return settlements.stream().filter(s -> s.applied() && s.attempt().purpose() == Purpose.WAGE).map(s -> BigInteger.valueOf(s.attempt().value())).reduce(BigInteger.ZERO, BigInteger::add); }
    public BigInteger arrears(long now) { return due(now).subtract(paid()).max(BigInteger.ZERO); }
    public ServiceProvision mark(Step step, UUID operation, long at, long now) {
        var next = new EnumMap<Step, Mark>(Step.class); next.putAll(marks);
        boolean outstanding = false;
        if (step == Step.CLOSE_PAYROLL) {
            var start = marks.get(Step.OPEN_PAYROLL); if (start == null || at < start.at()) throw new IllegalArgumentException("payroll start required");
            var period = BigInteger.valueOf(terms.period()); var owed = BigInteger.valueOf(at - start.at()).multiply(BigInteger.valueOf(terms.rate())).add(period).subtract(BigInteger.ONE).divide(period);
            outstanding = owed.compareTo(paid()) > 0;
        }
        next.put(step, new Mark(operation, at, now, outstanding));
        return replace(next, pending, settlements, nextSequence, now);
    }
    public ServiceProvision attempt(Attempt attempt, long now) {
        if (pending.isPresent() || settlements.size() == 1024) throw new IllegalStateException("service history full or attempt pending");
        return replace(marks, Optional.of(attempt), settlements, nextSequence, now);
    }
    public ServiceProvision settled(boolean applied, long at, long now) {
        var history = new ArrayList<>(settlements); history.add(new Settlement(pending.orElseThrow(), applied, at));
        return replace(marks, Optional.empty(), history, nextSequence + 1, now);
    }
    private ServiceProvision replace(Map<Step, Mark> marks, Optional<Attempt> pending, List<Settlement> history, long sequence, long now) {
        if (now < changedAt || revision == Long.MAX_VALUE) throw new IllegalArgumentException("service time/revision reversed");
        return new ServiceProvision(terms, revision + 1, now, marks, pending, history, sequence);
    }
    public static UUID operation(UUID id, Purpose purpose, long sequence) { return UUID.nameUUIDFromBytes(("sagadyssey:service/" + id + "/" + purpose + "/" + sequence).getBytes(StandardCharsets.UTF_8)); }
    public static boolean follows(ServiceProvision old, ServiceProvision next) {
        if (old.equals(next)) return true;
        if (!old.terms.equals(next.terms) || next.revision != old.revision + 1 || next.changedAt < old.changedAt || !next.marks.entrySet().containsAll(old.marks.entrySet())
                || next.settlements.size() < old.settlements.size() || !next.settlements.subList(0, old.settlements.size()).equals(old.settlements)) return false;
        if (next.marks.size() == old.marks.size() + 1) return next.pending.equals(old.pending) && next.settlements.equals(old.settlements);
        if (!next.marks.equals(old.marks)) return false;
        return old.pending.isEmpty() && next.pending.isPresent() && next.settlements.equals(old.settlements)
                || old.pending.isPresent() && next.pending.isEmpty() && next.settlements.size() == old.settlements.size() + 1
                && next.settlements.getLast().attempt().equals(old.pending.get());
    }
}
