package com.jgeted.sagadyssey.social.market;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Settlement-local transaction tape. No observation is broadcast to another settlement. */
public final class LocalMarketData extends NbtSavedData {
    private static final String DATA_NAME = "sagadyssey_local_markets";
    private static final int MAX_RECORDS_PER_SETTLEMENT = 256;
    public static final long QUOTE_WINDOW = 14L * 24_000L;
    public static final long FRESH_WINDOW = 3L * 24_000L;
    private static final Factory<LocalMarketData> FACTORY =
            new Factory<>(LocalMarketData::new, LocalMarketData::load);

    private final Map<UUID, List<MarketTradeRecord>> trades = new HashMap<>();

    public static LocalMarketData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.type(DATA_NAME));
    }

    public boolean record(MarketTradeRecord record) {
        List<MarketTradeRecord> local = trades.computeIfAbsent(record.settlementId(),
                ignored -> new ArrayList<>());
        if (local.stream().anyMatch(existing -> existing.tradeId().equals(record.tradeId()))) return false;
        local.add(record);
        local.sort(Comparator.comparingLong(MarketTradeRecord::completedAt));
        while (local.size() > MAX_RECORDS_PER_SETTLEMENT) local.removeFirst();
        setDirty();
        return true;
    }

    public List<MarketTradeRecord> recentTrades(UUID settlementId, long since) {
        return trades.getOrDefault(settlementId, List.of()).stream()
                .filter(record -> record.completedAt() >= since)
                .toList();
    }

    public List<MarketTradeRecord> trades() {
        return trades.values().stream().flatMap(List::stream)
                .sorted(Comparator.comparingLong(MarketTradeRecord::completedAt)).toList();
    }

    public MarketQuote quote(UUID settlementId, ItemFingerprint fingerprint, long now) {
        long cutoff = now - QUOTE_WINDOW;
        List<PricePoint> points = trades.getOrDefault(settlementId, List.of()).stream()
                .filter(MarketTradeRecord::publiclyObserved)
                .filter(record -> record.completedAt() >= cutoff && record.completedAt() <= now)
                .map(record -> pricePoint(record, fingerprint))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (points.isEmpty()) return null;
        long weighted = 0L;
        int volume = 0;
        int low = Integer.MAX_VALUE;
        int high = 0;
        long latest = 0L;
        for (PricePoint point : points) {
            weighted += (long) point.unitEmeraldMilli * point.quantity;
            volume += point.quantity;
            low = Math.min(low, point.unitEmeraldMilli);
            high = Math.max(high, point.unitEmeraldMilli);
            latest = Math.max(latest, point.observedAt);
        }
        int unit = (int) Math.max(1L, weighted / Math.max(1, volume));
        return new MarketQuote(settlementId, fingerprint, unit, low, high, volume,
                points.size(), latest, now - latest > FRESH_WINDOW);
    }

    private static PricePoint pricePoint(MarketTradeRecord trade, ItemFingerprint wanted) {
        PricePoint point = directPrice(trade.firstToSecond(), trade.secondToFirst(),
                wanted, trade.completedAt());
        return point != null ? point : directPrice(trade.secondToFirst(),
                trade.firstToSecond(), wanted, trade.completedAt());
    }

    /** Tender side must be emerald-only and goods side one line; ambiguous barter is retained but not priced. */
    private static PricePoint directPrice(List<MarketBasketEntry> tender,
                                          List<MarketBasketEntry> goods,
                                          ItemFingerprint wanted, long observedAt) {
        if (tender.size() != 1 || goods.size() != 1 || !goods.getFirst().fingerprint().equals(wanted)
                || !isEmerald(tender.getFirst().fingerprint())) return null;
        int price = Math.max(1, tender.getFirst().quantity() * 1000 / goods.getFirst().quantity());
        return new PricePoint(price, goods.getFirst().quantity(), observedAt);
    }

    private static boolean isEmerald(ItemFingerprint fingerprint) {
        Identifier id = Identifier.tryParse(fingerprint.itemId());
        return id != null && BuiltInRegistries.ITEM.containsKey(id)
                && BuiltInRegistries.ITEM.getValue(id) == Items.EMERALD;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        trades.values().forEach(records -> records.forEach(record -> list.add(record.save())));
        tag.put("Trades", list);
        return tag;
    }

    static LocalMarketData load(CompoundTag tag, HolderLookup.Provider registries) {
        LocalMarketData data = new LocalMarketData();
        ListTag list = MinecraftApi.listOfType(tag, "Trades", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            try {
                MarketTradeRecord record = MarketTradeRecord.load(list.getCompoundOrEmpty(index));
                data.record(record);
            } catch (RuntimeException ignored) { }
        }
        return data;
    }

    private record PricePoint(int unitEmeraldMilli, int quantity, long observedAt) { }
}
