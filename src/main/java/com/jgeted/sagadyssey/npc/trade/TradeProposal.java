package com.jgeted.sagadyssey.npc.trade;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record TradeProposal(UUID id, UUID sessionId, int version, UUID proposedBy,
                            TradeIntent intent, List<TradeAsset> playerOffers,
                            List<TradeAsset> npcOffers, long proposedAt, long expiresAt) {
    public TradeProposal {
        if (id == null || sessionId == null || proposedBy == null || intent == null
                || playerOffers == null || npcOffers == null
                || playerOffers.isEmpty() || npcOffers.isEmpty() || version < 1
                || expiresAt <= proposedAt) throw new IllegalArgumentException("invalid proposal");
        playerOffers = List.copyOf(playerOffers);
        npcOffers = List.copyOf(npcOffers);
        if (playerOffers.size() > 9 || npcOffers.size() > 9) {
            throw new IllegalArgumentException("proposal side exceeds nine asset lines");
        }
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("SessionId", UUIDUtil.CODEC, sessionId);
        tag.putInt("Version", version);
        tag.store("ProposedBy", UUIDUtil.CODEC, proposedBy);
        tag.putString("Intent", intent.name());
        tag.put("PlayerOffers", saveAssets(playerOffers));
        tag.put("NpcOffers", saveAssets(npcOffers));
        tag.putLong("ProposedAt", proposedAt);
        tag.putLong("ExpiresAt", expiresAt);
        return tag;
    }

    static TradeProposal load(CompoundTag tag) {
        return new TradeProposal(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("SessionId", UUIDUtil.CODEC).orElseThrow(),
                tag.getIntOr("Version", 0), tag.read("ProposedBy", UUIDUtil.CODEC).orElseThrow(),
                TradeIntent.valueOf(tag.getStringOr("Intent", "")),
                loadAssets(MinecraftApi.listOfType(tag, "PlayerOffers", Tag.TAG_COMPOUND)),
                loadAssets(MinecraftApi.listOfType(tag, "NpcOffers", Tag.TAG_COMPOUND)),
                tag.getLongOr("ProposedAt", 0L), tag.getLongOr("ExpiresAt", 0L));
    }

    private static ListTag saveAssets(List<TradeAsset> assets) {
        ListTag list = new ListTag();
        assets.forEach(asset -> list.add(asset.save()));
        return list;
    }

    private static List<TradeAsset> loadAssets(ListTag tags) {
        List<TradeAsset> assets = new ArrayList<>();
        for (int index = 0; index < tags.size(); index++) {
            assets.add(TradeAsset.load(tags.getCompoundOrEmpty(index)));
        }
        return assets;
    }
}
