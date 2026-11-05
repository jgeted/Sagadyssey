package com.jgeted.sagadyssey.npc.entity;

/**
 * 客户端可见的 NPC 短时动作。
 * 只描述表现，不替代服务端 AI、物品或方块交互逻辑。
 */
public enum NpcAction {
    NONE,
    GREET,
    HEAD_NOD,
    COURTESY_BOW,
    CHEST_SALUTE,
    NOBLE_ACKNOWLEDGE,
    ROYAL_WAVE,
    MELEE_SLASH,
    HEAVY_STRIKE,
    BOW_RELEASE,
    MINE,
    CHOP,
    PLACE,
    TILL,
    PLANT,
    HARVEST,
    SMITH,
    PERFORM,
    MEDIC_TEND,
    TRADE_PRESENT,
    /** 目标至少高于 NPC 脚下两格；保持躯干直立并向上挥动工具。 */
    MINE_HIGH,
    CHOP_HIGH,
    /** 战士短距突击。 */
    CHARGE,
    /** 重甲兵在待命点采取的坚守姿态。 */
    BRACE,
    /** 弩手横持武器、拉弦并装填箭矢。 */
    CROSSBOW_LOAD,
    /** 将食物举到嘴边。 */
    EAT
}
