package com.jgeted.sagadyssey.npc.faction.network;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.network.NetworkStrings;
import com.jgeted.sagadyssey.npc.faction.FactionRelationMatrix;
import com.jgeted.sagadyssey.npc.faction.FactionRegistry;
import com.jgeted.sagadyssey.npc.faction.FactionStandings;
import com.jgeted.sagadyssey.npc.faction.InterFactionRelation;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 服务端→客户端：全量声望数据同步（玩家登录时）。
 * <p>
 * 只包含该玩家已经发现的阵营、声望值和这些已知阵营之间的关系摘要。
 */
public record FactionDataSyncPayload(
        Map<String, Integer> standings,
        Set<String> knownFactions,
        Map<String, String> relationSummary,
        String playerFactionName
) implements CustomPacketPayload {
    private static final int MAX_FACTIONS = 128;
    private static final int MAX_RELATIONS = 8_192;

    public FactionDataSyncPayload {
        standings = Map.copyOf(standings);
        knownFactions = Set.copyOf(knownFactions);
        relationSummary = Map.copyOf(relationSummary);
    }

    public static final Type<FactionDataSyncPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Sagadyssey.MOD_ID, "faction_data_sync")
    );

    public static final StreamCodec<ByteBuf, FactionDataSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                // standings map
                requireSize(payload.standings.size(), MAX_FACTIONS, "standings");
                buf.writeInt(payload.standings.size());
                for (var entry : payload.standings.entrySet()) {
                    NetworkStrings.write(buf, entry.getKey(), 128);
                    buf.writeInt(entry.getValue());
                }
                requireSize(payload.knownFactions.size(), MAX_FACTIONS, "known factions");
                buf.writeInt(payload.knownFactions.size());
                for (String factionId : payload.knownFactions) {
                    NetworkStrings.write(buf, factionId, 128);
                }
                // relationSummary map
                requireSize(payload.relationSummary.size(), MAX_RELATIONS, "relations");
                buf.writeInt(payload.relationSummary.size());
                for (var entry : payload.relationSummary.entrySet()) {
                    NetworkStrings.write(buf, entry.getKey(), 260);
                    NetworkStrings.write(buf, entry.getValue(), 32);
                }
                // playerFactionName
                NetworkStrings.write(buf,
                        payload.playerFactionName != null ? payload.playerFactionName : "", 64);
            },
            buf -> {
                // standings map
                int size = readSize(buf, MAX_FACTIONS, "standings");
                Map<String, Integer> standings = new HashMap<>();
                for (int i = 0; i < size; i++) {
                    String key = NetworkStrings.read(buf, 128);
                    int value = buf.readInt();
                    if (value < -100 || value > 100) {
                        throw new DecoderException("invalid faction standing");
                    }
                    standings.put(key, value);
                }
                int knownSize = readSize(buf, MAX_FACTIONS, "known factions");
                Set<String> knownFactions = new java.util.HashSet<>();
                for (int i = 0; i < knownSize; i++) {
                    knownFactions.add(NetworkStrings.read(buf, 128));
                }
                // relationSummary map
                int relSize = readSize(buf, MAX_RELATIONS, "relations");
                Map<String, String> relations = new HashMap<>();
                for (int i = 0; i < relSize; i++) {
                    String key = NetworkStrings.read(buf, 260);
                    String value = NetworkStrings.read(buf, 32);
                    relations.put(key, value);
                }
                // playerFactionName
                String name = NetworkStrings.read(buf, 64);
                return new FactionDataSyncPayload(standings, knownFactions, relations,
                        name.isEmpty() ? null : name);
            }
    );

    private static void requireSize(int size, int maximum, String label) {
        if (size < 0 || size > maximum) {
            throw new IllegalArgumentException("too many faction " + label);
        }
    }

    private static int readSize(ByteBuf buffer, int maximum, String label) {
        int size = buffer.readInt();
        if (size < 0 || size > maximum) {
            throw new DecoderException("invalid faction " + label + " count");
        }
        return size;
    }

    @Override
    public Type<FactionDataSyncPayload> type() {
        return TYPE;
    }

    /** 客户端处理：更新 ClientFactionCache */
    public static void handle(final FactionDataSyncPayload data,
                               final IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientFactionCache.updateFromSync(data.standings, data.knownFactions);
            ClientFactionCache.updateRelationSummary(data.relationSummary);
            ClientFactionCache.setPlayerFactionName(data.playerFactionName);
        });
    }

    /** 从 FactionStandings 构建 Payload（服务端调用） */
    public static FactionDataSyncPayload from(FactionStandings standings) {
        Set<String> known = standings.getKnownFactionIds();
        Map<String, Integer> visibleStandings = new HashMap<>();
        standings.getStandingsMap().forEach((id, value) -> {
            if (known.contains(id)) visibleStandings.put(id, value);
        });
        return new FactionDataSyncPayload(
                visibleStandings,
                known,
                buildRelationSummary(known),
                standings.getPlayerFactionName()
        );
    }

    /** 构建轻量关系摘要（供客户端 GUI 渲染） */
    private static Map<String, String> buildRelationSummary(Set<String> knownFactions) {
        Map<String, String> summary = new HashMap<>();
        var matrix = FactionRelationMatrix.getInstance();
        var factions = FactionRegistry.getAllFactions();
        for (var a : factions) {
            if (!knownFactions.contains(a.id())) continue;
            for (var b : factions) {
                if (!knownFactions.contains(b.id())) continue;
                if (a.id().equals(b.id())) continue;
                InterFactionRelation rel = matrix.getRelation(a, b);
                if (rel != InterFactionRelation.NEUTRAL) {
                    summary.put(a.id() + "→" + b.id(), rel.name());
                }
            }
        }
        return summary;
    }
}
