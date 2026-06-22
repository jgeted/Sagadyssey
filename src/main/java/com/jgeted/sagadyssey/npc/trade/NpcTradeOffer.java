package com.jgeted.sagadyssey.npc.trade;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * 单个交易定义（范围版）。
 * 池中存 min/max 范围，NPC 初始化时 resolve() 固定为确定值。
 * 特殊物品（唱片、附魔书、附魔装备等）通过 specialResultResolver 在 resolve 时动态生成。
 */
public record NpcTradeOffer(
        ItemStack costItem,
        int costMin, int costMax,
        ItemStack resultItem,
        int resultMin, int resultMax,
        int minNpcLevel,
        @Nullable SpecialItemResolver specialResultResolver
) {
    /** 无特殊 resolver 的便利构造器（绝大多数 offer 用这个） */
    public NpcTradeOffer(ItemStack costItem, int costMin, int costMax,
                         ItemStack resultItem, int resultMin, int resultMax, int minNpcLevel) {
        this(costItem, costMin, costMax, resultItem, resultMin, resultMax, minNpcLevel, null);
    }

    /** 取成本数量（resolve 后 min==max，此处取 min 即为确定值） */
    public int costAmount() { return costMin; }

    /** 取结果数量 */
    public int resultAmount() { return resultMin; }

    /** 是否为范围未固定的模板（池中状态） */
    public boolean isTemplate() { return costMin != costMax || resultMin != resultMax || specialResultResolver != null; }

    /** NPC 初始化时调用：随机确定数量，返回固定版（无特殊结果时使用） */
    public NpcTradeOffer resolve(Random rand) {
        return resolveWithRegistry(rand, null);
    }

    /** 带 RegistryAccess 的 resolve。特殊物品需要 RegistryAccess 查附魔注册表等 */
    public NpcTradeOffer resolveWithRegistry(Random rand, @Nullable RegistryAccess registry) {
        int costAmt = costMin == costMax ? costMin
                : costMin + rand.nextInt(costMax - costMin + 1);
        int resultAmt = resultMin == resultMax ? resultMin
                : resultMin + rand.nextInt(resultMax - resultMin + 1);

        ItemStack resolvedResult;
        if (specialResultResolver != null && registry != null) {
            resolvedResult = specialResultResolver.resolve(rand, registry);
        } else {
            resolvedResult = resultItem;
        }
        resolvedResult.setCount(resultAmt);

        return new NpcTradeOffer(costItem, costAmt, costAmt,
                resolvedResult, resultAmt, resultAmt, minNpcLevel, null);
    }

    public ItemStack getCostStack() {
        return costItem.copyWithCount(costAmount());
    }

    public ItemStack getResultStack() {
        return resultItem.copyWithCount(resultAmount());
    }

    /** 特殊物品生成器：在 NPC 初始化时动态生成结果物品（唱片、附魔书、附魔装备等） */
    @FunctionalInterface
    public interface SpecialItemResolver {
        ItemStack resolve(Random rand, RegistryAccess registry);
    }
}
