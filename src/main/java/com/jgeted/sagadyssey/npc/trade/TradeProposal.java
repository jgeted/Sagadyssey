package com.jgeted.sagadyssey.npc.trade;

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
        tag.putUUID("Id", id);
        tag.putUUID("SessionId", sessionId);
        tag.putInt("Version", version);
        tag.putUUID("ProposedBy", proposedBy);
        tag.putString("Intent", intent.name());
        tag.put("PlayerOffers", saveAssets(playerOffers));
        tag.put("NpcOffers", saveAssets(npcOffers));
        tag.putLong("ProposedAt", proposedAt);
        tag.putLong("ExpiresAt", expiresAt);
        return tag;
    }

    static TradeProposal load(CompoundTag tag) {
        return new TradeProposal(tag.getUUID("Id"), tag.getUUID("SessionId"),
                tag.getInt("Version"), tag.getUUID("ProposedBy"),
                TradeIntent.valueOf(tag.getString("Intent")),
                loadAssets(tag.getList("PlayerOffers", Tag.TAG_COMPOUND)),
                loadAssets(tag.getList("NpcOffers", Tag.TAG_COMPOUND)),
                tag.getLong("ProposedAt"), tag.getLong("ExpiresAt"));
    }

    private static ListTag saveAssets(List<TradeAsset> assets) {
        ListTag list = new ListTag();
        assets.forEach(asset -> list.add(asset.save()));
        return list;
    }

    private static List<TradeAsset> loadAssets(ListTag tags) {
        List<TradeAsset> assets = new ArrayList<>();
        for (int index = 0; index < tags.size(); index++) {
            assets.add(TradeAsset.load(tags.getCompound(index)));
        }
        return assets;
    }
}
