package com.jgeted.sagadyssey.social.life;

/** 合同状态与权限状态分离；历史合同结束后仍可保留用于诊断。 */
public enum NpcContractStatus {
    ACTIVE("履行中"),
    PAYMENT_BLOCKED("等待收款箱"),
    IN_ARREARS("欠薪"),
    COMPLETED("期满"),
    CANCELLED("已解除"),
    BREACHED("因欠薪违约");

    private final String displayName;

    NpcContractStatus(String displayName) { this.displayName = displayName; }

    public String displayName() { return displayName; }

    public boolean ongoing() {
        return this == ACTIVE || this == PAYMENT_BLOCKED || this == IN_ARREARS;
    }

    public static NpcContractStatus parse(String value) {
        try {
            return value == null || value.isBlank() ? ACTIVE : valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return ACTIVE;
        }
    }
}
