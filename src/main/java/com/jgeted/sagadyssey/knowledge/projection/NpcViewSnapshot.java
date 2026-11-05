package com.jgeted.sagadyssey.knowledge.projection;

import com.jgeted.sagadyssey.knowledge.ConflictState;
import com.jgeted.sagadyssey.knowledge.FreshnessBand;
import com.jgeted.sagadyssey.knowledge.KnowledgeSourceKind;
import com.jgeted.sagadyssey.knowledge.PrecisionBand;

import java.util.List;
import java.util.UUID;

/** Observer-specific read model. Omitted truth is intentionally absent, not encoded as a sentinel. */
public record NpcViewSnapshot(int bodyEntityId, UUID characterId, long projectionRevision,
                              DisplayIdentity identity, String relationship,
                              String conversation,
                              List<ObservedCondition> conditions, List<KnownFact> knownFacts,
                              List<SystemInsight> systemInsights,
                              List<AvailableInteraction> actions, List<VisibleAsset> visibleAssets,
                              List<KnownAgreement> agreements, long expiresAt) {
    public NpcViewSnapshot {
        conversation = conversation == null ? "" : conversation;
        conditions = List.copyOf(conditions);
        knownFacts = List.copyOf(knownFacts);
        systemInsights = List.copyOf(systemInsights);
        actions = List.copyOf(actions);
        visibleAssets = List.copyOf(visibleAssets);
        agreements = List.copyOf(agreements);
    }

    public record DisplayIdentity(String name, String publicRole, String profession,
                                  String affiliation, String appearance) {}
    public record ObservedCondition(String label, String description, int severity) {}
    public record KnownFact(String label, String displayValue, PrecisionBand precision,
                            KnowledgeSourceKind sourceKind, String sourceLabel,
                            FreshnessBand freshness, ConflictState conflict,
                            boolean exactValueAvailable) {}
    public record SystemInsight(String category, String title, String summary, String detail,
                                int urgency, PrecisionBand precision,
                                FreshnessBand freshness, String sourceLabel) {
        public SystemInsight {
            urgency = Math.max(0, Math.min(3, urgency));
        }
    }
    public record AvailableInteraction(String actionId, String label, String category,
                                       String explanation) {}
    public record VisibleAsset(String location, String itemId, String description,
                               PrecisionBand quantityPrecision) {}
    public record KnownAgreement(String title, String status, String terms, long endsAt) {}
}
