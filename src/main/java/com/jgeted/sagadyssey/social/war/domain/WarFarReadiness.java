package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.WarIntent;

import java.util.*;

/** An unloaded unit's preparation duty. Stations are last known real positions. */
public record WarFarReadiness(UUID unit, UUID army, UUID principal, UUID order, UUID operation, List<Post> posts,
                            int duration, long approvedAt, long departedAt, Phase phase,
                            int fitCount, long revision, long changedAt, String reason) {
    public static final int MAX_MEMBERS = 16;
    public static final int MIN_DURATION = 20;
    public static final int MAX_DURATION = 2400;
    public static final long UNLOAD_WAIT_LIMIT = 6000;

    public enum Phase { ARMED, OFFLINE, PREPARED, RETURNED, CANCELLED }

    public record Post(WarTransmission.Member member, WarIntent.Position station) {
        public Post {
            Objects.requireNonNull(member);
            Objects.requireNonNull(station);
            if (Math.abs((long) station.x()) > 30_000_000 || Math.abs((long) station.z()) > 30_000_000
                    || station.y() < -2048 || station.y() > 2047) throw new IllegalArgumentException("invalid original station");
        }
    }

    public WarFarReadiness {
        Objects.requireNonNull(unit);
        Objects.requireNonNull(army);
        Objects.requireNonNull(principal);
        Objects.requireNonNull(order);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(phase);
        posts = List.copyOf(posts);
        if (posts.isEmpty() || posts.size() > MAX_MEMBERS || duration < MIN_DURATION || duration > MAX_DURATION
                || approvedAt < 0 || changedAt < approvedAt || revision < 1 || departedAt < -1 || departedAt > changedAt
                || departedAt >= 0 && departedAt < approvedAt || fitCount < 0 || fitCount > posts.size()
                || reason == null || reason.isBlank() || reason.length() > 240
                || posts.stream().map(p -> p.station().dimension()).distinct().count() != 1
                || posts.stream().map(p -> p.member().body()).distinct().count() != posts.size()
                || posts.stream().map(p -> p.member().character()).distinct().count() != posts.size()
                || posts.stream().map(p -> p.member().contract()).distinct().count() != posts.size()
                || phase == Phase.ARMED && departedAt != -1
                || (phase == Phase.ARMED || phase == Phase.OFFLINE) && fitCount != 0
                || (phase == Phase.OFFLINE || phase == Phase.PREPARED || phase == Phase.RETURNED) && departedAt < 0
                || (phase == Phase.PREPARED || fitCount > 0) && (departedAt < 0 || changedAt - departedAt < duration)) {
            throw new IllegalArgumentException("invalid readiness checkpoint");
        }
    }

    public static WarFarReadiness approve(UUID unit, UUID army, UUID principal, UUID order, UUID operation,
                                          List<Post> posts, int duration, long now, long revision) {
        return new WarFarReadiness(unit, army, principal, order, operation, posts, duration, now, -1,
                Phase.ARMED, 0, revision, now, "等待原部队所在区块实际卸载");
    }

    public boolean terminal() { return phase == Phase.RETURNED || phase == Phase.CANCELLED; }

    public WarFarReadiness begin(long now) {
        if (phase != Phase.ARMED) throw new IllegalArgumentException("unload can only start an armed plan");
        return change(Phase.OFFLINE, now, now, 0, "该批原身体和原据点均已卸载");
    }

    public WarFarReadiness prepared(long now, int available) {
        if (phase != Phase.OFFLINE || now - departedAt < duration) throw new IllegalArgumentException("preparation is not due");
        return change(Phase.PREPARED, now, departedAt, available, "远场整备已结算可用人数，等待原身体回载");
    }

    public WarFarReadiness returned(long now) {
        if (phase != Phase.OFFLINE && phase != Phase.PREPARED) throw new IllegalArgumentException("not offline");
        return change(Phase.RETURNED, now, departedAt, fitCount, "原身体已经回载，由现役主劳动复核当前军令");
    }

    public WarFarReadiness cancel(long now, String reason) {
        if (terminal()) throw new IllegalArgumentException("readiness already ended");
        return change(Phase.CANCELLED, now, departedAt, fitCount, reason);
    }

    private WarFarReadiness change(Phase next, long now, long departure, int fit, String why) {
        if (revision == Long.MAX_VALUE || now < changedAt) throw new IllegalArgumentException("readiness revision or clock");
        return new WarFarReadiness(unit, army, principal, order, operation, posts, duration, approvedAt, departure,
                next, fit, revision + 1, now, why);
    }

    public static boolean follows(WarFarReadiness old, WarFarReadiness next) {
        if (old.equals(next)) return true;
        if (!old.unit.equals(next.unit) || !old.army.equals(next.army) || next.changedAt < old.changedAt
                || old.revision == Long.MAX_VALUE || next.revision != old.revision + 1) return false;
        if (old.terminal()) return next.phase == Phase.ARMED && !old.operation.equals(next.operation) && next.approvedAt == next.changedAt;
        try {
            return switch (next.phase) {
                case ARMED -> false;
                case OFFLINE -> old.begin(next.changedAt).equals(next);
                case PREPARED -> old.prepared(next.changedAt, next.fitCount).equals(next);
                case RETURNED -> old.returned(next.changedAt).equals(next);
                case CANCELLED -> old.cancel(next.changedAt, next.reason).equals(next);
            };
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
