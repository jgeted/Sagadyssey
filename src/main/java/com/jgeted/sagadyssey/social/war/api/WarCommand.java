package com.jgeted.sagadyssey.social.war.api;

import java.util.Objects;
import java.util.UUID;

/** 提交时间不属于业务指纹；重试必须保留原请求及原版本前提。 */
public sealed interface WarCommand permits WarCommand.CreateUnit, WarCommand.IssueOrder {
    UUID operationId();
    long generation();
    UUID actorId();
    UUID armyId();
    UUID unitId();

    record CreateUnit(UUID operationId, long generation, UUID actorId, UUID armyId, UUID unitId,
                      WarKey troopType, int definitionVersion) implements WarCommand {
        public CreateUnit(UUID operationId, UUID actorId, UUID armyId, UUID unitId, WarKey troopType, int definitionVersion) {
            this(operationId, 0, actorId, armyId, unitId, troopType, definitionVersion);
        }
        public CreateUnit {
            requireIds(operationId, actorId, armyId, unitId);
            Objects.requireNonNull(troopType, "troopType");
            if (generation < 0 || definitionVersion < 1) throw new IllegalArgumentException("invalid generation or troop version");
        }
    }

    record IssueOrder(UUID operationId, long generation, UUID actorId, UUID armyId, UUID unitId,
                      long expectedRevision, WarIntent intent) implements WarCommand {
        public IssueOrder(UUID operationId, UUID actorId, UUID armyId, UUID unitId, long expectedRevision, WarIntent intent) {
            this(operationId, 0, actorId, armyId, unitId, expectedRevision, intent);
        }
        public IssueOrder {
            requireIds(operationId, actorId, armyId, unitId);
            Objects.requireNonNull(intent, "intent");
            if (generation < 0 || expectedRevision < 1) throw new IllegalArgumentException("invalid generation or unit revision");
        }
    }

    private static void requireIds(UUID operation, UUID actor, UUID army, UUID unit) {
        Objects.requireNonNull(operation, "operationId");
        Objects.requireNonNull(actor, "actorId");
        Objects.requireNonNull(army, "armyId");
        Objects.requireNonNull(unit, "unitId");
    }
}
