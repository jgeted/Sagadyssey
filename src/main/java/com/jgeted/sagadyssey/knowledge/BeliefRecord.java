package com.jgeted.sagadyssey.knowledge;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

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
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.store("Knower", UUIDUtil.CODEC, knowerId);
        tag.put("Key", key.save());
        tag.putString("Value", displayValue);
        tag.putString("Precision", precision.name());
        tag.putDouble("Confidence", confidence);
        tag.putString("Source", source.name());
        tag.putString("SourceLabel", sourceLabel);
        ListTag chain = new ListTag();
        provenanceChain.forEach(value -> {
            CompoundTag entry = new CompoundTag();
            entry.store("Id", UUIDUtil.CODEC, value);
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
        ListTag list = MinecraftApi.listOfType(tag, "Provenance", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompoundOrEmpty(index);
            if (entry.read("Id", UUIDUtil.CODEC).isPresent()) chain.add(entry.read("Id", UUIDUtil.CODEC).orElseThrow());
        }
        return new BeliefRecord(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), tag.read("Knower", UUIDUtil.CODEC).orElseThrow(),
                FactKey.load(tag.getCompoundOrEmpty("Key")), tag.getStringOr("Value", ""),
                PrecisionBand.valueOf(tag.getStringOr("Precision", "")), tag.getDoubleOr("Confidence", 0.0),
                KnowledgeSourceKind.valueOf(tag.getStringOr("Source", "")), tag.getStringOr("SourceLabel", ""),
                chain, tag.getLongOr("Learned", 0L), tag.getLongOr("Confirmed", 0L),
                tag.contains("StaleAfter") ? tag.getLongOr("StaleAfter", 0L) : null,
                DisclosurePolicy.valueOf(tag.getStringOr("Disclosure", "")), tag.getLongOr("Revision", 0L));
    }
}
