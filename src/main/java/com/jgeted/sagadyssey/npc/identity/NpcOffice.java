package com.jgeted.sagadyssey.npc.identity;

import net.minecraft.network.chat.Component;

/**
 * 可授予、可撤销的职务级别。显示称谓由所属阵营决定，因而同一级别可以是骑士、教士或行会成员。
 */
public enum NpcOffice {
    NONE("none", 0),
    SWORN("sworn", 3),
    LOCAL_RULER("local", 4),
    HIGH_RULER("high", 5),
    SOVEREIGN("sovereign", 6);

    private final String id;
    private final int precedence;

    NpcOffice(String id, int precedence) {
        this.id = id;
        this.precedence = precedence;
    }

    public String id() { return id; }
    public int precedence() { return precedence; }
    public boolean blocksDirectRecruitment() { return precedence >= LOCAL_RULER.precedence; }

    public Component displayName(String factionId) {
        if (this == NONE) return Component.empty();
        return Component.translatable("office.sagadyssey." + factionKey(factionId) + "." + id);
    }

    public static NpcOffice byName(String name) {
        if (name == null || name.isBlank()) return NONE;
        try {
            return valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return NONE;
        }
    }

    public static String factionKey(String factionId) {
        if (factionId == null || factionId.isBlank()) return "generic";
        int separator = factionId.indexOf(':');
        String shortId = separator >= 0 ? factionId.substring(separator + 1) : factionId;
        return switch (shortId) {
            case "kingdom", "church", "merchant_guild", "bandit", "dwarven", "mystic", "wilderness", "player" -> shortId;
            default -> "generic";
        };
    }
}
