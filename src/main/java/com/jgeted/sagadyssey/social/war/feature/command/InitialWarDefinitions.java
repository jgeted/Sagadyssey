package com.jgeted.sagadyssey.social.war.feature.command;

import com.jgeted.sagadyssey.social.war.api.WarKey;
import com.jgeted.sagadyssey.social.war.domain.WarDefinitions;
import com.jgeted.sagadyssey.social.war.domain.WarLoadoutCatalog;

import java.util.List;
import java.util.Set;

/** 初始稳定定义ID，不包含尚未接入的世界战斗或免费的共享能力。 */
public final class InitialWarDefinitions {
    public static final WarKey CORE = key("core");
    public static final WarKey MOVE = key("order/move");
    public static final WarKey HOLD = key("order/hold");
    public static final WarKey RETREAT = key("order/retreat");
    public static final WarKey RALLY = key("order/rally");
    public static final WarKey CHARGE = key("order/charge");
    public static final WarLoadoutCatalog LOADOUTS = WarLoadoutCatalog.initial();
    public static final List<WarKey> TROOPS = List.copyOf(LOADOUTS.profiles().keySet());
    public static final List<WarKey> LEGACY_TROOPS = List.copyOf(LOADOUTS.legacyAliases().keySet());

    private InitialWarDefinitions() {}
    private static WarKey key(String path) { return new WarKey("sagadyssey", "war/" + path); }

    public static WarDefinitions create() {
        var builder = new WarDefinitions.Builder();
        builder.register(new WarDefinitions.Definition(CORE, WarDefinitions.Kind.MODULE, 1, Set.of()));
        for (WarKey troop : TROOPS) {
            builder.register(new WarDefinitions.Definition(troop, WarDefinitions.Kind.TROOP, 1, Set.of(CORE)));
        }
        for (WarKey legacy : LEGACY_TROOPS) {
            builder.register(new WarDefinitions.Definition(legacy, WarDefinitions.Kind.TROOP, 1, Set.of(CORE)));
        }
        for (WarKey order : List.of(MOVE, HOLD, RETREAT, RALLY, CHARGE)) {
            builder.register(new WarDefinitions.Definition(order, WarDefinitions.Kind.ORDER, 1, Set.of(CORE)));
        }
        return builder.freeze();
    }
}
