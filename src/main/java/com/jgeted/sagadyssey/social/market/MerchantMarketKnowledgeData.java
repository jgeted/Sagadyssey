package com.jgeted.sagadyssey.social.market;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Private, travel-borne market knowledge. It never reads a remote market at decision time. */
public final class MerchantMarketKnowledgeData extends SavedData {
    private static final String DATA_NAME = "sagadyssey_merchant_market_knowledge";
    private static final int MAX_MEMORIES_PER_MERCHANT = 128;
    private static final Factory<MerchantMarketKnowledgeData> FACTORY =
            new Factory<>(MerchantMarketKnowledgeData::new, MerchantMarketKnowledgeData::load);
    private final Map<UUID, List<MerchantPriceMemory>> memories = new HashMap<>();

    public static MerchantMarketKnowledgeData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public int observe(UUID merchantId, UUID settlementId, Container inventory,
                       LocalMarketData market, long now) {
        Set<ItemFingerprint> fingerprints = new HashSet<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) fingerprints.add(ItemFingerprint.of(stack));
        }
        int learned = 0;
        for (ItemFingerprint fingerprint : fingerprints) {
            MarketQuote quote = market.quote(settlementId, fingerprint, now);
            if (quote == null) continue;
            remember(new MerchantPriceMemory(merchantId, settlementId, fingerprint,
                    quote.unitEmeraldMilli(), quote.sampleCount(), now));
            learned++;
        }
        return learned;
    }

    public void remember(MerchantPriceMemory memory) {
        List<MerchantPriceMemory> local = memories.computeIfAbsent(memory.merchantId(),
                ignored -> new ArrayList<>());
        local.removeIf(existing -> existing.settlementId().equals(memory.settlementId())
                && existing.fingerprint().equals(memory.fingerprint()));
        local.add(memory);
        local.sort(Comparator.comparingLong(MerchantPriceMemory::observedAt));
        while (local.size() > MAX_MEMORIES_PER_MERCHANT) local.removeFirst();
        setDirty();
    }

    /** Best remembered sale price after explicit transport and danger deductions. */
    public int bestRemoteNetPrice(UUID merchantId, UUID currentSettlement,
                                  ItemFingerprint fingerprint, long now,
                                  int transportCostPerUnitMilli, int riskBasisPoints) {
        return memories.getOrDefault(merchantId, List.of()).stream()
                .filter(value -> !value.settlementId().equals(currentSettlement)
                        && value.fingerprint().equals(fingerprint)
                        && now - value.observedAt() <= LocalMarketData.QUOTE_WINDOW)
                .mapToInt(value -> {
                    long afterRisk = (long) value.unitEmeraldMilli()
                            * (10_000 - Math.max(0, Math.min(5_000, riskBasisPoints))) / 10_000L;
                    long net = Math.max(0L, afterRisk - Math.max(0, transportCostPerUnitMilli));
                    return (int) Math.min(Integer.MAX_VALUE, net);
                }).max().orElse(0);
    }

    public List<MerchantPriceMemory> memories(UUID merchantId) {
        return List.copyOf(memories.getOrDefault(merchantId, List.of()));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        memories.values().forEach(values -> values.forEach(value -> list.add(value.save())));
        tag.put("Memories", list);
        return tag;
    }

    static MerchantMarketKnowledgeData load(CompoundTag tag, HolderLookup.Provider registries) {
        MerchantMarketKnowledgeData data = new MerchantMarketKnowledgeData();
        ListTag list = tag.getList("Memories", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            try { data.remember(MerchantPriceMemory.load(list.getCompound(index))); }
            catch (RuntimeException ignored) { }
        }
        return data;
    }
}
