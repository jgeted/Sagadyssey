package com.jgeted.sagadyssey.social.life;

/** NPC 当前日程阶段；它约束新任务启动，但不会粗暴中断已开始的实体交互。 */
public enum NpcScheduleActivity {
    COMBAT("战斗应急"),
    URGENT_NEEDS("处理紧急需求"),
    FOLLOW("执行跟随命令"),
    STAY("执行驻守命令"),
    WORK("排班工作"),
    DUTY("排班巡逻"),
    REST("回家休息"),
    PERSONAL("个人时间");

    private final String displayName;

    NpcScheduleActivity(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }

    public static NpcScheduleActivity parse(String value) {
        if (value == null || value.isBlank()) return PERSONAL;
        try { return valueOf(value); }
        catch (IllegalArgumentException ignored) { return PERSONAL; }
    }
}
