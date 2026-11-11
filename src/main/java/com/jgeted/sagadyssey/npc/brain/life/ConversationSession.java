package com.jgeted.sagadyssey.npc.brain.life;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.util.Objects;
import java.util.UUID;

/** Immutable rendezvous checkpoint. Both participants start together only after real arrival. */
public record ConversationSession(UUID id, UUID first, UUID second, String dimension, BlockPos place,
                                  int durationTicks, long createdAt, long expiresAt, long startedAt,
                                  long updatedAt, Status status) {
    public enum Status { INVITED, TALKING, SUCCEEDED, CANCELLED, EXPIRED;
        public boolean terminal() { return this == SUCCEEDED || this == CANCELLED || this == EXPIRED; }
    }

    public ConversationSession {
        Objects.requireNonNull(id); Objects.requireNonNull(first); Objects.requireNonNull(second);
        Objects.requireNonNull(status); Objects.requireNonNull(place);
        place = place.immutable();
        if (!BlockPos.of(place.asLong()).equals(place)) throw new IllegalArgumentException("place cannot be persisted losslessly");
        if (first.equals(second) || dimension == null || dimension.isBlank()) throw new IllegalArgumentException("invalid participants/place");
        if (durationTicks < 20 || durationTicks > 24000 || createdAt < 0 || expiresAt <= createdAt
                || updatedAt < createdAt || startedAt < -1 || startedAt > updatedAt
                || (startedAt >= 0 && startedAt < createdAt)
                || (status == Status.INVITED && startedAt != -1)
                || ((status == Status.TALKING || status == Status.SUCCEEDED) && startedAt == -1)
                || (status == Status.SUCCEEDED && updatedAt - startedAt < durationTicks))
            throw new IllegalArgumentException("invalid conversation checkpoint");
    }

    public static ConversationSession invite(UUID id, UUID first, UUID second, String dimension,
                                              BlockPos place, int duration, long now, long deadline) {
        return new ConversationSession(id, first, second, dimension, place, duration, now, deadline, -1, now, Status.INVITED);
    }

    /** Caller supplies fresh perception, mutual consent and leases, never global hidden knowledge. */
    public ConversationSession tick(long now, boolean bothPresent, boolean mutuallyAvailable) {
        if (now < updatedAt) throw new IllegalArgumentException("conversation time moved backwards");
        if (status.terminal()) return this;
        if (!mutuallyAvailable) return change(now, startedAt, Status.CANCELLED);
        if (now >= expiresAt) return change(now, startedAt, Status.EXPIRED);
        if (status == Status.INVITED)
            return change(now, bothPresent ? now : -1, bothPresent ? Status.TALKING : Status.INVITED);
        // A vanished/unloaded partner ends this encounter, not a one-sided successful conversation.
        if (!bothPresent) return change(now, startedAt, Status.CANCELLED);
        return change(now, startedAt, now - startedAt >= durationTicks ? Status.SUCCEEDED : Status.TALKING);
    }

    /** Restart must re-establish mutual presence; offline time cannot finish a conversation. */
    public ConversationSession recover(long now) {
        if (now < updatedAt) throw new IllegalArgumentException("conversation time moved backwards");
        if (status.terminal()) return this;
        return change(now, -1, now >= expiresAt ? Status.EXPIRED : Status.INVITED);
    }

    private ConversationSession change(long now, long start, Status next) {
        return new ConversationSession(id, first, second, dimension, place, durationTicks,
                createdAt, expiresAt, start, now, next);
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id); tag.store("First", UUIDUtil.CODEC, first); tag.store("Second", UUIDUtil.CODEC, second);
        tag.putString("Dimension", dimension); tag.putLong("Place", place.asLong());
        tag.putInt("Duration", durationTicks); tag.putLong("Created", createdAt);
        tag.putLong("Expires", expiresAt); tag.putLong("Started", startedAt);
        tag.putLong("Updated", updatedAt); tag.putString("Status", status.name());
        return tag;
    }

    public static ConversationSession load(CompoundTag tag) {
        for (String field : new String[]{"Id", "First", "Second"})
            if (!tag.read(field, UUIDUtil.CODEC).isPresent()) throw new IllegalArgumentException("missing " + field);
        for (String field : new String[]{"Place", "Created", "Expires", "Started", "Updated"})
            if (!MinecraftApi.containsTag(tag, field, Tag.TAG_LONG)) throw new IllegalArgumentException("missing/wrong " + field);
        if (!MinecraftApi.containsTag(tag, "Duration", Tag.TAG_INT) || !MinecraftApi.containsTag(tag, "Dimension", Tag.TAG_STRING)
                || !MinecraftApi.containsTag(tag, "Status", Tag.TAG_STRING)) throw new IllegalArgumentException("malformed checkpoint");
        return new ConversationSession(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("First", UUIDUtil.CODEC).orElseThrow(), tag.read("Second", UUIDUtil.CODEC).orElseThrow(),
                tag.getStringOr("Dimension", ""), BlockPos.of(tag.getLongOr("Place", 0L)), tag.getIntOr("Duration", 0),
                tag.getLongOr("Created", 0L), tag.getLongOr("Expires", 0L), tag.getLongOr("Started", 0L),
                tag.getLongOr("Updated", 0L), Status.valueOf(tag.getStringOr("Status", "")));
    }
}
