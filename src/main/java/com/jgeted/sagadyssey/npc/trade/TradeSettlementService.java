package com.jgeted.sagadyssey.npc.trade;

import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ExpectedRevision;
import com.jgeted.sagadyssey.worldsim.transfer.TransferEndpoint;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLeg;
import com.jgeted.sagadyssey.worldsim.transfer.TransferReceipt;
import com.jgeted.sagadyssey.worldsim.transfer.TransferRequest;
import com.jgeted.sagadyssey.worldsim.transfer.TransferService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Commits both sides as one multi-leg inventory transaction or changes nothing. */
public final class TradeSettlementService {
    private TradeSettlementService() {}

    public static TransferReceipt commit(TradeSession session,
                                         Map<UUID, TransferEndpoint> endpoints,
                                         RightsStateData rights, TransferLedgerData ledger,
                                         long now) {
        if (session.status() != TradeSessionStatus.ACCEPTED || session.proposal() == null) {
            throw new IllegalStateException("trade proposal is not accepted");
        }
        TradeProposal proposal = session.proposal();
        List<TransferLeg> legs = new ArrayList<>();
        proposal.playerOffers().forEach(asset -> legs.add(new TransferLeg(
                session.playerContainerId(), session.npcContainerId(),
                asset.fingerprint(), asset.quantity())));
        proposal.npcOffers().forEach(asset -> legs.add(new TransferLeg(
                session.npcContainerId(), session.playerContainerId(),
                asset.fingerprint(), asset.quantity())));
        SubjectRef player = SubjectRef.character(session.playerId());
        SubjectRef npc = SubjectRef.character(session.npcId());
        TransferRequest request = new TransferRequest(proposal.id(), player,
                proposal.intent() == TradeIntent.SPOT_PURCHASE
                        ? TransferIntent.PAYMENT : TransferIntent.DELIVERY,
                legs, List.of(new ExpectedRevision(session.playerContainerId(),
                        requiredRevision(rights, session.playerContainerId())),
                        new ExpectedRevision(session.npcContainerId(),
                                requiredRevision(rights, session.npcContainerId()))),
                session.reservationToken(), now,
                Map.of(session.playerContainerId(), player, session.npcContainerId(), npc));
        return new TransferService(rights, ledger).submit(request, endpoints, now);
    }

    private static long requiredRevision(RightsStateData rights, UUID containerId) {
        var context = rights.context(containerId);
        if (context == null) throw new IllegalStateException("trade container has no rights context");
        return context.revision();
    }
}
