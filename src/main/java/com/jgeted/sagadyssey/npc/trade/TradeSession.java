package com.jgeted.sagadyssey.npc.trade;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Stable identities and revisions replace client-selected recipe indexes. */
public record TradeSession(UUID id, UUID playerId, UUID npcId, UUID playerContainerId,
                           UUID npcContainerId, TradeSessionStatus status, int revision,
                           long openedAt, long expiresAt, TradeProposal proposal,
                           String explanation) {
    public TradeSession {
        if (id == null || playerId == null || npcId == null || playerContainerId == null
                || npcContainerId == null || status == null || revision < 0
                || expiresAt <= openedAt) throw new IllegalArgumentException("invalid trade session");
        explanation = explanation == null ? "" : explanation;
    }

    public static TradeSession open(UUID playerId, UUID npcId, UUID playerContainerId,
                                    UUID npcContainerId, long now, long lifetime) {
        return new TradeSession(UUID.randomUUID(), playerId, npcId, playerContainerId,
                npcContainerId, TradeSessionStatus.OPEN, 0, now, now + lifetime,
                null, "等待双方提出实物交换条件");
    }

    public String reservationToken() { return "trade:" + id; }

    public TradeSession withProposal(TradeProposal next, TradeSessionStatus nextStatus,
                                     String reason) {
        if (status.terminal()) throw new IllegalStateException("terminal trade session");
        if (!id.equals(next.sessionId()) || next.version() != revision + 1) {
            throw new IllegalArgumentException("stale or foreign proposal");
        }
        return new TradeSession(id, playerId, npcId, playerContainerId, npcContainerId,
                nextStatus, revision + 1, openedAt, expiresAt, next, reason);
    }

    public TradeSession finish(TradeSessionStatus terminalStatus, String reason) {
        if (!terminalStatus.terminal()) throw new IllegalArgumentException("status is not terminal");
        return new TradeSession(id, playerId, npcId, playerContainerId, npcContainerId,
                terminalStatus, revision + 1, openedAt, expiresAt, proposal, reason);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("PlayerId", playerId);
        tag.putUUID("NpcId", npcId);
        tag.putUUID("PlayerContainerId", playerContainerId);
        tag.putUUID("NpcContainerId", npcContainerId);
        tag.putString("Status", status.name());
        tag.putInt("Revision", revision);
        tag.putLong("OpenedAt", openedAt);
        tag.putLong("ExpiresAt", expiresAt);
        if (proposal != null) tag.put("Proposal", proposal.save());
        tag.putString("Explanation", explanation);
        return tag;
    }

    static TradeSession load(CompoundTag tag) {
        return new TradeSession(tag.getUUID("Id"), tag.getUUID("PlayerId"),
                tag.getUUID("NpcId"), tag.getUUID("PlayerContainerId"),
                tag.getUUID("NpcContainerId"), TradeSessionStatus.valueOf(tag.getString("Status")),
                tag.getInt("Revision"), tag.getLong("OpenedAt"), tag.getLong("ExpiresAt"),
                tag.contains("Proposal") ? TradeProposal.load(tag.getCompound("Proposal")) : null,
                tag.getString("Explanation"));
    }
}
