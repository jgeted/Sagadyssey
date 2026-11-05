package com.jgeted.sagadyssey.worldsim.lod;

import java.util.*;

/** Bounded FIFO admission. A busy first entity cannot starve the rest of the tick order. */
public final class FairTickBudget {
    private final int limit, capacity;
    private final long nanosLimit;
    private final LinkedHashMap<UUID, Long> waiting = new LinkedHashMap<>();
    private final Map<UUID, Long> seen = new HashMap<>();
    private final Set<UUID> admitted = new HashSet<>();
    private long tick = -1, spent, maxSpent, deferred, calls, oldestWait;
    private int peakAdmissions, peakBacklog;

    public FairTickBudget(int limit, long nanosLimit, int capacity) {
        if (limit < 1 || nanosLimit < 1 || capacity < limit) throw new IllegalArgumentException("invalid budget");
        this.limit = limit; this.nanosLimit = nanosLimit; this.capacity = capacity;
    }
    public boolean request(UUID body, long now) {
        Objects.requireNonNull(body);
        if (now < 0 || now < tick) throw new IllegalArgumentException("budget time cannot go backwards");
        if (now != tick) {
            maxSpent = Math.max(maxSpent, spent); peakAdmissions = Math.max(peakAdmissions, admitted.size());
            tick = now; spent = 0; admitted.clear();
            // A goal may cease requesting without an entity-unload event (e.g. combat).
            var stale = waiting.keySet().iterator();
            while (stale.hasNext()) {
                var id = stale.next();
                if (now - seen.getOrDefault(id, now) > 200) { stale.remove(); seen.remove(id); }
            }
        }
        if (admitted.contains(body)) return false;
        if (!waiting.containsKey(body)) {
            if (waiting.size() >= capacity) { deferred++; return false; }
            waiting.put(body, now); peakBacklog = Math.max(peakBacklog, waiting.size());
        }
        seen.put(body, now);
        if (admitted.size() >= limit || spent >= nanosLimit || !waiting.keySet().iterator().next().equals(body)) {
            deferred++; return false;
        }
        oldestWait = Math.max(oldestWait, now - waiting.remove(body));
        seen.remove(body);
        admitted.add(body); calls++; return true;
    }
    public void charge(long nanos) { spent = Math.addExact(spent, Math.max(0, nanos)); }
    public void forget(UUID body) { waiting.remove(body); seen.remove(body); }
    public Snapshot snapshot() {
        return new Snapshot(calls, deferred, waiting.size(), peakBacklog,
                Math.max(peakAdmissions, admitted.size()), Math.max(maxSpent, spent), oldestWait, limit, nanosLimit);
    }
    public record Snapshot(long calls, long deferred, int backlog, int peakBacklog,
                           int maxAdmissions, long maxTickNanos, long maxWaitTicks, int limit, long nanosLimit) {}
}
