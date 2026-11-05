package com.jgeted.sagadyssey.social.service;

public enum ServiceStatus {
    ACTIVE,
    SUSPENDED,
    COMPLETED,
    CANCELLED,
    BREACHED;

    public boolean grantsAuthority() { return this == ACTIVE; }

    public String displayName() {
        return switch (this) {
            case ACTIVE -> "生效中";
            case SUSPENDED -> "已暂停";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已解除";
            case BREACHED -> "已违约";
        };
    }
}
