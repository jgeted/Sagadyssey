package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.api.*;
import java.util.*;
import java.util.function.Function;

/** Transient map sessions and one-use previews. No world effects, persistence, or implicit army authority. */
public final class WarMapService {
    private static final long SESSION_LIFETIME_TICKS = 1_200;
    private static final long PREVIEW_LIFETIME_TICKS = 600;
    public record Lease(UUID id, UUID account, UUID character, WarMapSheet sheet, long revision, long expiresAt) {}
    private record Prepared(WarMapView.Preview view, WarCommand.IssueOrder command) {}
    private record Result(UUID ticket, WarMapView.Receipt receipt) {}
    private final Map<UUID, Lease> leases = new HashMap<>();
    private final Map<UUID, Prepared> previews = new HashMap<>();
    private final Map<UUID, Result> results = new HashMap<>();
    private final Map<UUID, WarMapView.Receipt> feedback = new HashMap<>();
    public Lease open(UUID account, UUID character, WarMapSheet sheet, long now) {
        Objects.requireNonNull(account); Objects.requireNonNull(character); Objects.requireNonNull(sheet); time(now);
        for (var old : List.copyOf(leases.values())) if (now > old.expiresAt() || old.account().equals(account)) close(old);
        if (leases.size() >= 256) throw new IllegalStateException("map session capacity");
        var lease = new Lease(UUID.randomUUID(), account, character, sheet, 1, Math.addExact(now, SESSION_LIFETIME_TICKS)); leases.put(lease.id(), lease); return lease;
    }
    public Optional<Lease> find(UUID account, UUID character, UUID id, long now) {
        var lease = leases.get(id);
        return lease != null && lease.account().equals(account) && lease.character().equals(character) && now >= lease.expiresAt()-SESSION_LIFETIME_TICKS && now <= lease.expiresAt() ? Optional.of(lease) : Optional.empty();
    }
    public Lease refresh(Lease lease, long now) {
        require(lease, now); previews.remove(lease.id());
        var next = new Lease(lease.id(), lease.account(), lease.character(), lease.sheet(), Math.addExact(lease.revision(), 1), Math.addExact(now, SESSION_LIFETIME_TICKS)); leases.put(next.id(), next); return next;
    }
    public WarMapView.Preview prepare(Lease lease, WarCommand.IssueOrder command, WarMapView.Mode mode, WarMapView.Delivery channel, long delay, long now) {
        require(lease, now);
        if (!command.actorId().equals(lease.character()) || !command.intent().target().dimension().equals(lease.sheet().dimension())) throw new IllegalArgumentException("foreign map command");
        var view = new WarMapView.Preview(UUID.randomUUID(), command.unitId(), mode, command.intent().target(), Math.min(lease.expiresAt(), Math.addExact(now, PREVIEW_LIFETIME_TICKS)), channel, delay);
        previews.put(lease.id(), new Prepared(view, command)); return view;
    }
    public Optional<WarMapView.Preview> preview(Lease lease, long now) { require(lease, now); return Optional.ofNullable(previews.get(lease.id())).map(Prepared::view).filter(v -> now <= v.expiresAt()); }
    public Optional<WarMapView.Receipt> receipt(Lease lease) { return Optional.ofNullable(feedback.get(lease.id())); }
    public Optional<WarMapView.Receipt> receipt(Lease lease, UUID ticket) { return Optional.ofNullable(results.get(lease.id())).filter(r -> r.ticket().equals(ticket)).map(Result::receipt); }
    public WarMapView.Receipt issue(Lease lease, UUID ticket, long now, Function<WarCommand.IssueOrder, WarMapView.Receipt> submit) {
        require(lease, now);
        var prior = results.get(lease.id()); if (prior != null && prior.ticket().equals(ticket)) { feedback.put(lease.id(), prior.receipt()); return prior.receipt(); }
        var prepared = previews.get(lease.id());
        if (prepared == null || !prepared.view().ticket().equals(ticket) || now > prepared.view().expiresAt()) { var denied=WarMapView.Receipt.rejected("PREVIEW_EXPIRED"); feedback.put(lease.id(),denied); return denied; }
        previews.remove(lease.id());
        var unknown = new WarMapView.Receipt(WarMapView.Outcome.REVIEW, "SUBMISSION_REQUIRES_REVIEW", Optional.of(prepared.command().operationId()), -1);
        results.put(lease.id(), new Result(ticket, unknown)); feedback.put(lease.id(),unknown);
        try { var result = Objects.requireNonNull(submit.apply(prepared.command())); if(result.outcome()==WarMapView.Outcome.ACCEPTED&&!result.order().filter(prepared.command().operationId()::equals).isPresent())return unknown; results.put(lease.id(), new Result(ticket, result)); feedback.put(lease.id(),result); return result; }
        catch (RuntimeException e) { return unknown; }
    }
    public void close(Lease lease) { if (Objects.equals(leases.get(lease.id()), lease)) { leases.remove(lease.id()); previews.remove(lease.id()); results.remove(lease.id()); feedback.remove(lease.id()); } }
    public void logout(UUID account) { for (var lease : List.copyOf(leases.values())) if (lease.account().equals(account)) close(lease); }
    private void require(Lease lease, long now) { if (!Objects.equals(leases.get(lease.id()), lease) || now < lease.expiresAt()-SESSION_LIFETIME_TICKS || now > lease.expiresAt()) throw new IllegalArgumentException("stale map lease"); }
    private static void time(long now) { if (now < 0) throw new IllegalArgumentException("invalid map time"); }
}
