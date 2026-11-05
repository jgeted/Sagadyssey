package com.jgeted.sagadyssey.npc.ai;

/**
 * AI 行为互斥标记。
 * 只保存原生 Goal.Flag 无法表达的高层语义互斥；移动互斥由 GoalSelector 处理。
 */
public enum AiMutex {
    /** 攻击行为（近战、远程、盾挡） */
    ATTACK,
    /** 水中行为 */
    SWIM,
    /** 饥饿/进食行为 */
    HUNGRY,
    /** 社交行为（恐慌、求救、指挥官） */
    SOCIAL
}
