package com.jgeted.sagadyssey.npc.profession;

/**
 * NPC 职业类型。
 * 后续 6-8 种 NPC 从这些枚举中选取。
 */
public enum NpcProfession {
    /** 无职业——刚生成、未被玩家分配的 NPC */
    NONE("无"),

    /** 战斗系 */
    WARRIOR("战士"),
    ARCHER("弓箭手"),
    HEAVY("重甲兵"),

    /** 工作系 */
    WORKER("工人"),
    BLACKSMITH("铁匠"),
    FARMER("农民"),

    /** 支援系 */
    BARD("吟游诗人"),
    MEDIC("医师"),
    TRADER("商人");

    private final String displayName;

    NpcProfession(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
