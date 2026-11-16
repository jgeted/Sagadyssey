package com.jgeted.sagadyssey.social.war.api;

import java.util.*;

/** 己方实际观察的执行摘要；不包含敌方私有健康、库存或命令。 */
public record WarFieldReport(UUID contract, UUID character, UUID body, UUID unit, Optional<UUID> order,
                             State state, WarIntent.Position position, long observedAt, String reason) {
    public enum State { WAITING, MOVING, HOLDING, ENGAGING, RETREATING, REGROUPING, BLOCKED, REVIEW }
    public WarFieldReport {
        Objects.requireNonNull(contract); Objects.requireNonNull(character); Objects.requireNonNull(body); Objects.requireNonNull(unit);
        Objects.requireNonNull(order); Objects.requireNonNull(state); Objects.requireNonNull(position); Objects.requireNonNull(reason);
        if (observedAt < 0 || reason.length() > 512) throw new IllegalArgumentException("invalid field report");
    }
}
