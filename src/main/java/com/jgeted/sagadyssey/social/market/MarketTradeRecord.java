package com.jgeted.sagadyssey.social.market;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

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
        tag.store("Trade", UUIDUtil.CODEC, tradeId);
        tag.store("Settlement", UUIDUtil.CODEC, settlementId);
        tag.store("FirstParty", UUIDUtil.CODEC, firstParty);
        tag.store("SecondParty", UUIDUtil.CODEC, secondParty);
        tag.put("FirstToSecond", saveBasket(firstToSecond));
        tag.put("SecondToFirst", saveBasket(secondToFirst));
        tag.putLong("CompletedAt", completedAt);
        tag.putBoolean("Public", publiclyObserved);
        return tag;
    }

    static MarketTradeRecord load(CompoundTag tag) {
        return new MarketTradeRecord(tag.read("Trade", UUIDUtil.CODEC).orElseThrow(), tag.read("Settlement", UUIDUtil.CODEC).orElseThrow(),
                tag.read("FirstParty", UUIDUtil.CODEC).orElseThrow(), tag.read("SecondParty", UUIDUtil.CODEC).orElseThrow(),
                loadBasket(MinecraftApi.listOfType(tag, "FirstToSecond", Tag.TAG_COMPOUND)),
                loadBasket(MinecraftApi.listOfType(tag, "SecondToFirst", Tag.TAG_COMPOUND)),
                tag.getLongOr("CompletedAt", 0L), tag.getBooleanOr("Public", false));
    }

    private static ListTag saveBasket(List<MarketBasketEntry> basket) {
        ListTag tags = new ListTag();
        basket.forEach(entry -> tags.add(entry.save()));
        return tags;
    }

    private static List<MarketBasketEntry> loadBasket(ListTag tags) {
        List<MarketBasketEntry> result = new ArrayList<>();
        for (int index = 0; index < tags.size(); index++) {
            result.add(MarketBasketEntry.load(tags.getCompoundOrEmpty(index)));
        }
        return result;
    }
}
