package com.jgeted.sagadyssey.knowledge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A belief belongs to a knower and retains source/provenance instead of becoming world truth. */
public record BeliefRecord(UUID id, UUID knowerId, FactKey key, String displayValue,
                           PrecisionBand precision, double confidence,
                           KnowledgeSourceKind source, String sourceLabel,
                           List<UUID> provenanceChain, long learnedAt, long lastConfirmedAt,
                           @Nullable Long staleAfter, DisclosurePolicy disclosure, long revision) {
    public BeliefRecord {
        if (id == null || knowerId == null || key == null || precision == null || source == null
                || disclosure == null) throw new IllegalArgumentException("invalid belief");
        displayValue = displayValue == null ? "" : displayValue;
        confidence = Math.max(0.0D, Math.min(1.0D, confidence));
        sourceLabel = sourceLabel == null ? "" : sourceLabel;
        provenanceChain = List.copyOf(provenanceChain == null ? List.of() : provenanceChain);
        revision = Math.max(0L, revision);
    }

    public boolean staleAt(long gameTime) { return staleAfter != null && gameTime > staleAfter; }

    public BeliefRecord reconfirmed(String value, PrecisionBand newPrecision, double newConfidence,
                                    long gameTime, @Nullable Long newStaleAfter) {
        return new BeliefRecord(id, knowerId, key, value, newPrecision,
                Math.max(confidence, newConfidence), source, sourceLabel, provenanceChain,
                learnedAt, gameTime, newStaleAfter, disclosure, revision + 1L);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Knower", knowerId);
        tag.put("Key", key.save());
        tag.putString("Value", displayValue);
        tag.putString("Precision", precision.name());
        tag.putDouble("Confidence", confidence);
        tag.putString("Source", source.name());
        tag.putString("SourceLabel", sourceLabel);
        ListTag chain = new ListTag();
        provenanceChain.forEach(value -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", value);
            chain.add(entry);
        });
        tag.put("Provenance", chain);
        tag.putLong("Learned", learnedAt);
        tag.putLong("Confirmed", lastConfirmedAt);
        if (staleAfter != null) tag.putLong("StaleAfter", staleAfter);
        tag.putString("Disclosure", disclosure.name());
        tag.putLong("Revision", revision);
        return tag;
    }

    public static BeliefRecord load(CompoundTag tag) {
        List<UUID> chain = new ArrayList<>();
        ListTag list = tag.getList("Provenance", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            if (entry.hasUUID("Id")) chain.add(entry.getUUID("Id"));
        }
        return new BeliefRecord(tag.getUUID("Id"), tag.getUUID("Knower"),
                FactKey.load(tag.getCompound("Key")), tag.getString("Value"),
                PrecisionBand.valueOf(tag.getString("Precision")), tag.getDouble("Confidence"),
                KnowledgeSourceKind.valueOf(tag.getString("Source")), tag.getString("SourceLabel"),
                chain, tag.getLong("Learned"), tag.getLong("Confirmed"),
                tag.contains("StaleAfter") ? tag.getLong("StaleAfter") : null,
                DisclosurePolicy.valueOf(tag.getString("Disclosure")), tag.getLong("Revision"));
    }
}
