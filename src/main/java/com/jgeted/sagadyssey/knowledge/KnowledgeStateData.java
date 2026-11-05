package com.jgeted.sagadyssey.knowledge;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** World-persistent subjective knowledge. Truth remains in its owning domain model. */
public final class KnowledgeStateData extends SavedData {
    public static final String DATA_NAME = "sagadyssey_knowledge_v2";
    public static final Factory<KnowledgeStateData> FACTORY =
            new Factory<>(KnowledgeStateData::new, KnowledgeStateData::load);

    private final Map<UUID, BeliefRecord> beliefs = new LinkedHashMap<>();

    public static KnowledgeStateData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Collection<BeliefRecord> beliefs() { return List.copyOf(beliefs.values()); }

    public List<BeliefRecord> beliefsAbout(UUID knowerId, UUID subjectId) {
        return beliefs.values().stream().filter(value -> value.knowerId().equals(knowerId)
                        && value.key().subjectId().equals(subjectId))
                .sorted(Comparator.comparingLong(BeliefRecord::lastConfirmedAt).reversed()).toList();
    }

    public BeliefRecord observe(UUID knowerId, FactKey key, String value, PrecisionBand precision,
                                double confidence, String sourceLabel, long gameTime,
                                long validForTicks, DisclosurePolicy disclosure) {
        return learn(knowerId, key, value, precision, confidence, KnowledgeSourceKind.OBSERVATION,
                sourceLabel, List.of(), gameTime, validForTicks, disclosure);
    }

    public BeliefRecord selfReport(UUID knowerId, UUID speakerId, FactKey key, String value,
                                   PrecisionBand precision, double confidence, long gameTime,
                                   long validForTicks, DisclosurePolicy disclosure) {
        return learn(knowerId, key, value, precision, confidence, KnowledgeSourceKind.SELF_REPORT,
                "当事人自述", List.of(speakerId), gameTime, validForTicks, disclosure);
    }

    public BeliefRecord testimony(UUID knowerId, UUID witnessId, FactKey key, String value,
                                  PrecisionBand precision, double confidence, long gameTime,
                                  long validForTicks, DisclosurePolicy disclosure) {
        return learn(knowerId, key, value, precision, confidence, KnowledgeSourceKind.TESTIMONY,
                "他人证词", List.of(witnessId), gameTime, validForTicks, disclosure);
    }

    public BeliefRecord document(UUID knowerId, UUID documentId, FactKey key, String value,
                                 PrecisionBand precision, double confidence, long gameTime,
                                 long validForTicks, DisclosurePolicy disclosure) {
        return learn(knowerId, key, value, precision, confidence, KnowledgeSourceKind.DOCUMENT,
                "文书记录", List.of(documentId), gameTime, validForTicks, disclosure);
    }

    private BeliefRecord learn(UUID knowerId, FactKey key, String value, PrecisionBand precision,
                               double confidence, KnowledgeSourceKind source, String sourceLabel,
                               List<UUID> provenance, long gameTime, long validForTicks,
                               DisclosurePolicy disclosure) {
        UUID id = stableId(knowerId, key, source, provenance.isEmpty() ? null : provenance.getFirst());
        Long staleAfter = validForTicks <= 0L ? null : gameTime + validForTicks;
        BeliefRecord existing = beliefs.get(id);
        BeliefRecord updated = existing == null
                ? new BeliefRecord(id, knowerId, key, value, precision, confidence, source,
                sourceLabel, provenance, gameTime, gameTime, staleAfter, disclosure, 0L)
                : existing.reconfirmed(value, precision, confidence, gameTime, staleAfter);
        beliefs.put(id, updated);
        setDirty();
        return updated;
    }

    private static UUID stableId(UUID knowerId, FactKey key, KnowledgeSourceKind source,
                                 @Nullable UUID origin) {
        String material = knowerId + ":" + key.subjectId() + ':' + key.predicate() + ':'
                + key.qualifier() + ':' + source + ':' + (origin == null ? "direct" : origin);
        return UUID.nameUUIDFromBytes(("sagadyssey:belief:" + material)
                .getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        beliefs.values().forEach(value -> list.add(value.save()));
        tag.put("Beliefs", list);
        return tag;
    }

    private static KnowledgeStateData load(CompoundTag tag, HolderLookup.Provider registries) {
        KnowledgeStateData data = new KnowledgeStateData();
        ListTag list = tag.getList("Beliefs", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            try {
                BeliefRecord belief = BeliefRecord.load(list.getCompound(index));
                data.beliefs.put(belief.id(), belief);
            } catch (RuntimeException ignored) { }
        }
        return data;
    }
}
