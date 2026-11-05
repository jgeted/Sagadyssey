package com.jgeted.sagadyssey.social.economy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 持久化每日供给结算，避免服务器重启后同一天重复消耗。 */
public final class SettlementEconomyData extends SavedData {
    private static final String DATA_NAME = "sagadyssey_settlement_economy";
    private static final Factory<SettlementEconomyData> FACTORY =
            new Factory<>(SettlementEconomyData::new, SettlementEconomyData::load);

   private final Map<UUID, SettlementEconomyRecord> records = new HashMap<>();
    private final Map<UUID, com.jgeted.sagadyssey.social.combat.CombatImpact> combatImpacts = new HashMap<>();
    public java.util.List<com.jgeted.sagadyssey.social.combat.CombatImpact> combatImpacts() { return java.util.List.copyOf(combatImpacts.values()); }
    public void recordCombatImpact(com.jgeted.sagadyssey.social.combat.CombatImpact impact) {
        if(com.jgeted.sagadyssey.social.combat.CombatImpact.put(combatImpacts,impact))setDirty();
    }

    public static SettlementEconomyData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** 每个聚落独立判定；区块未加载时不会提前吞掉其他聚落的当日结算机会。 */
    public boolean shouldProcess(UUID settlementId, long day) {
        SettlementEconomyRecord record = records.get(settlementId);
        return record == null || record.processedDay() < day;
    }

    @Nullable public SettlementEconomyRecord record(UUID settlementId) { return records.get(settlementId); }

    public void update(SettlementEconomyRecord record) {
        records.put(record.settlementId(), record);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("CombatImpacts",com.jgeted.sagadyssey.social.combat.CombatImpact.save(combatImpacts));
        ListTag list = new ListTag();
        records.values().forEach(record -> list.add(record.save()));
        tag.put("Records", list);
        return tag;
    }

    private static SettlementEconomyData load(CompoundTag tag, HolderLookup.Provider registries) {
        SettlementEconomyData data = new SettlementEconomyData();
        com.jgeted.sagadyssey.social.combat.CombatImpact.load(tag,data.combatImpacts);
        ListTag list = tag.getList("Records", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            SettlementEconomyRecord record = SettlementEconomyRecord.load(list.getCompound(i));
            data.records.put(record.settlementId(), record);
        }
        return data;
    }
}
