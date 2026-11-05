package com.jgeted.sagadyssey.social.household;

/** 家庭身份只描述生活关系，不授予工作、战斗或政治指挥权。 */
public enum HouseholdRole {
    HEAD("户主"),
    SPOUSE("配偶"),
    CHILD("子女"),
    KIN("亲属"),
    DEPENDENT("受赡养者"),
    SERVANT("家仆"),
    LODGER("寄居者");

    private final String displayName;

    HouseholdRole(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }

    public static HouseholdRole parse(String value) {
        try {
            return value == null || value.isBlank() ? KIN : valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return KIN;
        }
    }
}
