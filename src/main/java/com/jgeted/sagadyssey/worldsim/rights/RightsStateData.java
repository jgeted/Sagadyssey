package com.jgeted.sagadyssey.worldsim.rights;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Authoritative world-level snapshot of container defaults, grants, and exceptional claims. */
public final class RightsStateData extends SavedData {
    public static final String DATA_NAME = "sagadyssey_asset_rights_v2";
    public static final int DATA_VERSION = 2;
    public static final Factory<RightsStateData> FACTORY =
            new Factory<>(RightsStateData::new, RightsStateData::load);

    private final Map<UUID, ContainerContext> contexts = new HashMap<>();
    private final Map<UUID, RightGrant> grants = new HashMap<>();
    private final Map<UUID, AssetClaim> claims = new HashMap<>();
    private final Map<UUID, AssetReservation> reservations = new HashMap<>();

    public static RightsStateData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public ContainerContext ensureContext(UUID id, String locator, SubjectRef custodian,
                                          SubjectRef owner, AccessPolicy policy) {
        ContainerContext existing = contexts.get(id);
        if (existing != null) return existing;
        ContainerContext created = new ContainerContext(id, locator, custodian, owner, policy, 0L);
        contexts.put(id, created);
        setDirty();
        return created;
    }

    @Nullable public ContainerContext context(UUID id) { return contexts.get(id); }
    public Collection<ContainerContext> contexts() { return List.copyOf(contexts.values()); }
    public Collection<RightGrant> grants() { return List.copyOf(grants.values()); }

    public long bumpContext(UUID id) {
        ContainerContext context = contexts.get(id);
        if (context == null) throw new IllegalStateException("Unknown container context: " + id);
        ContainerContext bumped = context.bumped();
        contexts.put(id, bumped);
        setDirty();
        return bumped.revision();
    }

    public void grant(RightGrant grant) {
        grants.put(grant.id(), grant);
        setDirty();
    }

    public void revoke(UUID grantId) {
        if (grants.remove(grantId) != null) setDirty();
    }

    public List<RightGrant> grantsFor(UUID containerId, SubjectRef holder, long gameTime) {
        return grants.values().stream()
                .filter(grant -> grant.containerId().equals(containerId)
                        && grant.holder().equals(holder) && grant.activeAt(gameTime))
                .toList();
    }

    public void putClaim(AssetClaim claim) {
        removeClaimsAt(claim.containerId(), claim.slot(), claim.fingerprint());
        claims.put(claim.claimId(), claim);
        setDirty();
    }

    public void removeClaim(UUID claimId) {
        if (claims.remove(claimId) != null) setDirty();
    }

    public void removeClaimsAt(UUID containerId, int slot, ItemFingerprint fingerprint) {
        boolean changed = claims.entrySet().removeIf(entry -> {
            AssetClaim claim = entry.getValue();
            return claim.containerId().equals(containerId) && claim.slot() == slot
                    && claim.fingerprint().equals(fingerprint);
        });
        if (changed) setDirty();
    }

    public void removeClaimsForSlot(UUID containerId, int slot) {
        boolean changed = claims.entrySet().removeIf(entry -> {
            AssetClaim claim = entry.getValue();
            return claim.containerId().equals(containerId) && claim.slot() == slot;
        });
        if (changed) setDirty();
    }

    @Nullable
    public AssetClaim claimAt(UUID containerId, int slot, ItemFingerprint fingerprint) {
        return claims.values().stream()
                .filter(claim -> claim.containerId().equals(containerId) && claim.slot() == slot
                        && claim.fingerprint().equals(fingerprint))
                .findFirst().orElse(null);
    }

    public List<AssetClaim> claimsAt(UUID containerId, int slot, ItemFingerprint fingerprint) {
        return claims.values().stream()
                .filter(claim -> claim.containerId().equals(containerId) && claim.slot() == slot
                        && claim.fingerprint().equals(fingerprint))
                .toList();
    }

    public Collection<AssetClaim> claims() { return List.copyOf(claims.values()); }
    public Collection<AssetReservation> reservations() { return List.copyOf(reservations.values()); }

    public boolean reserve(AssetReservation reservation, int physicalAvailable, long gameTime) {
        pruneReservations(gameTime);
        int reservedElsewhere = reservedQuantity(reservation.containerId(),
                reservation.fingerprint(), reservation.reservedFor(),
                reservation.token(), gameTime);
        if (physicalAvailable - reservedElsewhere < reservation.quantity()) return false;
        reservations.put(reservation.id(), reservation);
        setDirty();
        return true;
    }

    public int reservedQuantity(UUID containerId, ItemFingerprint fingerprint,
                                String exceptToken, long gameTime) {
        return reservations.values().stream()
                .filter(value -> value.activeAt(gameTime)
                        && value.containerId().equals(containerId)
                        && value.fingerprint().equals(fingerprint)
                        && (exceptToken == null || !value.token().equals(exceptToken)))
                .mapToInt(AssetReservation::quantity).sum();
    }

    /** Reservation quantity for one beneficial owner, used when identical stacks have mixed title. */
    public int reservedQuantity(UUID containerId, ItemFingerprint fingerprint,
                                SubjectRef reservedFor, String exceptToken, long gameTime) {
        return reservations.values().stream()
                .filter(value -> value.activeAt(gameTime)
                        && value.containerId().equals(containerId)
                        && value.fingerprint().equals(fingerprint)
                        && value.reservedFor().equals(reservedFor)
                        && (exceptToken == null || !value.token().equals(exceptToken)))
                .mapToInt(AssetReservation::quantity).sum();
    }

    public List<AssetReservation> reservationsForToken(String token, long gameTime) {
        return reservations.values().stream().filter(value -> value.activeAt(gameTime)
                && value.token().equals(token)).toList();
    }

