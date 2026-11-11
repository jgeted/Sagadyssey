package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.network.NetworkRateLimiter;
import com.jgeted.sagadyssey.core.network.NetworkStrings;
import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.faction.FactionAttachments;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.trade.NpcTradeAccessPolicy;
import com.jgeted.sagadyssey.npc.trade.TradeAsset;
import com.jgeted.sagadyssey.npc.trade.TradeEvaluationContext;
import com.jgeted.sagadyssey.npc.trade.TradeIntent;
import com.jgeted.sagadyssey.npc.trade.TradeNegotiationService;
import com.jgeted.sagadyssey.npc.trade.TradeSession;
import com.jgeted.sagadyssey.npc.trade.TradeSessionData;
import com.jgeted.sagadyssey.npc.trade.TradeSessionStatus;
import com.jgeted.sagadyssey.npc.trade.TradeSettlementService;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcLifeRecord;
import com.jgeted.sagadyssey.social.market.LocalMarketData;
import com.jgeted.sagadyssey.social.market.MarketBasketEntry;
import com.jgeted.sagadyssey.social.market.MarketTradeRecord;
import com.jgeted.sagadyssey.social.market.MerchantMarketKnowledgeData;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import com.jgeted.sagadyssey.social.settlement.SettlementRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.transfer.TransferEndpoint;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Multi-line physical-goods proposal; each side may offer up to nine distinct assets. */
public record SubmitTradeProposalPacket(int npcEntityId, UUID sessionId, int expectedRevision,
                                        List<TradeAsset> playerOffers,
                                        List<TradeAsset> npcOffers) implements CustomPacketPayload {
    private static final int MAX_LINES_PER_SIDE = 9;

    public SubmitTradeProposalPacket {
        playerOffers = validatedCopy(playerOffers);
        npcOffers = validatedCopy(npcOffers);
    }

    public static final Type<SubmitTradeProposalPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Sagadyssey.MOD_ID, "submit_trade_v2"));
    public static final StreamCodec<ByteBuf, SubmitTradeProposalPacket> STREAM_CODEC = StreamCodec.of(
            SubmitTradeProposalPacket::write, SubmitTradeProposalPacket::read);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static void write(ByteBuf buffer, SubmitTradeProposalPacket value) {
        buffer.writeInt(value.npcEntityId);
        TradeSessionPayload.writeUuid(buffer, value.sessionId);
        buffer.writeInt(value.expectedRevision);
        writeAssets(buffer, value.playerOffers);
        writeAssets(buffer, value.npcOffers);
    }

    private static SubmitTradeProposalPacket read(ByteBuf buffer) {
        int entityId = buffer.readInt();
        UUID session = TradeSessionPayload.readUuid(buffer);
        int revision = buffer.readInt();
        return new SubmitTradeProposalPacket(entityId, session, revision,
                readAssets(buffer), readAssets(buffer));
    }

    private static void writeAssets(ByteBuf buffer, List<TradeAsset> assets) {
        buffer.writeByte(assets.size());
        for (TradeAsset asset : assets) {
            NetworkStrings.write(buffer, asset.fingerprint().itemId(), 128);
            buffer.writeInt(asset.fingerprint().componentsHash());
            buffer.writeInt(asset.quantity());
            NetworkStrings.write(buffer, asset.knownName(), 128);
        }
    }

    private static List<TradeAsset> readAssets(ByteBuf buffer) {
        int size = buffer.readUnsignedByte();
        if (size < 1 || size > MAX_LINES_PER_SIDE) {
            throw new DecoderException("invalid proposal line count");
        }
        List<TradeAsset> assets = new java.util.ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            ItemFingerprint fingerprint = new ItemFingerprint(NetworkStrings.read(buffer, 128),
                    buffer.readInt());
            int count = buffer.readInt();
            if (count <= 0 || count > 1_728) {
                throw new DecoderException("invalid proposal quantity");
            }
            assets.add(new TradeAsset(fingerprint, count, NetworkStrings.read(buffer, 128)));
        }
        return assets;
    }

    private static List<TradeAsset> validatedCopy(List<TradeAsset> assets) {
        if (assets == null || assets.isEmpty() || assets.size() > MAX_LINES_PER_SIDE) {
            throw new IllegalArgumentException("proposal must contain 1-9 lines per side");
        }
        return List.copyOf(assets);
    }

    public static void handle(SubmitTradeProposalPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !NetworkRateLimiter.allow(player, "submit_trade_v2", 4)
                    || !(player.level().getEntity(packet.npcEntityId) instanceof NpcBase npc)
                    || player.distanceToSqr(npc) > 36.0D) return;
            UUID playerId = CharacterRegistry.currentId(player);
            TradeSessionData data = TradeSessionData.get(player.level().getServer());
            TradeSession before = data.session(packet.sessionId);
            if (playerId == null || before == null || !before.playerId().equals(playerId)
                    || !before.npcId().equals(npc.getCharacterId())) return;
            long now = player.level().getGameTime();
            boolean directServiceParty = ServiceRelationshipData.get(player.level().getServer())
                    .hasActiveBetween(playerId, npc.getCharacterId(), now);
            NpcTradeAccessPolicy.Decision tradeAccess = NpcTradeAccessPolicy.evaluate(
                    FactionAttachments.getStandings(player), npc.getFaction(), directServiceParty);
            if (!tradeAccess.allowed()) {
                TradeSession cancelled = before.finish(TradeSessionStatus.CANCELLED,
                        tradeAccess.explanation());
                data.put(cancelled);
                RightsStateData rights = RightsStateData.get(player.level().getServer());
                rights.releaseReservations(cancelled.reservationToken());
                player.displayClientMessage(Component.literal("§c" + tradeAccess.explanation()), true);
                PacketDistributor.sendToPlayer(player,
                        TradeSessionPayload.create(player, npc, cancelled));
                return;
            }
            Map<UUID, TransferEndpoint> endpoints = Map.of(
                    before.playerContainerId(), new TransferEndpoint(before.playerContainerId(), player.getInventory()),
                    before.npcContainerId(), new TransferEndpoint(before.npcContainerId(), npc.getEquipmentInventory()));
            try {
                SettlementRecord localSettlement = SettlementRegistryData.get(player.level().getServer()).nearest(
                        player.level().dimension().identifier().toString(), npc.blockPosition(), 256.0D);
                NpcLifeRecord life = NpcLifeData.get(player.level().getServer()).record(npc.getCharacterId());
                int relationship = life == null ? 0 : life.relations().stream()
                        .filter(value -> value.otherCharacterId().equals(playerId))
                        .mapToInt(value -> value.strength()).findFirst().orElse(0);
                boolean awayFromHome = localSettlement != null && life != null
                        && life.originSettlementId() != null
                        && !life.originSettlementId().equals(localSettlement.id());
                int routeRisk = (awayFromHome ? 800 : 0)
                        + Math.max(0, 50 - npc.getMoral()) * 20;
                MerchantMarketKnowledgeData merchantKnowledge =
                        MerchantMarketKnowledgeData.get(player.level().getServer());
                if (localSettlement != null && npc.getProfession() == NpcProfession.TRADER) {
                    merchantKnowledge.observe(npc.getCharacterId(), localSettlement.id(),
                            npc.getEquipmentInventory(), LocalMarketData.get(player.level().getServer()), now);
                }
                TradeEvaluationContext evaluationContext = new TradeEvaluationContext(
                        localSettlement == null ? null : localSettlement.id(),
                        LocalMarketData.get(player.level().getServer()), now, relationship,
                        awayFromHome ? 500 : 0, routeRisk, tradeAccess.priceBasisPoints(),
                        npc.getProfession() == NpcProfession.TRADER ? npc.getCharacterId() : null,
                        npc.getProfession() == NpcProfession.TRADER ? merchantKnowledge : null);
                RightsStateData rights = RightsStateData.get(player.level().getServer());
                TradeSession updated = TradeNegotiationService.propose(data, before.id(),
                        packet.expectedRevision, playerId, TradeIntent.SPOT_PURCHASE,
                        packet.playerOffers, packet.npcOffers,
                        endpoints, now, evaluationContext, rights);
                if (updated.status() == TradeSessionStatus.ACCEPTED) {
                    var receipt = TradeSettlementService.commit(updated, endpoints,
                            rights, TransferLedgerData.get(player.level().getServer()),
                            now);
                    if (receipt.successful() && localSettlement != null) {
                        recordMarketTrade(playerId, npc.getCharacterId(), localSettlement.id(),
                                updated, now, LocalMarketData.get(player.level().getServer()));
                    }
                    updated = updated.finish(receipt.successful() ? TradeSessionStatus.COMMITTED
                            : TradeSessionStatus.FAILED, receipt.successful()
                            ? "双方已当面点清，交换完成。" : "库存已经变化：" + receipt.reason());
                    data.put(updated);
                    rights.releaseReservations(updated.reservationToken());
                    if (receipt.successful()) npc.playAction(NpcAction.TRADE_PRESENT, 36);
                }
                PacketDistributor.sendToPlayer(player, TradeSessionPayload.create(player, npc, updated));
            } catch (RuntimeException exception) {
                player.displayClientMessage(Component.literal("§c" + exception.getMessage()), true);
                TradeSession current = data.session(packet.sessionId);
                if (current != null) PacketDistributor.sendToPlayer(player,
                        TradeSessionPayload.create(player, npc, current));
            }
        });
    }

    private static void recordMarketTrade(UUID playerId, UUID npcId, UUID settlementId,
                                          TradeSession session, long now,
                                          LocalMarketData markets) {
        if (session.proposal() == null) return;
        List<MarketBasketEntry> playerBasket = session.proposal().playerOffers().stream()
                .map(asset -> new MarketBasketEntry(asset.fingerprint(), asset.quantity())).toList();
        List<MarketBasketEntry> npcBasket = session.proposal().npcOffers().stream()
                .map(asset -> new MarketBasketEntry(asset.fingerprint(), asset.quantity())).toList();
        markets.record(new MarketTradeRecord(session.proposal().id(), settlementId,
                playerId, npcId, playerBasket, npcBasket, now, true));
    }
}
