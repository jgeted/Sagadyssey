package com.jgeted.sagadyssey.npc.trade;

import com.jgeted.sagadyssey.worldsim.transfer.TransferEndpoint;
import com.jgeted.sagadyssey.worldsim.rights.AssetReservation;
import com.jgeted.sagadyssey.worldsim.rights.AssetRight;
import com.jgeted.sagadyssey.worldsim.rights.RightsService;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.RightsVerdict;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Validates stable revisions, physical availability and reservations before NPC judgement. */
public final class TradeNegotiationService {
    private TradeNegotiationService() {}

    public static TradeSession propose(TradeSessionData data, UUID sessionId, int expectedRevision,
            UUID playerId, TradeIntent intent, List<TradeAsset> playerOffers,
            List<TradeAsset> npcOffers, Map<UUID, TransferEndpoint> endpoints, long now) {
        return propose(data, sessionId, expectedRevision, playerId, intent, playerOffers,
                npcOffers, endpoints, now, TradeEvaluationContext.unobserved(now), null);
    }

    public static TradeSession propose(TradeSessionData data, UUID sessionId, int expectedRevision,
            UUID playerId, TradeIntent intent, List<TradeAsset> playerOffers,
            List<TradeAsset> npcOffers, Map<UUID, TransferEndpoint> endpoints, long now,
            TradeEvaluationContext evaluationContext) {
        return propose(data, sessionId, expectedRevision, playerId, intent, playerOffers,
                npcOffers, endpoints, now, evaluationContext, null);
    }

    public static TradeSession propose(TradeSessionData data, UUID sessionId, int expectedRevision,
            UUID playerId, TradeIntent intent, List<TradeAsset> playerOffers,
            List<TradeAsset> npcOffers, Map<UUID, TransferEndpoint> endpoints, long now,
            TradeEvaluationContext evaluationContext, RightsStateData rights) {
        data.expire(now);
        TradeSession session = data.session(sessionId);
        if (session == null || session.status().terminal() || now >= session.expiresAt()) {
            throw new IllegalStateException("交易会话已经失效");
        }
        if (!session.playerId().equals(playerId) || session.revision() != expectedRevision) {
            throw new IllegalStateException("报价版本已变化，请重新核对");
        }
        requireUnique(playerOffers);
        requireUnique(npcOffers);
        requireDifferentSides(playerOffers, npcOffers);
        ensureAvailable(data, session, session.playerContainerId(), playerOffers,
                endpoints.get(session.playerContainerId()), now);
        ensureAvailable(data, session, session.npcContainerId(), npcOffers,
                endpoints.get(session.npcContainerId()), now);
        TradeProposal proposal = new TradeProposal(UUID.randomUUID(), session.id(),
                session.revision() + 1, playerId, intent, playerOffers, npcOffers,
                now, Math.min(session.expiresAt(), now + 600L));
        TradeEvaluation evaluation = NpcTradeEvaluator.evaluate(proposal,
                endpoints.get(session.npcContainerId()).container(), evaluationContext);
        TradeSessionStatus status = switch (evaluation.outcome()) {
            case ACCEPT -> TradeSessionStatus.ACCEPTED;
            case COUNTER -> TradeSessionStatus.COUNTERED;
            case REFUSE -> TradeSessionStatus.OPEN;
        };
        if (rights != null) synchronizeGlobalReservations(rights, session, proposal,
                status, endpoints, now);
        TradeSession updated = session.withProposal(proposal, status, evaluation.explanation());
        data.put(updated);
        return updated;
    }

    private static void requireUnique(List<TradeAsset> assets) {
        HashSet<com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint> seen = new HashSet<>();
        for (TradeAsset asset : assets) {
            if (!seen.add(asset.fingerprint())) throw new IllegalArgumentException("同类物品必须合并为一行");
        }
    }

    private static void requireDifferentSides(List<TradeAsset> playerOffers,
                                              List<TradeAsset> npcOffers) {
        HashSet<com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint> playerItems =
                new HashSet<>();
        playerOffers.forEach(asset -> playerItems.add(asset.fingerprint()));
        if (npcOffers.stream().anyMatch(asset -> playerItems.contains(asset.fingerprint()))) {
            throw new IllegalArgumentException("同一种物品无需双方来回交换；请只提出实际的净交换");
        }
    }

    private static void ensureAvailable(TradeSessionData data, TradeSession session,
            UUID containerId, List<TradeAsset> assets, TransferEndpoint endpoint, long now) {
        if (endpoint == null) throw new IllegalStateException("交易一方的物品栏当前不可用");
        for (TradeAsset asset : assets) {
            int physical = 0;
            for (int slot = 0; slot < endpoint.container().getContainerSize(); slot++) {
                var stack = endpoint.container().getItem(slot);
                if (asset.fingerprint().matches(stack)) physical += stack.getCount();
            }
            int reservedElsewhere = data.reserved(containerId, asset.fingerprint(),
                    session.id(), now);
            if (physical - reservedElsewhere < asset.quantity()) {
                throw new IllegalStateException("报价中的物品已售出、被预留或数量发生变化");
            }
        }
    }

    private static void synchronizeGlobalReservations(RightsStateData rights, TradeSession session,
            TradeProposal proposal, TradeSessionStatus status,
            Map<UUID, TransferEndpoint> endpoints, long now) {
        var snapshot = rights.snapshotFor(java.util.Set.of(session.playerContainerId(),
                session.npcContainerId()));
        rights.releaseReservations(session.reservationToken());
        if (status == TradeSessionStatus.OPEN) return;
        try {
            reserveSide(rights, session, proposal, proposal.playerOffers(), session.playerContainerId(),
                    SubjectRef.character(session.playerId()), endpoints, now);
            reserveSide(rights, session, proposal, proposal.npcOffers(), session.npcContainerId(),
                    SubjectRef.character(session.npcId()), endpoints, now);
        } catch (RuntimeException exception) {
            rights.restore(snapshot);
            throw exception;
        }
    }

    private static void reserveSide(RightsStateData rights, TradeSession session,
            TradeProposal proposal, List<TradeAsset> assets, UUID containerId, SubjectRef owner,
            Map<UUID, TransferEndpoint> endpoints, long now) {
        TransferEndpoint endpoint = endpoints.get(containerId);
        if (endpoint == null) throw new IllegalStateException("交易容器当前不可用");
        RightsService resolver = new RightsService(rights);
        for (TradeAsset asset : assets) {
            int transferable = 0;
            for (int slot = 0; slot < endpoint.container().getContainerSize(); slot++) {
                var stack = endpoint.container().getItem(slot);
                if (asset.fingerprint().matches(stack)
                        && resolver.can(owner, AssetRight.TRANSFER, containerId, slot,
                        asset.fingerprint(), now).status() == RightsVerdict.Status.ALLOW) {
                    transferable += stack.getCount();
                }
            }
            AssetReservation reservation = new AssetReservation(UUID.randomUUID(), containerId,
                    asset.fingerprint(), asset.quantity(), owner, session.reservationToken(),
                    "trade_proposal:" + proposal.id(), now,
                    Math.min(session.expiresAt(), proposal.expiresAt()));
            if (!rights.reserve(reservation, transferable, now)) {
                throw new IllegalStateException("报价物品已被生产、消费或另一项承诺预留");
            }
        }
    }
}
