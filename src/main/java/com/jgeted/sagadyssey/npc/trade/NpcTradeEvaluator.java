package com.jgeted.sagadyssey.npc.trade;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A deliberately local first-pass judgement. It reads this NPC's stock and the concrete proposal;
 * it is not a global market price table. Phase 8 will add observed local transactions and routes.
 */
public final class NpcTradeEvaluator {
    private NpcTradeEvaluator() {}

    public static TradeEvaluation evaluate(TradeProposal proposal, Container npcInventory) {
        return evaluate(proposal, npcInventory,
                TradeEvaluationContext.unobserved(proposal.proposedAt()));
    }

    public static TradeEvaluation evaluate(TradeProposal proposal, Container npcInventory,
                                           TradeEvaluationContext context) {
        Valuation offered = value(proposal.playerOffers(), npcInventory, context, true);
        Valuation surrendered = value(proposal.npcOffers(), npcInventory, context, false);
        boolean wantsSomething = proposal.playerOffers().stream()
                .anyMatch(asset -> usefulness(asset, npcInventory) >= 3);
        if (!wantsSomething) {
            return new TradeEvaluation(TradeEvaluation.Outcome.REFUSE,
                    "这些东西目前解决不了我的需要。");
        }
        long required = surrendered.totalMilli;
        required = required * context.reputationPriceBasisPoints() / 10_000L;
        // Trust narrows the reserve spread, while distrust widens it. It never creates free goods.
        int relationshipAdjustment = -context.relationshipStrength() * 8;
        required = required * (10_000L + relationshipAdjustment) / 10_000L;
        if (offered.totalMilli < required) {
            return new TradeEvaluation(TradeEvaluation.Outcome.COUNTER,
                    offered.observedSamples + surrendered.observedSamples > 0
                            ? "本地最近的实际成交和我手里的余量都不支持这个报价；请增加货物或绿宝石。"
                            : "我会失去更难替代的东西；请增加我需要的货物或绿宝石。");
        }
        return new TradeEvaluation(TradeEvaluation.Outcome.ACCEPT,
                offered.observedSamples + surrendered.observedSamples > 0
                        ? "这个报价符合本地近期成交，也留下了我需要的库存。"
                        : "这些实物我用得上，数量也可以当场核对。");
    }

    private static Valuation value(java.util.List<TradeAsset> assets, Container inventory,
                                   TradeEvaluationContext context, boolean incoming) {
        long total = 0L;
        int samples = 0;
        for (TradeAsset asset : assets) {
            int unit = intrinsicValueMilli(asset);
            if (context.localMarket() != null && context.settlementId() != null) {
                var quote = context.localMarket().quote(context.settlementId(),
                        asset.fingerprint(), context.gameTime());
                if (quote != null) {
                    unit = quote.stale() ? (unit + quote.unitEmeraldMilli()) / 2
                            : quote.unitEmeraldMilli();
                    samples += quote.sampleCount();
                }
            }
            if (!incoming) {
                long landed = (long) unit * (10_000L + context.routeRiskBasisPoints()) / 10_000L
                        + context.transportCostPerUnitEmeraldMilli();
                unit = (int) Math.min(Integer.MAX_VALUE, landed);
                if (context.merchantKnowledge() != null
                        && context.merchantId() != null && context.settlementId() != null) {
                    int remoteNet = context.merchantKnowledge().bestRemoteNetPrice(
                            context.merchantId(), context.settlementId(), asset.fingerprint(),
                            context.gameTime(), context.transportCostPerUnitEmeraldMilli(),
                            context.routeRiskBasisPoints());
                    unit = Math.max(unit, remoteNet);
                }
            }
            int held = held(asset, inventory);
            int pressure = incoming ? incomingPressure(asset, held) : outgoingPressure(asset, held);
            total += (long) asset.quantity() * unit * pressure / 100L;
        }
        return new Valuation(total, samples);
    }

    private static int intrinsicValueMilli(TradeAsset asset) {
        Identifier id = Identifier.tryParse(asset.fingerprint().itemId());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return 500;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == Items.EMERALD) return 1_000;
        ItemStack sample = new ItemStack(item);
        if (com.jgeted.sagadyssey.core.MinecraftApi.isArmor(new ItemStack(item))) return 5_000;
        if (com.jgeted.sagadyssey.core.MinecraftApi.isDigger(new ItemStack(item)) || com.jgeted.sagadyssey.core.MinecraftApi.isSword(new ItemStack(item)) || item instanceof BowItem) return 4_000;
        if (item == Items.IRON_INGOT) return 1_500;
        if (item == Items.COAL || item == Items.CHARCOAL) return 750;
        if (sample.has(net.minecraft.core.component.DataComponents.FOOD)) return 600;
        return 1_000;
    }

    private static int held(TradeAsset asset, Container inventory) {
        int held = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (asset.fingerprint().matches(inventory.getItem(slot))) {
                held += inventory.getItem(slot).getCount();
            }
        }
        return held;
    }

    private static int incomingPressure(TradeAsset asset, int held) {
        Identifier id = Identifier.tryParse(asset.fingerprint().itemId());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return 100;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == Items.EMERALD) return 100;
        ItemStack sample = new ItemStack(item);
        if (sample.has(net.minecraft.core.component.DataComponents.FOOD)) return held < 6 ? 150 : 80;
        if (item == Items.COAL || item == Items.CHARCOAL || item == Items.IRON_INGOT) {
            return held < 12 ? 140 : 85;
        }
        return held == 0 ? 120 : 90;
    }

    private static int outgoingPressure(TradeAsset asset, int held) {
        Identifier id = Identifier.tryParse(asset.fingerprint().itemId());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return 110;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == Items.EMERALD) return held <= asset.quantity() + 3 ? 120 : 100;
        ItemStack sample = new ItemStack(item);
        int reserve = sample.has(net.minecraft.core.component.DataComponents.FOOD) ? 3
                : item == Items.COAL || item == Items.CHARCOAL ? 4 : 0;
        return held - asset.quantity() <= reserve ? 140 : held > asset.quantity() * 3 ? 90 : 110;
    }

    static int usefulness(TradeAsset asset, Container inventory) {
        Identifier id = Identifier.tryParse(asset.fingerprint().itemId());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return 0;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == Items.EMERALD) return 8;
        ItemStack sample = new ItemStack(item);
        int held = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) held += inventory.getItem(slot).getCount();
        }
        if (sample.has(net.minecraft.core.component.DataComponents.FOOD)) return held < 6 ? 6 : 2;
        if (item == Items.COAL || item == Items.CHARCOAL || item == Items.IRON_INGOT) {
            return held < 12 ? 5 : 2;
        }
        return held == 0 ? 3 : 1;
    }

    private record Valuation(long totalMilli, int observedSamples) { }
}
