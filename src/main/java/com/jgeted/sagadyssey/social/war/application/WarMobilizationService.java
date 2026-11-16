package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.*;

import java.util.*;

/** 单步推进的服役用例：先落盘意图，再查权威结果，只有明确无效果才允许提交原操作。 */
public final class WarMobilizationService {
    public static final int RECORD_LIMIT = 4_096;
    public enum Outcome { ACCEPTED, REPLAYED, PROGRESSED, WAITING, REVIEW, ACTIVE, RELEASED, BLOCKED, CONFLICT, BUSY, FULL, NOT_FOUND }
    public record Preview(Set<WarKey> missing, List<String> reasons) {
        public Preview { missing = Set.copyOf(missing); reasons = List.copyOf(reasons); }
        public boolean ready() { return missing.isEmpty() && reasons.isEmpty(); }
    }
    public record Result(Outcome outcome, Optional<WarMobilization> record, String reason) {
        public Result { Objects.requireNonNull(outcome); Objects.requireNonNull(record); Objects.requireNonNull(reason); }
    }
    private final Thread owner = Thread.currentThread();
    private final Bindings bindings;
    private final WarDefinitions definitions;
    private final WarLoadoutCatalog loadouts;
    private final int capacity;
    private final Map<UUID, WarMobilization> records = new LinkedHashMap<>();
    private final Map<UUID, UUID> heldCharacters = new HashMap<>();
    private final Map<UUID, Long> lastAdvanced = new HashMap<>();
    private List<String> recoveryProblems = List.of();
    private long observedTick;

