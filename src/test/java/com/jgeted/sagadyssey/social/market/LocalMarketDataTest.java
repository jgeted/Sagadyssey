package com.jgeted.sagadyssey.social.market;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalMarketDataTest {
    @Test
    void quoteIsVolumeWeightedFromCompletedPublicLocalTrades() {
        LocalMarketData data = new LocalMarketData();
        UUID settlement = UUID.randomUUID();
        ItemFingerprint emerald = ItemFingerprint.of(new ItemStack(Items.EMERALD));
        ItemFingerprint bread = ItemFingerprint.of(new ItemStack(Items.BREAD));
        data.record(trade(settlement, emerald, 4, bread, 2, 1_000L, true));
        data.record(trade(settlement, emerald, 9, bread, 3, 2_000L, true));
        data.record(trade(settlement, emerald, 64, bread, 1, 2_500L, false));

        MarketQuote quote = data.quote(settlement, bread, 3_000L);

        assertNotNull(quote);
        assertEquals(2_600, quote.unitEmeraldMilli());
        assertEquals(2_000, quote.lowEmeraldMilli());
        assertEquals(3_000, quote.highEmeraldMilli());
        assertEquals(5, quote.tradedQuantity());
        assertEquals(2, quote.sampleCount());
        assertFalse(quote.stale());
    }

    @Test
    void pricesDoNotTeleportBetweenSettlementsAndOldQuotesAreMarkedStale() {
        LocalMarketData data = new LocalMarketData();
        UUID observed = UUID.randomUUID();
        UUID remote = UUID.randomUUID();
        ItemFingerprint emerald = ItemFingerprint.of(new ItemStack(Items.EMERALD));
        ItemFingerprint iron = ItemFingerprint.of(new ItemStack(Items.IRON_INGOT));
        data.record(trade(observed, emerald, 3, iron, 2, 1_000L, true));

        assertNull(data.quote(remote, iron, 2_000L));
        MarketQuote stale = data.quote(observed, iron,
                1_000L + LocalMarketData.FRESH_WINDOW + 1L);
        assertNotNull(stale);
        assertTrue(stale.stale());
    }

    @Test
    void duplicateTransactionIdIsIdempotentWithinItsMarket() {
        LocalMarketData data = new LocalMarketData();
        UUID settlement = UUID.randomUUID();
        ItemFingerprint emerald = ItemFingerprint.of(new ItemStack(Items.EMERALD));
        ItemFingerprint coal = ItemFingerprint.of(new ItemStack(Items.COAL));
        MarketTradeRecord trade = trade(settlement, emerald, 2, coal, 4, 10L, true);

        assertTrue(data.record(trade));
        assertFalse(data.record(trade));
        assertEquals(1, data.recentTrades(settlement, 0L).size());
        assertEquals(trade, MarketTradeRecord.load(trade.save()));
    }

    private static MarketTradeRecord trade(UUID settlement, ItemFingerprint tender,
                                            int tenderCount, ItemFingerprint goods,
                                            int goodsCount, long at, boolean publicTrade) {
        return new MarketTradeRecord(UUID.randomUUID(), settlement, UUID.randomUUID(),
                UUID.randomUUID(), List.of(new MarketBasketEntry(tender, tenderCount)),
                List.of(new MarketBasketEntry(goods, goodsCount)), at, publicTrade);
    }
}
