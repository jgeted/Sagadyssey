package com.jgeted.sagadyssey.social.war.spi;

import com.jgeted.sagadyssey.social.war.api.*;
import java.util.Objects;

/** Native locomotion and mount-reference boundary; the field controller does not drive horse physics. */
public final class WarMobilityPorts {
    private WarMobilityPorts() {}
    public enum Mode { FOOT, RIDING, FOOT_FALLBACK, BLOCKED, REVIEW }
    public record Observation(Mode mode, String reason) {
        public Observation { Objects.requireNonNull(mode); Objects.requireNonNull(reason); }
        public boolean usable() { return mode == Mode.FOOT || mode == Mode.RIDING || mode == Mode.FOOT_FALLBACK; }
    }
    public record Pose(WarKey dimension, double x, double y, double z) {
        public Pose { Objects.requireNonNull(dimension); if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("invalid actual pose"); }
        public WarIntent.Position block() { return new WarIntent.Position(dimension, (int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z)); }
        public double distanceSquared(WarIntent.Position p) {
            if (!dimension.equals(p.dimension())) return Double.POSITIVE_INFINITY;
            double dx=x-p.x()-.5, dy=y-p.y(), dz=z-p.z()-.5; return dx*dx+dy*dy+dz*dz;
        }
    }
    public interface Mobility<A> {
        Observation prepare(A actor, WarMilitiaRequest request);
        Observation observe(A actor, WarMilitiaRequest request);
        Pose pose(A actor);
        /** Advance a previously planned native last segment only while its caller owns current execution. */
        default void tick(A actor){}
        default boolean arrived(A actor,WarIntent.Position point){return pose(actor).distanceSquared(point)<=.81;}
    }
}
