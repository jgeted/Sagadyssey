package com.jgeted.sagadyssey.social.war.api;

import java.util.*;

/** 单个人物的一次服役请求；只存人物引用和军事约定，不复制生活或财产记录。 */
public record WarMilitiaRequest(UUID id, UUID actorId, UUID armyId, UUID unitId, UUID characterId,
                                WarKey loadout, int definitionVersion, Kind kind, Role role, Wage wage,
                                long requestedAtTick) {
    public enum Kind { MILITIA, REGULAR }
    public enum Role { COMBATANT, STANDARD_BEARER, ENGINEER, COURIER, STAFF }
    public record Wage(WarKey currency, long amountPerPeriod, long periodTicks) {
        public Wage {
            Objects.requireNonNull(currency);
            if (amountPerPeriod < 1 || periodTicks < 1) throw new IllegalArgumentException("positive wage terms required");
        }
    }
    public WarMilitiaRequest {
        Objects.requireNonNull(id); Objects.requireNonNull(actorId); Objects.requireNonNull(armyId);
        Objects.requireNonNull(unitId); Objects.requireNonNull(characterId); Objects.requireNonNull(loadout);
        Objects.requireNonNull(kind); Objects.requireNonNull(role); Objects.requireNonNull(wage);
        if (definitionVersion < 1 || requestedAtTick < 0) throw new IllegalArgumentException("invalid mobilization request");
    }
}
