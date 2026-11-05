package com.jgeted.sagadyssey.social.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Public fact of a completed local exchange; baskets are facts, not generated shop recipes. */
public record MarketTradeRecord(UUID tradeId, UUID settlementId, UUID firstParty,
                                UUID secondParty, List<MarketBasketEntry> firstToSecond,
                                List<MarketBasketEntry> secondToFirst,
                                long completedAt, boolean publiclyObserved) {
    public MarketTradeRecord {
        if (tradeId == null || settlementId == null || firstParty == null || secondParty == null
                || firstParty.equals(secondParty) || firstToSecond == null || secondToFirst == null
                || firstToSecond.isEmpty() || secondToFirst.isEmpty()) {
            throw new IllegalArgumentException("invalid market trade record");
        }
        firstToSecond = List.copyOf(firstToSecond);
        secondToFirst = List.copyOf(secondToFirst);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Trade", tradeId);
        tag.putUUID("Settlement", settlementId);
        tag.putUUID("FirstParty", firstParty);
        tag.putUUID("SecondParty", secondParty);
        tag.put("FirstToSecond", saveBasket(firstToSecond));
        tag.put("SecondToFirst", saveBasket(secondToFirst));
        tag.putLong("CompletedAt", completedAt);
        tag.putBoolean("Public", publiclyObserved);
        return tag;
    }

    static MarketTradeRecord load(CompoundTag tag) {
        return new MarketTradeRecord(tag.getUUID("Trade"), tag.getUUID("Settlement"),
                tag.getUUID("FirstParty"), tag.getUUID("SecondParty"),
                loadBasket(tag.getList("FirstToSecond", Tag.TAG_COMPOUND)),
                loadBasket(tag.getList("SecondToFirst", Tag.TAG_COMPOUND)),
                tag.getLong("CompletedAt"), tag.getBoolean("Public"));
    }

    private static ListTag saveBasket(List<MarketBasketEntry> basket) {
        ListTag tags = new ListTag();
        basket.forEach(entry -> tags.add(entry.save()));
        return tags;
    }

    private static List<MarketBasketEntry> loadBasket(ListTag tags) {
        List<MarketBasketEntry> result = new ArrayList<>();
        for (int index = 0; index < tags.size(); index++) {
            result.add(MarketBasketEntry.load(tags.getCompound(index)));
        }
        return result;
    }
}
