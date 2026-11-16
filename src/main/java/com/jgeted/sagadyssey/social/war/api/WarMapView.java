package com.jgeted.sagadyssey.social.war.api;

import java.util.*;

/** Bounded military projection. Only the server supplies authorized units and previously observed own reports. */
public record WarMapView(UUID session, long revision, long observedAt, long expiresAt, WarMapSheet sheet,
                         int page, int totalUnits, List<Unit> units, Optional<Preview> preview, Optional<Receipt> receipt) {
    public static final int PAGE_SIZE = 6;
    public enum Mode {
        MOVE("行军"), HOLD("坚守"), RETREAT("撤退"), RALLY("集结"), CHARGE("突击");
        private final String label;
        Mode(String label) { this.label = label; }
        public String label() { return label; }
    }
    public enum Delivery { DIRECT, COURIER }
    public enum Outcome { ACCEPTED, REJECTED, REVIEW }
    public record Observation(WarIntent.Position position, long observedAt, WarFieldReport.State state) {
        public Observation { Objects.requireNonNull(position); Objects.requireNonNull(state); if (observedAt < 0) throw new IllegalArgumentException("invalid observation"); }
    }
    public record Unit(UUID id, UUID army, WarKey loadout, long revision, int members, int reports,
                       int organization, int pending, Optional<Observation> observation, String name) {
        public Unit(UUID id, UUID army, WarKey loadout, long revision, int members, int reports,
                    int organization, int pending, Optional<Observation> observation) {
            this(id, army, loadout, revision, members, reports, organization, pending, observation, "");
        }
        public Unit {
            Objects.requireNonNull(id); Objects.requireNonNull(army); Objects.requireNonNull(loadout); Objects.requireNonNull(observation);
            if (Objects.requireNonNull(name).length() > 60 || revision < 1 || members < 0 || members > 128 || reports < 0 || reports > members
                    || organization < -1 || organization > 10000 || pending < 0 || pending > 1024) throw new IllegalArgumentException("invalid map unit");
        }
    }
    public record Preview(UUID ticket, UUID unit, Mode mode, WarIntent.Position target, long expiresAt, Delivery delivery, long delay) {
        public Preview { Objects.requireNonNull(ticket); Objects.requireNonNull(unit); Objects.requireNonNull(mode); Objects.requireNonNull(target); Objects.requireNonNull(delivery); if (expiresAt < 0 || delay < 0) throw new IllegalArgumentException("invalid preview"); }
    }
    public record Receipt(Outcome outcome, String reason, Optional<UUID> order, long dueAt) {
        public Receipt { Objects.requireNonNull(outcome); Objects.requireNonNull(reason); Objects.requireNonNull(order); if (reason.length() > 256 || dueAt < -1 || outcome == Outcome.ACCEPTED && (order.isEmpty() || dueAt < 0)) throw new IllegalArgumentException("invalid map receipt"); }
        public static Receipt rejected(String reason) { return new Receipt(Outcome.REJECTED, reason, Optional.empty(), -1); }
    }
    public WarMapView {
        Objects.requireNonNull(session); Objects.requireNonNull(sheet); units = List.copyOf(units); Objects.requireNonNull(preview); Objects.requireNonNull(receipt);
        if (revision < 1 || observedAt < 0 || expiresAt < observedAt || page < 0 || totalUnits < 0 || totalUnits > 1024
                || page > Math.max(0, (totalUnits - 1) / PAGE_SIZE) || units.size() > PAGE_SIZE
                || units.size() != Math.min(PAGE_SIZE, totalUnits-page*PAGE_SIZE)
                || units.stream().map(Unit::id).distinct().count() != units.size()
                || units.stream().anyMatch(u -> u.observation().filter(o -> o.observedAt() > observedAt || !o.position().dimension().equals(sheet.dimension())).isPresent())
                || preview.filter(p -> !p.target().dimension().equals(sheet.dimension()) || p.expiresAt()>expiresAt).isPresent()) throw new IllegalArgumentException("invalid bounded map view");
    }
}
