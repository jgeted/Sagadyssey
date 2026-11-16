package com.jgeted.sagadyssey.social.war.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One confirmed field result, credited to the original person and exact appointment once per order. */
public record WarLeadershipRecord(UUID order, UUID army, UUID unit, UUID appointment, UUID officer,
                                  Specialty specialty, long receivedAt, long observedAt,
                                  int strength, int arrived, Optional<UUID> contact) {
    public static final int MAX_POINTS = 8;

    public enum Specialty {
        MANEUVER("机动"), ASSAULT("突击"), HOLD("坚守"), WITHDRAW("撤收");

        private final String title;

        Specialty(String title) { this.title = title; }

        public String title() { return title; }
    }

    public WarLeadershipRecord {
        Objects.requireNonNull(order);
        Objects.requireNonNull(army);
        Objects.requireNonNull(unit);
        Objects.requireNonNull(appointment);
        Objects.requireNonNull(officer);
        Objects.requireNonNull(specialty);
        Objects.requireNonNull(contact);
        if (receivedAt < 0 || observedAt < receivedAt || strength < 3 || strength > WarTransmission.MAX_MEMBERS
                || arrived < 0 || arrived > strength || !qualifies(specialty, observedAt - receivedAt, strength, arrived, contact.isPresent())) {
            throw new IllegalArgumentException("invalid leadership evidence");
        }
    }

    public static boolean qualifies(Specialty specialty, long elapsed, int strength, int arrived, boolean contacted) {
        if (strength < 3 || elapsed < 0) return false;
        return switch (specialty) {
            case MANEUVER -> elapsed >= 20 && arrived * 5 >= strength * 4;
            case ASSAULT -> contacted;
            case HOLD -> contacted && elapsed >= 80 && arrived * 5 >= strength * 3;
            case WITHDRAW -> contacted && arrived * 5 >= strength * 4;
        };
    }

    public static int pathRetryTicks(int points) { return 100 - 3 * bounded(points); }
    public static int formationLossDiscount(int points) { return 2 * bounded(points); }
    public static int rallyRecoveryBonus(int points) { return 15 * bounded(points); }

    private static int bounded(int points) { return Math.max(0, Math.min(MAX_POINTS, points)); }
}
