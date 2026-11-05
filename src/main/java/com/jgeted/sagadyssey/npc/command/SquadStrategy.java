package com.jgeted.sagadyssey.npc.command;

/** 小队级战术。NPC 的本地自卫仍然保留，战术只负责更高层目标分配。 */
public enum SquadStrategy {
    BALANCED("均衡分配", "在多个敌人之间分散火力"),
    NEAREST("就近攻击", "每名成员选择离自己最近的敌人"),
    HIGH_RANK_FIRST("优先高阶", "优先攻击敌方指挥等级更高的单位"),
    PROTECT_OWNER("保护领主", "主人受击时集中攻击威胁"),
    HOLD_POSITION("坚守阵地", "仅处理主人防御与近身威胁"),
    RETREAT("撤退跟随", "停止集团追击并回到主人身边"),
    ESCORT_CONVOY("护送运输", "战斗成员保持在同队运输者周围");

    private final String displayName;
    private final String description;

    SquadStrategy(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }

    public SquadStrategy next() {
        SquadStrategy[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static SquadStrategy byName(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return BALANCED;
        }
    }
}