    public WarMobilizationService(WarDefinitions definitions, WarLoadoutCatalog loadouts, Bindings bindings, int capacity) {
        this.definitions = Objects.requireNonNull(definitions); this.loadouts = Objects.requireNonNull(loadouts);
        this.bindings = Objects.requireNonNull(bindings);
        if (capacity < 1 || capacity > RECORD_LIMIT) throw new IllegalArgumentException("invalid mobilization capacity");
        this.capacity = capacity;
        boolean supportedJournal = bindings.journal().isPresent() && bindings.journal().get().protocolVersion() == 1;
        if (supportedJournal && capacity > bindings.journal().get().capacity()) throw new IllegalArgumentException("service capacity exceeds durable journal capacity");
        if (supportedJournal) for (WarMobilization record : bindings.journal().get().recover()) {
            if (records.size() >= capacity || records.putIfAbsent(record.request().id(), record) != null) {
                throw new IllegalArgumentException("duplicate or excessive mobilization records; preserve journal");
            }
            observedTick = Math.max(observedTick, record.changedAtTick()); index(record, true);
        }
        reconcile();
    }
    public void reconcile() {
        checkThread();
        if (!bindings.missing().isEmpty()) return;
        try { recoveryProblems = bindings.inspection().orElseThrow().reconcile(List.copyOf(records.values())).reasons(); }
        catch (RuntimeException exception) { recoveryProblems = List.of("RECOVERY_QUERY_FAILED:" + exception.getClass().getSimpleName()); }
    }
    public Optional<WarMobilization> find(UUID id) { checkThread(); return Optional.ofNullable(records.get(id)); }
    public boolean hasRoomFor(UUID id) { checkThread(); return records.containsKey(id) || records.size() < capacity; }
    public Collection<WarMobilization> records() { checkThread(); return List.copyOf(records.values()); }
    public boolean recoveryReady() { checkThread(); return bindings.missing().isEmpty() && recoveryProblems.isEmpty(); }
    /** Checkpoint only; does not perform or retry an external effect. */
    public boolean checkpointed(UUID id){
        checkThread();var value=records.get(id);if(value==null||!recoveryReady())return false;
        var ack=bindings.journal().orElseThrow().persist(value);boolean confirmed=ack.status()==Status.APPLIED&&ack.durable().filter(value::equals).isPresent();
        if(confirmed)index(value,true);return confirmed;
    }
    public Preview preview(WarMilitiaRequest request, long now) {
        checkThread(); Objects.requireNonNull(request);
        var missing = bindings.missing(); List<String> reasons = new ArrayList<>(recoveryProblems);
        if (now < observedTick || now < request.requestedAtTick()) reasons.add("TIME_REVERSED");
        if (!definitions.supports(request.loadout(), WarDefinitions.Kind.TROOP, request.definitionVersion())
                || loadouts.find(request.loadout()).isEmpty()) reasons.add("UNSUPPORTED_LOADOUT");
        if (heldCharacters.containsKey(request.characterId()) && !heldCharacters.get(request.characterId()).equals(request.id())) {
            reasons.add("CHARACTER_ALREADY_MOBILIZING");
        }
        if (missing.isEmpty() && reasons.isEmpty()) {
            try { reasons.addAll(bindings.inspection().orElseThrow().inspect(request, now).reasons()); }
            catch (RuntimeException exception) { reasons.add("INSPECTION_UNAVAILABLE:" + exception.getClass().getSimpleName()); }
        }
        return new Preview(missing, reasons);
    }
    /** 调用方必须绑定可信服务端身份；不向客户端直接暴露此内部用例。 */
    public Result begin(WarMilitiaRequest request, long now) {
        checkThread(); Objects.requireNonNull(request);
        var existing = records.get(request.id());
        if (existing != null) return result(existing.request().equals(request) ? Outcome.REPLAYED : Outcome.CONFLICT, existing, "");
        Preview preview = preview(request, now);
        if (!preview.ready()) return result(preview.reasons().contains("CHARACTER_ALREADY_MOBILIZING") ? Outcome.BUSY : Outcome.BLOCKED,
                null, !preview.missing().isEmpty() ? "MISSING_CAPABILITIES:" + preview.missing() : String.join(";", preview.reasons()));
        if (records.size() >= capacity) return result(Outcome.FULL, null, "mobilization journal capacity reached; no records evicted");
        var record = WarMobilization.start(request); records.put(request.id(), record); index(record, false); observedTick = now;
        return result(Outcome.ACCEPTED, record, "intent accepted in memory; effects require durable acknowledgement");
    }
    public Result demobilize(UUID id, long now) {
        checkThread(); var record = records.get(id);
        if (record == null) return result(Outcome.NOT_FOUND, null, "");
        if (now < observedTick) return result(Outcome.BLOCKED, record, "TIME_REVERSED");
        if (record.demobilizeRequested()) return result(Outcome.REPLAYED, record, "");
        return publish(record.demobilize(now), Outcome.PROGRESSED, "demobilization intent requires persistence");
    }

