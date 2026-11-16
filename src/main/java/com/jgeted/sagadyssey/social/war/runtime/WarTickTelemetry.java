package com.jgeted.sagadyssey.social.war.runtime;

/** Optional main-thread timing for the field executor and the server's war coordinator. */
public final class WarTickTelemetry {
    public record Sample(long tick, long fieldNanos, long coordinatorNanos, int bodies) {}

    private boolean enabled;
    private long tick = -1, fieldNanos, coordinatorNanos;
    private int bodies;

    public void enabled(boolean value) {
        enabled = value;
        tick = -1;
        fieldNanos = coordinatorNanos = 0;
        bodies = 0;
    }

    public long start() {
        return enabled ? System.nanoTime() : 0;
    }

    public void field(long now, long start) {
        if (start == 0) return;
        begin(now);
        fieldNanos += Math.max(0, System.nanoTime() - start);
        bodies++;
    }

    public void coordinator(long now, long start) {
        if (start == 0) return;
        begin(now);
        coordinatorNanos += Math.max(0, System.nanoTime() - start);
    }

    public Sample sample(long now) {
        return tick == now ? new Sample(tick, fieldNanos, coordinatorNanos, bodies) : new Sample(now, 0, 0, 0);
    }

    private void begin(long now) {
        if (now == tick) return;
        tick = now;
        fieldNanos = coordinatorNanos = 0;
        bodies = 0;
    }
}
