package com.jgeted.sagadyssey.social.economy;

import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** 聚落最近一次真实供给结算的摘要。 */
public record SettlementEconomyRecord(UUID settlementId, int population, int foodRequired,
                                      int foodConsumed, int shortageDays, long processedDay) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Settlement", UUIDUtil.CODEC, settlementId);
        tag.putInt("Population", population);
        tag.putInt("FoodRequired", foodRequired);
        tag.putInt("FoodConsumed", foodConsumed);
        tag.putInt("ShortageDays", shortageDays);
        tag.putLong("ProcessedDay", processedDay);
        return tag;
    }

    public static SettlementEconomyRecord load(CompoundTag tag) {
        return new SettlementEconomyRecord(tag.read("Settlement", UUIDUtil.CODEC).orElseThrow(),
                Math.max(0, tag.getIntOr("Population", 0)), Math.max(0, tag.getIntOr("FoodRequired", 0)),
                Math.max(0, tag.getIntOr("FoodConsumed", 0)), Math.max(0, tag.getIntOr("ShortageDays", 0)),
                tag.getLongOr("ProcessedDay", 0L));
    }
}
