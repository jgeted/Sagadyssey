package com.jgeted.sagadyssey.npc.trade;

import com.jgeted.sagadyssey.social.market.LocalMarketData;
import com.jgeted.sagadyssey.social.market.MerchantMarketKnowledgeData;

import javax.annotation.Nullable;
import java.util.UUID;

/** Only local observations and this relationship may influence one NPC's reserve judgement. */
public record TradeEvaluationContext(@Nullable UUID settlementId,
                                     @Nullable LocalMarketData localMarket,
                                     long gameTime, int relationshipStrength,
                                     int transportCostPerUnitEmeraldMilli,
                                     int routeRiskBasisPoints,
                                     int reputationPriceBasisPoints,
                                     @Nullable UUID merchantId,
                                     @Nullable MerchantMarketKnowledgeData merchantKnowledge) {
    public TradeEvaluationContext(@Nullable UUID settlementId, @Nullable LocalMarketData localMarket,
                                  long gameTime, int relationshipStrength,
                                  int transportCostPerUnitEmeraldMilli, int routeRiskBasisPoints) {
        this(settlementId, localMarket, gameTime, relationshipStrength,
                transportCostPerUnitEmeraldMilli, routeRiskBasisPoints, 10_000, null, null);
    }
    public TradeEvaluationContext(@Nullable UUID settlementId, @Nullable LocalMarketData localMarket,
                                  long gameTime, int relationshipStrength,
                                  int transportCostPerUnitEmeraldMilli, int routeRiskBasisPoints,
                                  @Nullable UUID merchantId,
                                  @Nullable MerchantMarketKnowledgeData merchantKnowledge) {
        this(settlementId, localMarket, gameTime, relationshipStrength,
                transportCostPerUnitEmeraldMilli, routeRiskBasisPoints, 10_000,
                merchantId, merchantKnowledge);
    }
    public TradeEvaluationContext(@Nullable UUID settlementId, @Nullable LocalMarketData localMarket,
                                  long gameTime, int relationshipStrength,
                                  int transportCostPerUnitEmeraldMilli, int routeRiskBasisPoints,
                                  int reputationPriceBasisPoints) {
        this(settlementId, localMarket, gameTime, relationshipStrength,
                transportCostPerUnitEmeraldMilli, routeRiskBasisPoints,
                reputationPriceBasisPoints, null, null);
    }
    public TradeEvaluationContext {
        relationshipStrength = Math.max(-100, Math.min(100, relationshipStrength));
        transportCostPerUnitEmeraldMilli = Math.max(0, transportCostPerUnitEmeraldMilli);
        routeRiskBasisPoints = Math.max(0, Math.min(5_000, routeRiskBasisPoints));
        reputationPriceBasisPoints = Math.max(5_000,
                Math.min(20_000, reputationPriceBasisPoints));
    }

    public static TradeEvaluationContext unobserved(long gameTime) {
        return new TradeEvaluationContext(null, null, gameTime, 0, 0, 0);
    }
}
