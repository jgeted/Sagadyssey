package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.network.NetworkStrings;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.trade.TradeCatalogEntry;
import com.jgeted.sagadyssey.npc.trade.TradeCatalogService;
import com.jgeted.sagadyssey.npc.trade.TradeAsset;
import com.jgeted.sagadyssey.npc.trade.TradeSession;
import com.jgeted.sagadyssey.npc.trade.TradeSessionData;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Observer-safe trade view: only assets deliberately exposed for this negotiation. */
public record TradeSessionPayload(int npcEntityId, UUID sessionId, int revision, String npcName,
                                   String status, String explanation,
                                   List<TradeCatalogEntry> playerAssets,
                                   List<TradeCatalogEntry> npcAssets,
                                   List<TradeAsset> playerOffer,
                                   List<TradeAsset> npcOffer) implements CustomPacketPayload {
    private static final int MAX_ASSETS = 27;
    private static final int MAX_OFFER_LINES = 9;
    public static final Type<TradeSessionPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Sagadyssey.MOD_ID, "trade_session_v2"));
    public static final StreamCodec<ByteBuf, TradeSessionPayload> STREAM_CODEC = StreamCodec.of(
            TradeSessionPayload::write, TradeSessionPayload::read);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }


    public static TradeSessionPayload create(ServerPlayer player, NpcBase npc,
                                             TradeSession session) {
        RightsStateData rights = RightsStateData.get(player.level().getServer());
        TradeSessionData sessions = TradeSessionData.get(player.level().getServer());
        long now = player.level().getGameTime();
        List<TradeAsset> playerOffer = session.proposal() == null ? List.of()
                : session.proposal().playerOffers();
        List<TradeAsset> npcOffer = session.proposal() == null ? List.of()
                : session.proposal().npcOffers();
        return new TradeSessionPayload(npc.getId(), session.id(), session.revision(),
                npc.getNpcName(), session.status().name(), session.explanation(),
                TradeCatalogService.visiblePlayerOffers(session.playerId(),
                        session.playerContainerId(), player.getInventory(), rights, sessions,
                        session.id(), now),
                TradeCatalogService.visibleNpcOffers(session.npcId(), session.npcContainerId(),
                        npc.getEquipmentInventory(), rights, sessions, session.id(), now),
                playerOffer, npcOffer);
    }

    private static void write(ByteBuf buffer, TradeSessionPayload value) {
        buffer.writeInt(value.npcEntityId);
        writeUuid(buffer, value.sessionId);
        buffer.writeInt(value.revision);
        NetworkStrings.write(buffer, value.npcName, 128);
        NetworkStrings.write(buffer, value.status, 32);
        NetworkStrings.write(buffer, value.explanation, 512);
        writeEntries(buffer, value.playerAssets);
        writeEntries(buffer, value.npcAssets);
        writeOffer(buffer, value.playerOffer);
        writeOffer(buffer, value.npcOffer);
    }

    private static TradeSessionPayload read(ByteBuf buffer) {
        return new TradeSessionPayload(buffer.readInt(), readUuid(buffer), buffer.readInt(),
                NetworkStrings.read(buffer, 128), NetworkStrings.read(buffer, 32),
                NetworkStrings.read(buffer, 512), readEntries(buffer), readEntries(buffer),
                readOffer(buffer), readOffer(buffer));
    }

    private static void writeEntries(ByteBuf buffer, List<TradeCatalogEntry> entries) {
        if (entries.size() > MAX_ASSETS) throw new IllegalArgumentException("trade catalog too large");
        buffer.writeByte(entries.size());
        for (TradeCatalogEntry entry : entries) {
            NetworkStrings.write(buffer, entry.fingerprint().itemId(), 128);
            buffer.writeInt(entry.fingerprint().componentsHash());
            buffer.writeInt(entry.owned());
            buffer.writeInt(entry.available());
            buffer.writeInt(entry.reservedHere());
            NetworkStrings.write(buffer, entry.knownName(), 128);
            NetworkStrings.write(buffer, entry.reason(), 256);
        }
    }

    private static List<TradeCatalogEntry> readEntries(ByteBuf buffer) {
        int size = buffer.readUnsignedByte();
        if (size > MAX_ASSETS) throw new DecoderException("trade catalog too large");
        List<TradeCatalogEntry> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            ItemFingerprint fingerprint = new ItemFingerprint(NetworkStrings.read(buffer, 128),
                    buffer.readInt());
            int owned = buffer.readInt();
            int available = buffer.readInt();
            int reservedHere = buffer.readInt();
            if (owned <= 0 || owned > 1_728 || available <= 0 || available > owned
                    || reservedHere < 0 || reservedHere > available) {
                throw new DecoderException("invalid trade amount");
            }
            result.add(new TradeCatalogEntry(fingerprint, owned, available, reservedHere,
                    NetworkStrings.read(buffer, 128), NetworkStrings.read(buffer, 256)));
        }
        return result;
    }

    private static void writeOffer(ByteBuf buffer, List<TradeAsset> assets) {
        if (assets.size() > MAX_OFFER_LINES) throw new IllegalArgumentException("trade offer too large");
        buffer.writeByte(assets.size());
        for (TradeAsset asset : assets) {
            NetworkStrings.write(buffer, asset.fingerprint().itemId(), 128);
            buffer.writeInt(asset.fingerprint().componentsHash());
            buffer.writeInt(asset.quantity());
            NetworkStrings.write(buffer, asset.knownName(), 128);
        }
    }

    private static List<TradeAsset> readOffer(ByteBuf buffer) {
        int size = buffer.readUnsignedByte();
        if (size > MAX_OFFER_LINES) throw new DecoderException("trade offer too large");
        List<TradeAsset> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            ItemFingerprint fingerprint = new ItemFingerprint(NetworkStrings.read(buffer, 128),
                    buffer.readInt());
            int quantity = buffer.readInt();
            if (quantity <= 0 || quantity > 1_728) throw new DecoderException("invalid offer amount");
            result.add(new TradeAsset(fingerprint, quantity, NetworkStrings.read(buffer, 128)));
        }
        return result;
    }

    static void writeUuid(ByteBuf buffer, UUID id) {
        buffer.writeLong(id.getMostSignificantBits());
        buffer.writeLong(id.getLeastSignificantBits());
    }
    static UUID readUuid(ByteBuf buffer) { return new UUID(buffer.readLong(), buffer.readLong()); }
}
