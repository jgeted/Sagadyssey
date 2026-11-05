package com.jgeted.sagadyssey.social.settlement;

public enum SettlementType {
    HAMLET, VILLAGE, TOWN, FORT, CAMP, SANCTUARY;

    public static SettlementType byName(String name) {
        if (name == null || name.isBlank()) return HAMLET;
        try {
            return valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return HAMLET;
        }
    }

    public static SettlementType fromBuildingCategory(String category) {
        return switch (category == null ? "" : category.toLowerCase(java.util.Locale.ROOT)) {
            case "village" -> VILLAGE;
            case "camp" -> CAMP;
            case "church" -> SANCTUARY;
            case "watchtower", "wallgate" -> FORT;
            default -> HAMLET;
        };
    }
}