    public void releaseReservations(String token) {
        if (reservations.entrySet().removeIf(entry -> entry.getValue().token().equals(token))) setDirty();
    }

    /** 延长同一业务令牌下仍有效的全部数量预留，返回续租数量。 */
    public int renewReservations(String token, long gameTime, long newExpiresAt) {
        if (token == null || token.isBlank() || gameTime < 0L || newExpiresAt <= gameTime) {
            throw new IllegalArgumentException("invalid reservation renewal");
        }
        pruneReservations(gameTime);
        int renewed = 0;
        for (AssetReservation reservation : List.copyOf(reservations.values())) {
            if (!reservation.token().equals(token)) continue;
            if (newExpiresAt > reservation.expiresAt()) {
                reservations.put(reservation.id(), reservation.withExpiresAt(newExpiresAt));
                renewed++;
            }
        }
        if (renewed > 0) setDirty();
        return renewed;
    }

    public void consumeReservation(String token, UUID containerId,
                                   ItemFingerprint fingerprint, int quantity) {
        int remaining = quantity;
        for (AssetReservation reservation : List.copyOf(reservations.values())) {
            if (remaining <= 0) break;
            if (!reservation.token().equals(token) || !reservation.containerId().equals(containerId)
                    || !reservation.fingerprint().equals(fingerprint)) continue;
            int used = Math.min(remaining, reservation.quantity());
            reservations.remove(reservation.id());
            if (used < reservation.quantity()) {
                AssetReservation reduced = reservation.withQuantity(reservation.quantity() - used);
                reservations.put(reduced.id(), reduced);
            }
            remaining -= used;
        }
        if (remaining != quantity) setDirty();
    }

    private void pruneReservations(long gameTime) {
        if (reservations.entrySet().removeIf(entry -> !entry.getValue().activeAt(gameTime))) setDirty();
    }

    /** Captures the rights-side state touched by one inventory transaction. */
    public TransactionSnapshot snapshotFor(Set<UUID> containerIds) {
        Map<UUID, ContainerContext> contextSnapshot = new HashMap<>();
        for (UUID id : containerIds) {
            ContainerContext context = contexts.get(id);
            if (context != null) contextSnapshot.put(id, context);
        }
        Map<UUID, AssetClaim> claimSnapshot = new HashMap<>();
        claims.forEach((id, claim) -> {
            if (containerIds.contains(claim.containerId())) claimSnapshot.put(id, claim);
        });
        Map<UUID, AssetReservation> reservationSnapshot = new HashMap<>();
        reservations.forEach((id, reservation) -> {
            if (containerIds.contains(reservation.containerId())) reservationSnapshot.put(id, reservation);
        });
        return new TransactionSnapshot(Set.copyOf(containerIds), Map.copyOf(contextSnapshot),
                Map.copyOf(claimSnapshot), Map.copyOf(reservationSnapshot));
    }

    /** Restores only the containers captured for a failed transfer; unrelated state is untouched. */
    public void restore(TransactionSnapshot snapshot) {
        snapshot.containerIds().forEach(contexts::remove);
        contexts.putAll(snapshot.contexts());
        claims.entrySet().removeIf(entry -> snapshot.containerIds().contains(entry.getValue().containerId()));
        claims.putAll(snapshot.claims());
        reservations.entrySet().removeIf(entry -> snapshot.containerIds().contains(
                entry.getValue().containerId()));
        reservations.putAll(snapshot.reservations());
        setDirty();
    }

    public record TransactionSnapshot(Set<UUID> containerIds,
                                      Map<UUID, ContainerContext> contexts,
                                      Map<UUID, AssetClaim> claims,
                                      Map<UUID, AssetReservation> reservations) {}

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("DataVersion", DATA_VERSION);
        ListTag contextList = new ListTag();
        contexts.values().forEach(context -> contextList.add(context.save()));
        tag.put("Contexts", contextList);
        ListTag grantList = new ListTag();
        grants.values().forEach(grant -> grantList.add(grant.save()));
        tag.put("Grants", grantList);
        ListTag claimList = new ListTag();
        claims.values().forEach(claim -> claimList.add(claim.save()));
        tag.put("Claims", claimList);
        ListTag reservationList = new ListTag();
        reservations.values().forEach(reservation -> reservationList.add(reservation.save()));
        tag.put("Reservations", reservationList);
        return tag;
    }

    private static RightsStateData load(CompoundTag tag, HolderLookup.Provider registries) {
        RightsStateData data = new RightsStateData();
        ListTag contexts = tag.getList("Contexts", Tag.TAG_COMPOUND);
        for (int i = 0; i < contexts.size(); i++) {
            ContainerContext context = ContainerContext.load(contexts.getCompound(i));
            data.contexts.put(context.containerId(), context);
        }
        ListTag grants = tag.getList("Grants", Tag.TAG_COMPOUND);
        for (int i = 0; i < grants.size(); i++) {
            RightGrant grant = RightGrant.load(grants.getCompound(i));
            data.grants.put(grant.id(), grant);
        }
        ListTag claims = tag.getList("Claims", Tag.TAG_COMPOUND);
        for (int i = 0; i < claims.size(); i++) {
            AssetClaim claim = AssetClaim.load(claims.getCompound(i));
            data.claims.put(claim.claimId(), claim);
        }
        ListTag reservations = tag.getList("Reservations", Tag.TAG_COMPOUND);
        for (int i = 0; i < reservations.size(); i++) {
            try {
                AssetReservation reservation = AssetReservation.load(reservations.getCompound(i));
                data.reservations.put(reservation.id(), reservation);
            } catch (RuntimeException ignored) { }
        }
        return data;
    }
}
