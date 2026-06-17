package com.jgeted.sagadyssey.npc.trade;

import net.minecraft.world.item.ItemStack;

import java.util.Random;

/**
 * 单个交易定义（范围版）。
 * 池中存 min/max 范围，NPC 初始化时 resolve() 固定为确定值。
 */
public record NpcTradeOffer(
        ItemStack costItem,
        int costMin, int costMax,
        ItemStack resultItem,
        int resultMin, int resultMax,
        int minNpcLevel
) {
    /** 取成本数量（resolve 后 min==max，此处取 min 即为确定值） */
    public int costAmount() { return costMin; }

    /** 取结果数量 */
    public int resultAmount() { return resultMin; }

    /** 是否为范围未固定的模板（池中状态） */
    public boolean isTemplate() { return costMin != costMax || resultMin != resultMax; }

    /** NPC 初始化时调用：随机确定数量，返回固定版 */
    public NpcTradeOffer resolve(Random rand) {
        int costAmt = costMin == costMax ? costMin
                : costMin + rand.nextInt(costMax - costMin + 1);
        int resultAmt = resultMin == resultMax ? resultMin
                : resultMin + rand.nextInt(resultMax - resultMin + 1);
        return new NpcTradeOffer(costItem, costAmt, costAmt,
                resultItem, resultAmt, resultAmt, minNpcLevel);
    }

    public ItemStack getCostStack() {
        return costItem.copyWithCount(costAmount());
    }

    public ItemStack getResultStack() {
        return resultItem.copyWithCount(resultAmount());
    }
}
