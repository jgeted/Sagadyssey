package com.jgeted.sagadyssey.npc.faction;

/**
 * NPC 阵营。
 * HOSTILE: 主动攻击非主人的玩家，可高价招募
 * NEUTRAL: 不攻击，可正常招募
 * FRIENDLY: 不攻击，可正常招募（好感度系统后续接入）
 */
public enum NpcFaction {
    HOSTILE("敌对"),
    NEUTRAL("中立"),
    FRIENDLY("友好");

    private final String displayName;

    NpcFaction(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
