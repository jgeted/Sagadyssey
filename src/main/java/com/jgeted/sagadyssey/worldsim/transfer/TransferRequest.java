package com.jgeted.sagadyssey.worldsim.transfer;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TransferRequest(UUID transferId, SubjectRef initiatedBy, TransferIntent intent,
                              List<TransferLeg> legs, List<ExpectedRevision> expectedRevisions,
                              String authority, long requestedAt,
                              Map<UUID, SubjectRef> sourceAuthorities) {
    public TransferRequest(UUID transferId, SubjectRef initiatedBy, TransferIntent intent,
                           List<TransferLeg> legs, List<ExpectedRevision> expectedRevisions,
                           String authority, long requestedAt) {
        this(transferId, initiatedBy, intent, legs, expectedRevisions, authority, requestedAt,
                Map.of());
    }

    /**
     * Durable intent used for replay checks. Retry timestamps are observational;
     * leg order, revisions, actors and authority are part of the request identity.
     */
    public net.minecraft.nbt.CompoundTag identity() {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("Version", 1);
        tag.put("Actor", initiatedBy.save());
        tag.putString("Intent", intent.name());
        tag.putString("Authority", authority);
        var legTags = new net.minecraft.nbt.ListTag();
        for (var leg : legs) {
            var entry = new net.minecraft.nbt.CompoundTag();
            entry.store("Source", UUIDUtil.CODEC, leg.sourceContainerId());
            entry.store("Destination", UUIDUtil.CODEC, leg.destinationContainerId());
            entry.put("Item", leg.fingerprint().save());
            entry.putInt("Quantity", leg.quantity());
            legTags.add(entry);
        }
        tag.put("Legs", legTags);
        var revisionTags = new net.minecraft.nbt.ListTag();
        expectedRevisions.stream().sorted(java.util.Comparator
                .comparing((ExpectedRevision revision) -> revision.containerId().toString())
                .thenComparingLong(ExpectedRevision::revision)).forEach(revision -> {
                    var entry = new net.minecraft.nbt.CompoundTag();
                    entry.store("Container", UUIDUtil.CODEC, revision.containerId());
                    entry.putLong("Revision", revision.revision());
                    revisionTags.add(entry);
                });
        tag.put("ExpectedRevisions", revisionTags);
        var authorityTags = new net.minecraft.nbt.ListTag();
        sourceAuthorities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).forEach(authorityEntry -> {
                    var entry = new net.minecraft.nbt.CompoundTag();
                    entry.store("Container", UUIDUtil.CODEC, authorityEntry.getKey());
                    entry.put("Actor", authorityEntry.getValue().save());
                    authorityTags.add(entry);
                });
        tag.put("SourceAuthorities", authorityTags);
        return tag;
    }

    public TransferRequest {
        if (transferId == null || initiatedBy == null || intent == null || legs == null || legs.isEmpty()) {
            throw new IllegalArgumentException("incomplete transfer request");
        }
        legs = List.copyOf(legs);
        expectedRevisions = List.copyOf(expectedRevisions == null ? List.of() : expectedRevisions);
        authority = authority == null ? "" : authority;
        sourceAuthorities = Map.copyOf(sourceAuthorities == null ? Map.of() : sourceAuthorities);
    }
}
