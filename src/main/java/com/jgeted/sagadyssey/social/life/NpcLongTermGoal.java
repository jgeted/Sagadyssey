package com.jgeted.sagadyssey.social.life;

/** 长期目标提供稳定方向，但不会像命令一样强制覆盖当下需求。 */
public enum NpcLongTermGoal {
    SURVIVE("安稳生存"),
    PROTECT_FAMILY("保护亲友"),
    PROSPER("积累财富"),
    MASTER_CRAFT("精进技艺"),
    DEFEND_HOME("守卫家园"),
    SERVE_FAITH("践行信仰"),
    GAIN_STATUS("提升地位"),
    SEEK_FREEDOM("争取自由");

    private final String displayName;
    NpcLongTermGoal(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }

    public static NpcLongTermGoal parse(String value) {
        if (value == null || value.isBlank()) return SURVIVE;
        try { return valueOf(value); }
        catch (IllegalArgumentException ignored) { return SURVIVE; }
    }
}
