package com.jgeted.sagadyssey.knowledge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeStateDataTest {
    @Test
    void repeatedSameSourceReconfirmsWithoutConfidenceInflation() {
        KnowledgeStateData data = new KnowledgeStateData();
        UUID knower = UUID.randomUUID();
        FactKey key = new FactKey(UUID.randomUUID(), "need.food", "grain");
        data.observe(knower, key, "似乎缺粮", PrecisionBand.IMPRESSION, 0.6D,
                "观察", 10L, 100L, DisclosurePolicy.SHAREABLE);
        BeliefRecord updated = data.observe(knower, key, "似乎缺粮", PrecisionBand.IMPRESSION,
                0.6D, "观察", 20L, 100L, DisclosurePolicy.SHAREABLE);

        assertEquals(1, data.beliefsAbout(knower, key.subjectId()).size());
        assertEquals(0.6D, updated.confidence(), 0.0001D);
        assertEquals(20L, updated.lastConfirmedAt());
        assertFalse(updated.staleAt(120L));
        assertTrue(updated.staleAt(121L));
    }

    @Test
    void independentConflictingSourcesRemainSeparate() {
        KnowledgeStateData data = new KnowledgeStateData();
        UUID knower = UUID.randomUUID();
        FactKey key = new FactKey(UUID.randomUUID(), "household.name", "");
        data.testimony(knower, UUID.randomUUID(), key, "河岸家", PrecisionBand.CATEGORY,
                0.5D, 10L, 1_000L, DisclosurePolicy.SHAREABLE);
        data.document(knower, UUID.randomUUID(), key, "石桥家", PrecisionBand.EXACT,
                0.9D, 11L, 10_000L, DisclosurePolicy.PUBLIC);

        var beliefs = data.beliefsAbout(knower, key.subjectId());
        assertEquals(2, beliefs.size());
        assertEquals(2, beliefs.stream().map(BeliefRecord::displayValue).distinct().count());
    }

    @Test
    void beliefNbtRoundTripPreservesProvenanceAndDisclosure() {
        KnowledgeStateData data = new KnowledgeStateData();
        UUID knower = UUID.randomUUID();
        UUID speaker = UUID.randomUUID();
        FactKey key = new FactKey(UUID.randomUUID(), "identity.profession", "");
        BeliefRecord original = data.selfReport(knower, speaker, key, "铁匠学徒",
                PrecisionBand.CATEGORY, 0.8D, 30L, 200L, DisclosurePolicy.PRIVATE);

        BeliefRecord loaded = BeliefRecord.load(original.save());

        assertEquals(original, loaded);
        assertEquals(java.util.List.of(speaker), loaded.provenanceChain());
    }
}
