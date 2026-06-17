package com.jgeted.sagadyssey.npc.profession;

/**
 * NPC 职业类型。
 * 后续 6-8 种 NPC 从这些枚举中选取。
 */
public enum NpcProfession {
    /** 无职业——刚生成、未被玩家分配的 NPC */
    NONE("无", 3),

    /** 战斗系 */
    WARRIOR("战士", 6),
    ARCHER("弓箭手", 5),
    HEAVY("重甲兵", 8),

    /** 工作系 */
    WORKER("工人", 3),
    BLACKSMITH("铁匠", 10),
    FARMER("农民", 3),

    /** 支援系 */
    BARD("吟游诗人", 4),
    MEDIC("医师", 5),
    TRADER("商人", 5);

    private final String displayName;
    private final int recruitmentCost;

    NpcProfession(String displayName, int recruitmentCost) {
        this.displayName = displayName;
        this.recruitmentCost = recruitmentCost;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getRecruitmentCost() {
        return recruitmentCost;
    }
}
