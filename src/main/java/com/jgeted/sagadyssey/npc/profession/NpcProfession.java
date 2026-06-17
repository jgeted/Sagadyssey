package com.jgeted.sagadyssey.npc.profession;

/**
 * NPC 职业类型。
 * 后续 6-8 种 NPC 从这些枚举中选取。
 */
public enum NpcProfession {
    /** 无职业——刚生成、未被玩家分配的 NPC */
    NONE("无", 3, 20, 1.0, 0.30, 0),

    /** 战斗系 */
    WARRIOR("战士", 6, 28, 3.5, 0.30, 2),
    ARCHER("弓箭手", 5, 22, 2.0, 0.36, 0),
    HEAVY("重甲兵", 8, 40, 2.5, 0.22, 8),

    /** 工作系 */
    WORKER("工人", 3, 24, 1.5, 0.30, 0),
    BLACKSMITH("铁匠", 10, 26, 2.5, 0.28, 2),
    FARMER("农民", 3, 22, 1.0, 0.30, 0),

    /** 支援系 */
    BARD("吟游诗人", 4, 20, 1.0, 0.33, 0),
    MEDIC("医师", 5, 22, 1.0, 0.30, 1),
    TRADER("商人", 5, 24, 1.0, 0.33, 0);

    private final String displayName;
    private final int recruitmentCost;
    private final double maxHp;
    private final double attackDamage;
    private final double speed;
    private final double armor;

    NpcProfession(String displayName, int recruitmentCost,
                  double maxHp, double attackDamage, double speed, double armor) {
        this.displayName = displayName;
        this.recruitmentCost = recruitmentCost;
        this.maxHp = maxHp;
        this.attackDamage = attackDamage;
        this.speed = speed;
        this.armor = armor;
    }

    public String getDisplayName() { return displayName; }
    public int getRecruitmentCost() { return recruitmentCost; }
    public double getMaxHp() { return maxHp; }
    public double getAttackDamage() { return attackDamage; }
    public double getSpeed() { return speed; }
    public double getArmor() { return armor; }
}
