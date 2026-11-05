package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.network.NetworkStrings;
import com.jgeted.sagadyssey.knowledge.ConflictState;
import com.jgeted.sagadyssey.knowledge.FreshnessBand;
import com.jgeted.sagadyssey.knowledge.KnowledgeSourceKind;
import com.jgeted.sagadyssey.knowledge.PrecisionBand;
import com.jgeted.sagadyssey.knowledge.projection.NpcProjectionService;
import com.jgeted.sagadyssey.knowledge.projection.NpcViewSnapshot;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** The only ordinary NPC context response. It contains no authoritative combat or inventory state. */
public record NpcViewSnapshotPayload(NpcViewSnapshot view) implements CustomPacketPayload {
    private static final int MAX_LIST = 32;
    public static final Type<NpcViewSnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, "npc_view_snapshot"));

    public static final StreamCodec<ByteBuf, NpcViewSnapshotPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> write(buffer, payload.view),
            buffer -> new NpcViewSnapshotPayload(read(buffer)));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(NpcViewSnapshotPayload payload, IPayloadContext context) {
        ClientNpcViewSnapshotPayloadHandler.handle(payload, context);
    }

    public static void openFor(ServerPlayer player, NpcBase npc) {
        openFor(player, npc, "");
    }

    public static void openFor(ServerPlayer player, NpcBase npc, String conversation) {
        var payload = new NpcViewSnapshotPayload(NpcProjectionService.forViewer(player, npc, conversation));
        var encoded = io.netty.buffer.Unpooled.buffer();
        try {
            STREAM_CODEC.encode(encoded, payload);
            if(encoded.readableBytes()>65_536)throw new IllegalStateException("NPC context projection exceeds 64 KiB budget");
            if(!com.jgeted.sagadyssey.worldsim.lod.NpcLodBudgets.contextBytes(player.server).admit(player.getUUID(),player.server.overworld().getGameTime(),encoded.readableBytes()))return;
            com.jgeted.sagadyssey.worldsim.lod.LodMetrics.payload(player.server,encoded.readableBytes());
        } finally {encoded.release();}
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static void write(ByteBuf buffer, NpcViewSnapshot view) {
        buffer.writeInt(view.bodyEntityId());
        writeUuid(buffer, view.characterId());
        buffer.writeLong(view.projectionRevision());
        writeString(buffer, view.identity().name());
        writeString(buffer, view.identity().publicRole());
        writeString(buffer, view.identity().profession());
        writeString(buffer, view.identity().affiliation());
        writeString(buffer, view.identity().appearance());
        writeString(buffer, view.relationship());
        writeString(buffer, view.conversation());
        writeList(buffer, view.conditions(), (buf, value) -> {
            writeString(buf, value.label());
            writeString(buf, value.description());
            buf.writeByte(value.severity());
        });
        writeList(buffer, view.knownFacts(), (buf, value) -> {
            writeString(buf, value.label());
            writeString(buf, value.displayValue());
            buf.writeByte(value.precision().ordinal());
            buf.writeByte(value.sourceKind().ordinal());
            writeString(buf, value.sourceLabel());
            buf.writeByte(value.freshness().ordinal());
            buf.writeByte(value.conflict().ordinal());
            buf.writeBoolean(value.exactValueAvailable());
        });
        writeList(buffer, view.systemInsights(), (buf, value) -> {
            writeString(buf, value.category());
            writeString(buf, value.title());
            writeString(buf, value.summary());
            writeString(buf, value.detail());
            buf.writeByte(value.urgency());
            buf.writeByte(value.precision().ordinal());
            buf.writeByte(value.freshness().ordinal());
            writeString(buf, value.sourceLabel());
        });
        writeList(buffer, view.actions(), (buf, value) -> {
            writeString(buf, value.actionId());
            writeString(buf, value.label());
            writeString(buf, value.category());
            writeString(buf, value.explanation());
        });
        writeList(buffer, view.visibleAssets(), (buf, value) -> {
            writeString(buf, value.location());
            writeString(buf, value.itemId());
            writeString(buf, value.description());
            buf.writeByte(value.quantityPrecision().ordinal());
        });
        writeList(buffer, view.agreements(), (buf, value) -> {
            writeString(buf, value.title());
            writeString(buf, value.status());
            writeString(buf, value.terms());
            buf.writeLong(value.endsAt());
        });
        buffer.writeLong(view.expiresAt());
    }

    private static NpcViewSnapshot read(ByteBuf buffer) {
        int entityId = buffer.readInt();
        UUID characterId = readUuid(buffer);
        long revision = buffer.readLong();
        NpcViewSnapshot.DisplayIdentity identity = new NpcViewSnapshot.DisplayIdentity(
                readString(buffer), readString(buffer), readString(buffer), readString(buffer),
                readString(buffer));
        String relationship = readString(buffer);
        String conversation = readString(buffer);
        List<NpcViewSnapshot.ObservedCondition> conditions = readList(buffer, buf ->
                new NpcViewSnapshot.ObservedCondition(readString(buf), readString(buf),
                        buf.readUnsignedByte()));
        List<NpcViewSnapshot.KnownFact> facts = readList(buffer, buf ->
                new NpcViewSnapshot.KnownFact(readString(buf), readString(buf),
                        readEnum(buf, PrecisionBand.values()), readEnum(buf, KnowledgeSourceKind.values()),
                        readString(buf), readEnum(buf, FreshnessBand.values()),
                        readEnum(buf, ConflictState.values()), buf.readBoolean()));
        List<NpcViewSnapshot.SystemInsight> insights = readList(buffer, buf ->
                new NpcViewSnapshot.SystemInsight(readString(buf), readString(buf),
                        readString(buf), readString(buf), buf.readUnsignedByte(),
                        readEnum(buf, PrecisionBand.values()),
                        readEnum(buf, FreshnessBand.values()), readString(buf)));
        List<NpcViewSnapshot.AvailableInteraction> actions = readList(buffer, buf ->
                new NpcViewSnapshot.AvailableInteraction(readString(buf), readString(buf),
                        readString(buf), readString(buf)));
        List<NpcViewSnapshot.VisibleAsset> assets = readList(buffer, buf ->
                new NpcViewSnapshot.VisibleAsset(readString(buf), readString(buf), readString(buf),
                        readEnum(buf, PrecisionBand.values())));
        List<NpcViewSnapshot.KnownAgreement> agreements = readList(buffer, buf ->
                new NpcViewSnapshot.KnownAgreement(readString(buf), readString(buf),
                        readString(buf), buf.readLong()));
        return new NpcViewSnapshot(entityId, characterId, revision, identity, relationship,
                conversation,
                conditions, facts, insights, actions, assets, agreements, buffer.readLong());
    }

    private static void writeString(ByteBuf buffer, String value) {
        NetworkStrings.write(buffer, value, 512);
    }

    private static String readString(ByteBuf buffer) { return NetworkStrings.read(buffer, 512); }
    private static void writeUuid(ByteBuf buffer, UUID value) {
        buffer.writeLong(value.getMostSignificantBits());
        buffer.writeLong(value.getLeastSignificantBits());
    }
    private static UUID readUuid(ByteBuf buffer) { return new UUID(buffer.readLong(), buffer.readLong()); }

    private static <T> void writeList(ByteBuf buffer, List<T> values, ElementWriter<T> writer) {
        if (values.size() > MAX_LIST) throw new IllegalArgumentException("projection list too large");
        buffer.writeByte(values.size());
        values.forEach(value -> writer.write(buffer, value));
    }

    private static <T> List<T> readList(ByteBuf buffer, Function<ByteBuf, T> reader) {
        int count = buffer.readUnsignedByte();
        if (count > MAX_LIST) throw new DecoderException("projection list too large");
        List<T> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) result.add(reader.apply(buffer));
        return result;
    }

    private static <E extends Enum<E>> E readEnum(ByteBuf buffer, E[] values) {
        int ordinal = buffer.readUnsignedByte();
        if (ordinal >= values.length) throw new DecoderException("invalid projection enum");
        return values[ordinal];
    }

    @FunctionalInterface
    private interface ElementWriter<T> { void write(ByteBuf buffer, T value); }
}
