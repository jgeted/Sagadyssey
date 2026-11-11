package com.jgeted.sagadyssey.npc.faction.network;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.network.NetworkStrings;
import com.jgeted.sagadyssey.npc.faction.StandingLevel;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端→客户端：增量声望更新（声望变化时）。
 * <p>
 * 包含主要变化的阵营及受涟漪影响的其他阵营变化。
 */
public record FactionStandingsUpdatePayload(
        String factionId,
        int newValue,
        StandingLevel newLevel,
        String reasonKey,
        List<AffectedFaction> rippleEffects
) implements CustomPacketPayload {
    private static final int MAX_RIPPLES = 128;

    public FactionStandingsUpdatePayload {
        if (factionId == null || newLevel == null || reasonKey == null || rippleEffects == null) {
            throw new IllegalArgumentException("incomplete faction update");
        }
        rippleEffects = List.copyOf(rippleEffects);
    }

    public static final Type<FactionStandingsUpdatePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Sagadyssey.MOD_ID, "faction_standings_update")
    );

    public static final StreamCodec<ByteBuf, FactionStandingsUpdatePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                NetworkStrings.write(buf, payload.factionId, 128);
                buf.writeInt(payload.newValue);
                buf.writeByte(payload.newLevel.ordinal());
                NetworkStrings.write(buf, payload.reasonKey, 128);
                if (payload.rippleEffects.size() > MAX_RIPPLES) {
                    throw new IllegalArgumentException("too many faction ripple effects");
                }
                buf.writeInt(payload.rippleEffects.size());
                for (var a : payload.rippleEffects) {
                    AffectedFaction.write(buf, a);
                }
            },
            buf -> {
                String factionId = NetworkStrings.read(buf, 128);
                int newValue = readStanding(buf);
                StandingLevel level = readLevel(buf);
                String reasonKey = NetworkStrings.read(buf, 128);
                int count = buf.readInt();
                if (count < 0 || count > MAX_RIPPLES) {
                    throw new DecoderException("invalid faction ripple count");
                }
                List<AffectedFaction> ripples = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    ripples.add(AffectedFaction.read(buf));
                }
                return new FactionStandingsUpdatePayload(factionId, newValue, level, reasonKey, ripples);
            }
    );

    private static int readStanding(ByteBuf buffer) {
        int value = buffer.readInt();
        if (value < -100 || value > 100) throw new DecoderException("invalid faction standing");
        return value;
    }

    private static StandingLevel readLevel(ByteBuf buffer) {
        int ordinal = buffer.readUnsignedByte();
        if (ordinal >= StandingLevel.values().length) {
            throw new DecoderException("invalid standing level");
        }
        return StandingLevel.values()[ordinal];
    }

    @Override
    public Type<FactionStandingsUpdatePayload> type() {
        return TYPE;
    }

    /** 客户端处理：更新 ClientFactionCache */
    public static void handle(final FactionStandingsUpdatePayload data,
                               final IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientFactionCache.updateFromUpdate(data.factionId, data.newValue);
            for (var a : data.rippleEffects) {
                ClientFactionCache.updateFromUpdate(a.factionId(), a.newValue());
            }
        });
    }

    /** 涟漪影响的阵营 */
    public record AffectedFaction(
            String factionId,
            int delta,
            int newValue,
            StandingLevel newLevel
    ) {
        static void write(ByteBuf buf, AffectedFaction a) {
            NetworkStrings.write(buf, a.factionId, 128);
            buf.writeInt(a.delta);
            buf.writeInt(a.newValue);
            buf.writeByte(a.newLevel.ordinal());
        }

        static AffectedFaction read(ByteBuf buf) {
            return new AffectedFaction(
                    NetworkStrings.read(buf, 128),
                    buf.readInt(),
                    readStanding(buf),
                    readLevel(buf)
            );
        }
    }
}
