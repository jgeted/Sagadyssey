package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import java.util.Objects;
import java.util.UUID;

/** Durable intent, not an inventory. Counters advance only after an asset-service receipt. */
public record NeedRequest(UUID id, Key key, int originalRequested, int requested, int reserved, int inTransit,
                          int delivered, NeedRequestStatus status, long createdAt,
                          long updatedAt, int attempts, int maxAttempts, String detail) {
    public record Key(UUID requester, String purpose, ItemFingerprint item,
                      UUID destination, long windowStart, long deadline) {
        public Key {
            Objects.requireNonNull(requester); Objects.requireNonNull(destination);
            Objects.requireNonNull(item);
            if (purpose == null || purpose.isBlank() || purpose.length() > 512
                    || item.itemId().length() > 256 || ResourceLocation.tryParse(item.itemId()) == null
                    || windowStart < 0 || deadline <= windowStart)
                throw new IllegalArgumentException("invalid demand identity");
        }
    }

    public NeedRequest {
        Objects.requireNonNull(id); Objects.requireNonNull(key); Objects.requireNonNull(status);
        if (originalRequested <= 0 || requested <= 0 || reserved < 0 || inTransit < 0 || delivered < 0
                || (long) reserved + inTransit + delivered > requested
                || createdAt < key.windowStart() || createdAt >= key.deadline()
                || updatedAt < createdAt || attempts < 0 || maxAttempts < 1
                || maxAttempts > 1_000 || attempts > maxAttempts
                || detail == null || detail.length() > 2_048)
            throw new IllegalArgumentException("invalid demand progress");
        if ((status == NeedRequestStatus.DELIVERED || status == NeedRequestStatus.CONSUMED
                || status == NeedRequestStatus.CLOSED) && delivered != requested)
            throw new IllegalArgumentException("unfulfilled demand cannot close");
        if (status == NeedRequestStatus.CANCELLED && (reserved != 0 || inTransit != 0))
            throw new IllegalArgumentException("release reservations and reconcile cargo before cancellation");
        if ((status == NeedRequestStatus.OPEN || status == NeedRequestStatus.WAITING_EXTERNAL
                || status == NeedRequestStatus.BLOCKED_CYCLE) && (reserved != 0 || inTransit != 0))
            throw new IllegalArgumentException("unmatched demand cannot conceal held assets");
        if (status == NeedRequestStatus.MATCHED && (reserved == 0 || inTransit != 0))
            throw new IllegalArgumentException("matched demand requires reserved assets");
        if (status == NeedRequestStatus.IN_TRANSIT && inTransit == 0)
            throw new IllegalArgumentException("in-transit demand requires carried assets");
    }

    public static NeedRequest open(Key key, int quantity, int maxAttempts, long now) {
        return new NeedRequest(UUID.randomUUID(), key, quantity, quantity, 0, 0, 0,
                NeedRequestStatus.OPEN, now, now, 0, maxAttempts, "");
    }

    /** Uncommitted quantity only; in-transit goods must never be promised twice. */
    public int remaining() { return requested - reserved - inTransit - delivered; }

    public NeedRequest resize(int quantity, long now) {
        active(now);
        if (status == NeedRequestStatus.DELIVERED) throw new IllegalStateException("already delivered");
        return copy(quantity, reserved, inTransit, delivered, status, now, attempts, detail);
    }

    public NeedRequest reserve(int quantity, long now) {
        active(now); positive(quantity);
        if (now >= key.deadline() || attempts >= maxAttempts
                || (status != NeedRequestStatus.OPEN && status != NeedRequestStatus.MATCHED
                && status != NeedRequestStatus.IN_TRANSIT) || quantity > remaining())
            throw new IllegalStateException("demand cannot reserve more goods");
        return copy(requested, Math.addExact(reserved, quantity), inTransit, delivered,
                inTransit > 0 ? NeedRequestStatus.IN_TRANSIT : NeedRequestStatus.MATCHED,
                now, attempts, detail);
    }

    /** Only the ledger's once-only natural-food receipt may authorize this exhausted reserve. */
    NeedRequest reserveNaturalFoodEmergency(int quantity, long now) {
        active(now); positive(quantity);
        if (!key.purpose().equals("HOUSEHOLD:household:natural-diet:" + key.requester())
                || !key.item().equals(new ItemFingerprint("minecraft:carrot", 0))
                || attempts != 32 || maxAttempts != 32 || now >= key.deadline()
                || reserved != 0 || inTransit != 0 || quantity > remaining()
                || (status != NeedRequestStatus.OPEN && status != NeedRequestStatus.WAITING_EXTERNAL
                && status != NeedRequestStatus.UNREACHABLE))
            throw new IllegalStateException("exhausted natural food request cannot take emergency reserve");
        return copy(requested, quantity, 0, delivered, NeedRequestStatus.MATCHED, now, attempts, detail);
    }

    public NeedRequest dispatch(int quantity, long now) {
        active(now); positive(quantity);
        if ((status != NeedRequestStatus.MATCHED && status != NeedRequestStatus.IN_TRANSIT
                && status != NeedRequestStatus.UNREACHABLE)
                || quantity > reserved) throw new IllegalStateException("no reserved goods to dispatch");
        return copy(requested, reserved - quantity, Math.addExact(inTransit, quantity), delivered,
                NeedRequestStatus.IN_TRANSIT, now, attempts, detail);
    }

    public NeedRequest deliver(int quantity, long now) {
        active(now); positive(quantity);
        if ((status != NeedRequestStatus.IN_TRANSIT && status != NeedRequestStatus.UNREACHABLE)
                || quantity > inTransit) throw new IllegalStateException("no carried goods to deliver");
        int total = Math.addExact(delivered, quantity);
        return copy(requested, reserved, inTransit - quantity, total,
                total == requested ? NeedRequestStatus.DELIVERED
                        : inTransit > quantity ? NeedRequestStatus.IN_TRANSIT
                        : reserved > 0 ? NeedRequestStatus.MATCHED : NeedRequestStatus.OPEN,
                now, attempts, detail);
    }

    public NeedRequest releaseReserved(int quantity, long now) {
        active(now); positive(quantity);
        if (quantity > reserved) throw new IllegalStateException("reservation release exceeds held quantity");
        return copy(requested, reserved - quantity, inTransit, delivered,
                status == NeedRequestStatus.MATCHED && reserved == quantity
                        ? NeedRequestStatus.OPEN : status, now, attempts, detail);
    }

    public NeedRequest transition(NeedRequestStatus next, long now, String reason) {
        active(now);
        if (!status.canTransitionTo(next)) throw new IllegalStateException("invalid demand transition");
        if (next == NeedRequestStatus.MATCHED || next == NeedRequestStatus.IN_TRANSIT
                || next == NeedRequestStatus.DELIVERED)
            throw new IllegalStateException("fulfillment requires an asset receipt");
        if (next == NeedRequestStatus.OPEN && (reserved > 0 || inTransit > 0))
            throw new IllegalStateException("reconcile held assets before reopening");
        if ((next == NeedRequestStatus.WAITING_EXTERNAL || next == NeedRequestStatus.BLOCKED_CYCLE)
                && (reserved > 0 || inTransit > 0))
            throw new IllegalStateException("held goods cannot be classified as external shortage");
        return copy(requested, reserved, inTransit, delivered, next, now, attempts, reason);
    }

    public NeedRequest failedAttempt(long now, String reason) {
        active(now);
        if (attempts >= maxAttempts || now >= key.deadline())
            throw new IllegalStateException("demand retry budget exhausted");
        return copy(requested, reserved, inTransit, delivered, status, now, attempts + 1, reason);
    }

    private void active(long now) {
        if (status.terminal()) throw new IllegalStateException("terminal demand is immutable");
        if (now < updatedAt) throw new IllegalArgumentException("demand clock moved backwards");
    }
    private static void positive(int value) {
        if (value <= 0) throw new IllegalArgumentException("positive quantity required");
    }
    private NeedRequest copy(int quantity, int held, int carried, int received,
                             NeedRequestStatus next, long now, int retries, String reason) {
        return new NeedRequest(id, key, originalRequested, quantity, held, carried, received, next,
                createdAt, now, retries, maxAttempts, reason);
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putUUID("Id", id); tag.putUUID("Requester", key.requester());
        tag.putString("Purpose", key.purpose()); tag.put("Item", key.item().save());
        tag.putUUID("Destination", key.destination()); tag.putLong("WindowStart", key.windowStart());
        tag.putLong("Deadline", key.deadline()); tag.putInt("OriginalRequested", originalRequested);
        tag.putInt("Requested", requested);
        tag.putInt("Reserved", reserved); tag.putInt("InTransit", inTransit);
        tag.putInt("Delivered", delivered); tag.putString("Status", status.name());
        tag.putLong("CreatedAt", createdAt); tag.putLong("UpdatedAt", updatedAt);
        tag.putInt("Attempts", attempts); tag.putInt("MaxAttempts", maxAttempts);
        tag.putString("Detail", detail); return tag;
    }

    public static NeedRequest load(CompoundTag tag) {
        for (String name : new String[]{"Id", "Requester", "Destination"})
            if (!tag.hasUUID(name)) throw new IllegalArgumentException("missing demand UUID " + name);
        for (String name : new String[]{"Purpose", "Status", "Detail"}) require(tag, name, Tag.TAG_STRING);
        for (String name : new String[]{"WindowStart", "Deadline", "CreatedAt", "UpdatedAt"}) require(tag, name, Tag.TAG_LONG);
        for (String name : new String[]{"OriginalRequested", "Requested", "Reserved", "InTransit", "Delivered", "Attempts", "MaxAttempts"}) require(tag, name, Tag.TAG_INT);
        require(tag, "Item", Tag.TAG_COMPOUND);
        var item = tag.getCompound("Item");
        require(item, "Item", Tag.TAG_STRING); require(item, "Components", Tag.TAG_INT);
        return new NeedRequest(tag.getUUID("Id"), new Key(tag.getUUID("Requester"),
                tag.getString("Purpose"), ItemFingerprint.load(item), tag.getUUID("Destination"),
                tag.getLong("WindowStart"), tag.getLong("Deadline")), tag.getInt("OriginalRequested"), tag.getInt("Requested"),
                tag.getInt("Reserved"), tag.getInt("InTransit"), tag.getInt("Delivered"),
                NeedRequestStatus.valueOf(tag.getString("Status")), tag.getLong("CreatedAt"),
                tag.getLong("UpdatedAt"), tag.getInt("Attempts"), tag.getInt("MaxAttempts"), tag.getString("Detail"));
    }
    private static void require(CompoundTag tag, String name, int type) {
        if (!tag.contains(name, type)) throw new IllegalArgumentException("missing demand field " + name);
    }
}
