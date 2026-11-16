package com.jgeted.sagadyssey.social.war.domain;

import java.util.*;
import java.util.function.Predicate;

/** 原身体的军令接收/冷却检查点。其存储是原实体的一部分，不宣称跨文件原子落盘。 */
public record WarBodyCheckpoint(UUID contract, UUID unit, UUID order, long sequence, long nextAttack) {
    public WarBodyCheckpoint {
        Objects.requireNonNull(contract); Objects.requireNonNull(unit); Objects.requireNonNull(order);
        if (sequence < 1 || nextAttack < 0) throw new IllegalArgumentException("invalid body checkpoint");
    }
    public static Optional<WarBodyCheckpoint> receive(Optional<WarBodyCheckpoint> old, WarTransmission transmission,
            WarTransmission.Member member, long now, long recoveryTicks, Predicate<UUID> releasedContract) {
        return receive(old, transmission, member, transmission.members().indexOf(member), now, recoveryTicks, releasedContract);
    }

    public static Optional<WarBodyCheckpoint> receive(Optional<WarBodyCheckpoint> old, WarTransmission transmission,
            WarTransmission.Member member, int slot, long now, long recoveryTicks, Predicate<UUID> releasedContract) {
        Objects.requireNonNull(old); Objects.requireNonNull(transmission); Objects.requireNonNull(member); Objects.requireNonNull(releasedContract);
        if (now < 0 || recoveryTicks < 1 || recoveryTicks > 2400 || slot < 0 || slot >= transmission.members().size()
                || !transmission.members().get(slot).equals(member)
                || transmission.received().isEmpty() || transmission.received().get().deliveredAtTick() > now) return Optional.empty();
        long limit = now > Long.MAX_VALUE - recoveryTicks ? Long.MAX_VALUE : now + recoveryTicks;
        if (old.isPresent()) {
            var saved = old.get(); if (saved.nextAttack > limit) return Optional.empty();
            if (saved.contract.equals(member.contract())) {
                if (!saved.unit.equals(transmission.command().unitId()) || saved.sequence > transmission.sequence()
                        || saved.sequence == transmission.sequence() && !saved.order.equals(transmission.command().operationId())) return Optional.empty();
                if (saved.sequence == transmission.sequence()) return old;
            } else if (!releasedContract.test(saved.contract)) return Optional.empty();
        }
        return Optional.of(new WarBodyCheckpoint(member.contract(), transmission.command().unitId(), transmission.command().operationId(),
                transmission.sequence(), old.map(WarBodyCheckpoint::nextAttack).orElse(0L)));
    }
    public Optional<WarBodyCheckpoint> reserveAttack(long now, long recoveryTicks) {
        if (now < nextAttack || recoveryTicks < 1 || recoveryTicks > 2400 || now > Long.MAX_VALUE - recoveryTicks) return Optional.empty();
        return Optional.of(new WarBodyCheckpoint(contract, unit, order, sequence, now + recoveryTicks));
    }
}