    public Result advance(UUID id, long now) {
        checkThread(); WarMobilization record = records.get(id);
        if (record == null) return result(Outcome.NOT_FOUND, null, "");
        if (now < observedTick) return result(Outcome.BLOCKED, record, "TIME_REVERSED");
        observedTick = now;
        if (!bindings.missing().isEmpty() || !recoveryProblems.isEmpty()) return result(Outcome.BLOCKED, record, "INTEGRATION_OR_RECOVERY_UNAVAILABLE");
        if (lastAdvanced.getOrDefault(id, -1L) == now) return result(Outcome.WAITING, record, "one bounded advance per game tick");
        lastAdvanced.put(id, now);
        try {
            var saved = bindings.journal().orElseThrow().persist(record);
            if (saved.status() != Status.APPLIED) return result(saved.status() == Status.PENDING ? Outcome.WAITING : Outcome.REVIEW, record, saved.reason());
            if (!saved.durable().orElseThrow().equals(record)) return review(record, "JOURNAL_ACKNOWLEDGED_DIFFERENT_CONTENT", now);
            index(record, true);
        } catch (RuntimeException exception) { return review(record, "JOURNAL_FAILED:" + exception.getClass().getSimpleName(), now); }
        if (!record.stage().effect()) return result(record.stage() == WarMobilization.Stage.ACTIVE ? Outcome.ACTIVE : Outcome.RELEASED, record, "");
        var effect = new Effect(record.request(), record.stage(), WarMobilization.operationId(id, record.stage()),
                WarMobilization.cancellationId(id, record.stage()), record.receipts(), now);
        EffectPort port = bindings.effects().get(record.stage());
        try {
            Reply query = Objects.requireNonNull(port.lookup(effect));
            if (query.status() == Status.APPLIED) return confirm(record, query, now);
            if (query.status() == Status.UNKNOWN) return review(record, "AUTHORITATIVE_OUTCOME_UNKNOWN:" + query.reason(), now);
            if (query.status() == Status.UNAVAILABLE) return result(Outcome.WAITING, record, query.reason());
            boolean cancelable = record.stage() == WarMobilization.Stage.CLAIM || record.stage() == WarMobilization.Stage.SUSTAIN || record.stage() == WarMobilization.Stage.EQUIP
                    || record.stage() == WarMobilization.Stage.ACTIVATE;
            if (record.demobilizeRequested() && cancelable) {
                if (query.status() == Status.NOT_APPLIED) return publish(record.cancelUnapplied(now), Outcome.PROGRESSED, "proved unapplied; continue cleanup");
                return handle(record, Objects.requireNonNull(port.cancel(effect)), now, true);
            }
            if (query.status() == Status.PENDING) return result(Outcome.WAITING, record, query.reason());
            if (record.stage() == WarMobilization.Stage.CLAIM) {
                Readiness rechecked = bindings.inspection().orElseThrow().inspect(record.request(), now);
                if (!rechecked.eligible()) return review(record, String.join(";", rechecked.reasons()), now);
            }
            return handle(record, Objects.requireNonNull(port.apply(effect)), now, false);
        } catch (RuntimeException exception) {
            // Includes exceptions after an external effect. Never infer no effect or mint another operation ID.
            return review(record, "EFFECT_RESULT_UNCERTAIN:" + exception.getClass().getSimpleName(), now);
        }
    }
    private Result handle(WarMobilization record, Reply reply, long now, boolean cancelling) {
        return switch (reply.status()) {
            case APPLIED -> confirm(record, reply, now);
            case UNKNOWN -> review(record, "AUTHORITATIVE_OUTCOME_UNKNOWN:" + reply.reason(), now);
            case NOT_APPLIED -> cancelling ? publish(record.cancelUnapplied(now), Outcome.PROGRESSED, "cancelled with no remaining effect")
                    : result(Outcome.WAITING, record, reply.reason());
            case PENDING, UNAVAILABLE -> result(Outcome.WAITING, record, reply.reason());
        };
    }
    private Result confirm(WarMobilization record, Reply reply, long now) {
        try { return publish(record.confirmed(reply.receipt().orElseThrow(), now), Outcome.PROGRESSED, "receipt accepted; next effect awaits persistence"); }
        catch (IllegalArgumentException exception) { return review(record, "RECEIPT_IDENTITY_OR_SEQUENCE_MISMATCH", now); }
    }
    private Result review(WarMobilization record, String reason, long now) {
        String bounded = reason.length() > 512 ? reason.substring(0, 512) : reason;
        return publish(record.review(bounded, now), Outcome.REVIEW, bounded);
    }
    private Result publish(WarMobilization record, Outcome outcome, String reason) {
        records.put(record.request().id(), record); observedTick = Math.max(observedTick, record.changedAtTick()); index(record, false);
        return result(outcome, record, reason);
    }
    private void index(WarMobilization record, boolean durable) {
        if (record.holdsCharacter()) {
            UUID existing = heldCharacters.putIfAbsent(record.request().characterId(), record.request().id());
            if (existing != null && !existing.equals(record.request().id())) throw new IllegalArgumentException("duplicate character mobilization");
        } else if (durable) heldCharacters.remove(record.request().characterId(), record.request().id());
    }
    private static Result result(Outcome outcome, WarMobilization record, String reason) { return new Result(outcome, Optional.ofNullable(record), reason); }
    private void checkThread() { if (Thread.currentThread() != owner) throw new IllegalStateException("mobilization owner thread required"); }
}
