package com.jgeted.sagadyssey.npc.trade;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable sessions double as reservations; terminal sessions never reserve stock. */
public final class TradeSessionData extends SavedData {
    private static final String DATA_NAME = "sagadyssey_trade_sessions_v2";
    private static final int MAX_RETAINED = 512;
    private static final Factory<TradeSessionData> FACTORY =
            new Factory<>(TradeSessionData::new, TradeSessionData::load);
    private final Map<UUID, TradeSession> sessions = new HashMap<>();

    public static TradeSessionData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public TradeSession open(UUID playerId, UUID npcId, UUID playerContainerId,
                             UUID npcContainerId, long now) {
        expire(now);
        TradeSession existing = sessions.values().stream()
                .filter(session -> session.playerId().equals(playerId)
                        && session.npcId().equals(npcId) && !session.status().terminal())
                .findFirst().orElse(null);
        if (existing != null) return existing;
        TradeSession created = TradeSession.open(playerId, npcId, playerContainerId,
                npcContainerId, now, 1_200L);
        sessions.put(created.id(), created);
        prune();
        setDirty();
        return created;
    }

    public void put(TradeSession session) {
        sessions.put(session.id(), session);
        setDirty();
    }

    @Nullable public TradeSession session(UUID id) { return sessions.get(id); }
    public List<TradeSession> sessions() { return List.copyOf(sessions.values()); }

    public int reserved(UUID containerId, ItemFingerprint fingerprint, UUID exceptSession) {
        return reserved(containerId, fingerprint, exceptSession, Long.MIN_VALUE);
    }

    public int reserved(UUID containerId, ItemFingerprint fingerprint,
                        UUID exceptSession, long now) {
        int total = 0;
        for (TradeSession session : sessions.values()) {
            if (session.id().equals(exceptSession) || session.status().terminal()
                    || session.status() == TradeSessionStatus.OPEN || session.proposal() == null
                    || now != Long.MIN_VALUE && now >= session.proposal().expiresAt()) continue;
            List<TradeAsset> assets = session.playerContainerId().equals(containerId)
                    ? session.proposal().playerOffers()
                    : session.npcContainerId().equals(containerId)
                    ? session.proposal().npcOffers() : List.of();
            for (TradeAsset asset : assets) {
                if (asset.fingerprint().equals(fingerprint)) total += asset.quantity();
            }
        }
        return total;
    }

    /** Legacy sessions may lack global reservations; only that unmirrored remainder is added separately. */
    public int reservedOutsideRights(UUID containerId, ItemFingerprint fingerprint,
                                     UUID exceptSession, RightsStateData rights, long now) {
        int total = 0;
        for (TradeSession session : sessions.values()) {
            if (session.id().equals(exceptSession) || session.status().terminal()
                    || session.status() == TradeSessionStatus.OPEN || session.proposal() == null
                    || now >= session.proposal().expiresAt()) continue;
            List<TradeAsset> assets = session.playerContainerId().equals(containerId)
                    ? session.proposal().playerOffers()
                    : session.npcContainerId().equals(containerId)
                    ? session.proposal().npcOffers() : List.of();
            int promised = assets.stream().filter(asset -> asset.fingerprint().equals(fingerprint))
                    .mapToInt(TradeAsset::quantity).sum();
            int mirrored = rights.reservationsForToken(session.reservationToken(), now).stream()
                    .filter(value -> value.containerId().equals(containerId)
                            && value.fingerprint().equals(fingerprint))
                    .mapToInt(value -> value.quantity()).sum();
            total += Math.max(0, promised - mirrored);
        }
        return total;
    }

    public void expire(long now) {
        boolean changed = false;
        for (var entry : List.copyOf(sessions.entrySet())) {
            TradeSession session = entry.getValue();
            if (!session.status().terminal() && now >= session.expiresAt()) {
                sessions.put(entry.getKey(), session.finish(TradeSessionStatus.EXPIRED,
                        "谈判超时，库存预留已释放"));
                changed = true;
            }
        }
        if (changed) setDirty();
    }

    private void prune() {
        int excess = sessions.size() - MAX_RETAINED;
        if (excess <= 0) return;
        sessions.values().stream().filter(session -> session.status().terminal())
                .sorted(Comparator.comparingLong(TradeSession::openedAt)).limit(excess)
                .map(TradeSession::id).toList().forEach(sessions::remove);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        sessions.values().forEach(session -> list.add(session.save()));
        tag.put("Sessions", list);
        return tag;
    }

    private static TradeSessionData load(CompoundTag tag, HolderLookup.Provider registries) {
        TradeSessionData data = new TradeSessionData();
        ListTag list = tag.getList("Sessions", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            try {
                TradeSession session = TradeSession.load(list.getCompound(index));
                data.sessions.put(session.id(), session);
            } catch (RuntimeException ignored) { }
        }
        return data;
    }
}
