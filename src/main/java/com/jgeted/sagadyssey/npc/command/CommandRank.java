package com.jgeted.sagadyssey.npc.command;

/** 玩家集团内部的指挥等级；它独立于 NPC 的成长等级。 */
public enum CommandRank {
    RECRUIT("新兵", 0),
    MEMBER("队员", 1),
    VETERAN("老兵", 2),
    CAPTAIN("队长", 3),
    COMMANDER("指挥官", 4);

    private final String displayName;
    private final int priority;

    CommandRank(String displayName, int priority) {
        this.displayName = displayName;
        this.priority = priority;
    }

    public String displayName() { return displayName; }
    public int priority() { return priority; }

    public CommandRank promote() {
        CommandRank[] values = values();
        return values[Math.min(values.length - 1, ordinal() + 1)];
    }

    public CommandRank demote() {
        return values()[Math.max(0, ordinal() - 1)];
    }

    public static CommandRank byName(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return MEMBER;
        }
    }
}
