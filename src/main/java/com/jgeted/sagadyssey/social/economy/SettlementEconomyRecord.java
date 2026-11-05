package com.jgeted.sagadyssey.social.economy;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** 聚落最近一次真实供给结算的摘要。 */
public record SettlementEconomyRecord(UUID settlementId, int population, int foodRequired,
                                      int foodConsumed, int shortageDays, long processedDay) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Settlement", settlementId);
        tag.putInt("Population", population);
        tag.putInt("FoodRequired", foodRequired);
        tag.putInt("FoodConsumed", foodConsumed);
        tag.putInt("ShortageDays", shortageDays);
        tag.putLong("ProcessedDay", processedDay);
        return tag;
    }

    public static SettlementEconomyRecord load(CompoundTag tag) {
        return new SettlementEconomyRecord(tag.getUUID("Settlement"),
                Math.max(0, tag.getInt("Population")), Math.max(0, tag.getInt("FoodRequired")),
                Math.max(0, tag.getInt("FoodConsumed")), Math.max(0, tag.getInt("ShortageDays")),
                tag.getLong("ProcessedDay"));
    }
}
