package com.jgeted.sagadyssey.social.market;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MerchantMarketKnowledgeTest {
    @Test
    void merchantLearnsOnlyVisitedMarketsAndCalculatesNetOpportunity() {
        LocalMarketData markets = new LocalMarketData();
        MerchantMarketKnowledgeData knowledge = new MerchantMarketKnowledgeData();
        UUID merchant = UUID.randomUUID();
        UUID cheapMarket = UUID.randomUUID();
        UUID expensiveMarket = UUID.randomUUID();
        ItemFingerprint emerald = ItemFingerprint.of(new ItemStack(Items.EMERALD));
        ItemFingerprint coal = ItemFingerprint.of(new ItemStack(Items.COAL));
        markets.record(trade(cheapMarket, emerald, 4, coal, 8, 100L));
        markets.record(trade(expensiveMarket, emerald, 12, coal, 8, 200L));
        SimpleContainer bag = new SimpleContainer(new ItemStack(Items.COAL, 8));

        assertEquals(1, knowledge.observe(merchant, cheapMarket, bag, markets, 300L));
        assertEquals(0, knowledge.bestRemoteNetPrice(merchant, cheapMarket,
                coal, 300L, 100, 500));
        assertEquals(1, knowledge.observe(merchant, expensiveMarket, bag, markets, 400L));

        // 1.5 emerald gross, then 5% risk and 0.1 emerald transport = 1.325 net.
        assertEquals(1_325, knowledge.bestRemoteNetPrice(merchant, cheapMarket,
                coal, 400L, 100, 500));
        assertEquals(2, knowledge.memories(merchant).size());
    }

    @Test
    void staleTravelKnowledgeCannotDriveArbitrageForever() {
        MerchantMarketKnowledgeData knowledge = new MerchantMarketKnowledgeData();
        UUID merchant = UUID.randomUUID();
        UUID here = UUID.randomUUID();
        ItemFingerprint bread = ItemFingerprint.of(new ItemStack(Items.BREAD));
        knowledge.remember(new MerchantPriceMemory(merchant, UUID.randomUUID(), bread,
                2_000, 2, 10L));

        assertEquals(0, knowledge.bestRemoteNetPrice(merchant, here, bread,
                10L + LocalMarketData.QUOTE_WINDOW + 1L, 0, 0));
    }

    private static MarketTradeRecord trade(UUID settlement, ItemFingerprint emerald,
                                            int emeralds, ItemFingerprint goods,
                                            int quantity, long at) {
        return new MarketTradeRecord(UUID.randomUUID(), settlement, UUID.randomUUID(),
                UUID.randomUUID(), List.of(new MarketBasketEntry(emerald, emeralds)),
                List.of(new MarketBasketEntry(goods, quantity)), at, true);
    }
}
