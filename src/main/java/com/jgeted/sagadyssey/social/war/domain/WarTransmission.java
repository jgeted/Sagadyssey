package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.*;
import java.util.*;

/** 同一军令的传递及收到凭据；成员只引用已动员的原人物/身体，不复制人物事实。 */
public record WarTransmission(WarCommand.IssueOrder command, long sequence, Route route, List<Member> members,
                               Optional<WarUnit.Order> received) {
    public static final int MAX_MEMBERS = 128;
    public enum Channel { DIRECT, COURIER }
    public record Route(WarIntent.Position origin, WarIntent.Position reportedPosition, long reportedAt, long sentAt,
                        long delayTicks, Channel channel) {
        public Route {
            Objects.requireNonNull(origin); Objects.requireNonNull(reportedPosition); Objects.requireNonNull(channel);
            if (!origin.dimension().equals(reportedPosition.dimension()) || reportedAt < 0 || sentAt < reportedAt || sentAt - reportedAt > 1200
                    || delayTicks < 5 || delayTicks > 2400 || sentAt > Long.MAX_VALUE - delayTicks) throw new IllegalArgumentException("invalid transmission route");
            double distance = distance(origin, reportedPosition);
            var expected = distance <= 32 ? Channel.DIRECT : Channel.COURIER;
            long delay = expected == Channel.DIRECT ? 5 : 20 + (long)Math.ceil(distance / 4) * 5;
            if (distance > 1024 || channel != expected || delayTicks != delay) throw new IllegalArgumentException("unsupported transmission timing");
        }
        public long dueAt() { return sentAt + delayTicks; }
    }
    public record Member(UUID contract, UUID character, UUID body) {
        public Member { Objects.requireNonNull(contract); Objects.requireNonNull(character); Objects.requireNonNull(body); }
    }
    public WarTransmission {
        Objects.requireNonNull(command); Objects.requireNonNull(route); Objects.requireNonNull(received); members = List.copyOf(members);
        if (sequence < 1 || members.isEmpty() || members.size() > MAX_MEMBERS || !command.intent().target().dimension().equals(route.origin().dimension())
                || members.stream().map(Member::contract).distinct().count() != members.size() || members.stream().map(Member::character).distinct().count() != members.size()
                || members.stream().map(Member::body).distinct().count() != members.size()) throw new IllegalArgumentException("invalid transmission members/sequence");
        if (received.isPresent()) {
            var order = received.get();
            if (!order.id().equals(command.operationId()) || order.generation() != command.generation() || !order.issuerId().equals(command.actorId())
                    || order.sequence() != sequence || !order.intent().equals(command.intent()) || order.issuedAtTick() > route.sentAt() || order.deliveredAtTick() < route.dueAt()) throw new IllegalArgumentException("foreign/early received order");
        }
    }
    public WarTransmission received(WarUnit.Order order) { return new WarTransmission(command, sequence, route, members, Optional.of(order)); }
    public static boolean follows(WarTransmission old, WarTransmission next) {
        return old.equals(next) || old.command.equals(next.command) && old.sequence == next.sequence && old.route.equals(next.route) && old.members.equals(next.members)
                && old.received.isEmpty() && next.received.isPresent();
    }
    public static Route route(WarIntent.Position origin, WarIntent.Position report, long observed, long now) {
        double distance = distance(origin, report);
        if (distance > 1024) throw new IllegalArgumentException("report is beyond first transmission range");
        var channel = distance <= 32 ? Channel.DIRECT : Channel.COURIER;
        long delay = channel == Channel.DIRECT ? 5 : 20 + (long)Math.ceil(distance / 4) * 5;
        return new Route(origin, report, observed, now, delay, channel);
    }
    private static double distance(WarIntent.Position a, WarIntent.Position b) { return Math.hypot(Math.hypot((long)a.x() - b.x(), (long)a.z() - b.z()), (long)a.y() - b.y()); }
    /** 固定成员槽位，不因NPC当前迭代顺序变阵；后加入者等待下一份显式军令。 */
    public WarIntent.Position station(Member member) {
        return station(member, command.intent().target());
    }
    public WarIntent.Position station(Member member, WarIntent.Position target) {
        int slot = members.indexOf(member); if (slot < 0) throw new IllegalArgumentException("member not in received roster");
        return station(slot, target);
    }
    public WarIntent.Position station(int slot, WarIntent.Position target) {
        if (slot < 0 || slot >= members.size()) throw new IllegalArgumentException("invalid roster slot");
        int width = (int)Math.ceil(Math.sqrt(members.size()));
        return new WarIntent.Position(target.dimension(), Math.addExact(target.x(), (slot % width) * 2 - width + 1), target.y(), Math.addExact(target.z(), (slot / width) * 2));
    }
}
