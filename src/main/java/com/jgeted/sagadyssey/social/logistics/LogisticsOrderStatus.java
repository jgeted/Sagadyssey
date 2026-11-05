package com.jgeted.sagadyssey.social.logistics;

public enum LogisticsOrderStatus {
    PLANNED, IN_PROGRESS, DELIVERED, CANCELLED;

    public static LogisticsOrderStatus byName(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return PLANNED;
        }
    }
}
