package com.jgeted.sagadyssey.npc.trade;

import com.jgeted.sagadyssey.social.market.LocalMarketData;
import com.jgeted.sagadyssey.social.market.MarketBasketEntry;
import com.jgeted.sagadyssey.social.market.MarketTradeRecord;
import com.jgeted.sagadyssey.social.market.MerchantMarketKnowledgeData;
import com.jgeted.sagadyssey.social.market.MerchantPriceMemory;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NpcTradeEvaluatorMarketTest {
    @Test
    void localCompletedTradesChangeReserveValueWithoutAffectingRemoteMarket() {
        UUID player = UUID.randomUUID();
        UUID npc = UUID.randomUUID();
        UUID local = UUID.randomUUID();
        UUID remote = UUID.randomUUID();
        ItemFingerprint emerald = ItemFingerprint.of(new ItemStack(Items.EMERALD));
        ItemFingerprint hoe = ItemFingerprint.of(new ItemStack(Items.IRON_HOE));
        LocalMarketData markets = new LocalMarketData();
        markets.record(new MarketTradeRecord(UUID.randomUUID(), local, UUID.randomUUID(),
                UUID.randomUUID(), List.of(new MarketBasketEntry(emerald, 10)),
                List.of(new MarketBasketEntry(hoe, 1)), 5L, true));
        SimpleContainer npcBag = new SimpleContainer(2);
        npcBag.setItem(0, new ItemStack(Items.IRON_HOE));
        TradeProposal proposal = new TradeProposal(UUID.randomUUID(), UUID.randomUUID(), 1,
                player, TradeIntent.SPOT_PURCHASE,
                List.of(new TradeAsset(emerald, 6, "绿宝石")),
                List.of(new TradeAsset(hoe, 1, "铁锄")), 10L, 100L);

        TradeEvaluation remoteResult = NpcTradeEvaluator.evaluate(proposal, npcBag,
                new TradeEvaluationContext(remote, markets, 10L, 0, 0, 0));
        TradeEvaluation localResult = NpcTradeEvaluator.evaluate(proposal, npcBag,
                new TradeEvaluationContext(local, markets, 10L, 0, 0, 0));

        assertEquals(TradeEvaluation.Outcome.ACCEPT, remoteResult.outcome());
        assertEquals(TradeEvaluation.Outcome.COUNTER, localResult.outcome());
    }

    @Test
    void transportRiskAndDistrustCanTurnAMarginalOfferIntoCounteroffer() {
        ItemFingerprint emerald = ItemFingerprint.of(new ItemStack(Items.EMERALD));
        ItemFingerprint hoe = ItemFingerprint.of(new ItemStack(Items.IRON_HOE));
        SimpleContainer npcBag = new SimpleContainer(1);
        npcBag.setItem(0, new ItemStack(Items.IRON_HOE));
        TradeProposal proposal = new TradeProposal(UUID.randomUUID(), UUID.randomUUID(), 1,
                UUID.randomUUID(), TradeIntent.SPOT_PURCHASE,
                List.of(new TradeAsset(emerald, 6, "绿宝石")),
                List.of(new TradeAsset(hoe, 1, "铁锄")), 10L, 100L);

        assertEquals(TradeEvaluation.Outcome.ACCEPT,
                NpcTradeEvaluator.evaluate(proposal, npcBag,
                        TradeEvaluationContext.unobserved(10L)).outcome());
        assertEquals(TradeEvaluation.Outcome.COUNTER,
                NpcTradeEvaluator.evaluate(proposal, npcBag,
                        new TradeEvaluationContext(null, null, 10L, -50,
                                500, 1_000)).outcome());
    }

    @Test
    void coldFactionMarkupChangesTheWholeBasketReserveThreshold() {
        ItemFingerprint emerald = ItemFingerprint.of(new ItemStack(Items.EMERALD));
        ItemFingerprint bread = ItemFingerprint.of(new ItemStack(Items.BREAD));
        SimpleContainer npcBag = new SimpleContainer(new ItemStack(Items.BREAD, 4));
        TradeProposal proposal = proposal(emerald, 1, bread, 1);

        assertEquals(TradeEvaluation.Outcome.ACCEPT,
                NpcTradeEvaluator.evaluate(proposal, npcBag,
                        new TradeEvaluationContext(null, null, 10L, 0,
                                0, 0, 10_000)).outcome());
        assertEquals(TradeEvaluation.Outcome.COUNTER,
                NpcTradeEvaluator.evaluate(proposal, npcBag,
                        new TradeEvaluationContext(null, null, 10L, 0,
                                0, 0, 12_000)).outcome());
    }

    @Test
    void travellingMerchantKeepsGoodsForABetterRememberedRemoteMarket() {
        UUID merchant = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        ItemFingerprint emerald = ItemFingerprint.of(new ItemStack(Items.EMERALD));
        ItemFingerprint coal = ItemFingerprint.of(new ItemStack(Items.COAL));
        MerchantMarketKnowledgeData knowledge = new MerchantMarketKnowledgeData();
        knowledge.remember(new MerchantPriceMemory(merchant, UUID.randomUUID(), coal,
                3_000, 3, 10L));
        SimpleContainer npcBag = new SimpleContainer(new ItemStack(Items.COAL, 4));
        TradeProposal proposal = new TradeProposal(UUID.randomUUID(), UUID.randomUUID(), 1,
                UUID.randomUUID(), TradeIntent.SPOT_PURCHASE,
                List.of(new TradeAsset(emerald, 6, "绿宝石")),
                List.of(new TradeAsset(coal, 4, "煤炭")), 20L, 100L);

        assertEquals(TradeEvaluation.Outcome.ACCEPT,
                NpcTradeEvaluator.evaluate(proposal, npcBag,
                        TradeEvaluationContext.unobserved(20L)).outcome());
        assertEquals(TradeEvaluation.Outcome.COUNTER,
                NpcTradeEvaluator.evaluate(proposal, npcBag,
                        new TradeEvaluationContext(current, null, 20L, 0,
                                100, 500, merchant, knowledge)).outcome());
    }

    @Test
    void transportCostRemainsPerUnitWhenBuyerRequestsMoreGoods() {
        ItemFingerprint emerald = ItemFingerprint.of(new ItemStack(Items.EMERALD));
        ItemFingerprint bread = ItemFingerprint.of(new ItemStack(Items.BREAD));
        SimpleContainer npcBag = new SimpleContainer(new ItemStack(Items.BREAD, 64));
        TradeEvaluationContext context = new TradeEvaluationContext(null, null,
                10L, 0, 500, 0);

        TradeProposal oneForOne = proposal(emerald, 1, bread, 1);
        TradeProposal threeForFour = proposal(emerald, 3, bread, 4);
        TradeProposal fourForFour = proposal(emerald, 4, bread, 4);

        assertEquals(TradeEvaluation.Outcome.ACCEPT,
                NpcTradeEvaluator.evaluate(oneForOne, npcBag, context).outcome());
        assertEquals(TradeEvaluation.Outcome.COUNTER,
                NpcTradeEvaluator.evaluate(threeForFour, npcBag, context).outcome(),
                "a larger order must not dilute one per-unit route cost into one flat fee");
        assertEquals(TradeEvaluation.Outcome.ACCEPT,
                NpcTradeEvaluator.evaluate(fourForFour, npcBag, context).outcome());
    }

    private static TradeProposal proposal(ItemFingerprint offered, int offeredQuantity,
                                          ItemFingerprint requested, int requestedQuantity) {
        return new TradeProposal(UUID.randomUUID(), UUID.randomUUID(), 1,
                UUID.randomUUID(), TradeIntent.SPOT_PURCHASE,
                List.of(new TradeAsset(offered, offeredQuantity, "绿宝石")),
                List.of(new TradeAsset(requested, requestedQuantity, "面包")),
                10L, 100L);
    }
}
