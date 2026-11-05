package com.jgeted.sagadyssey.knowledge.projection;

import com.jgeted.sagadyssey.knowledge.BeliefRecord;
import com.jgeted.sagadyssey.knowledge.ConflictState;
import com.jgeted.sagadyssey.knowledge.DisclosurePolicy;
import com.jgeted.sagadyssey.knowledge.FactKey;
import com.jgeted.sagadyssey.knowledge.KnowledgeSourceKind;
import com.jgeted.sagadyssey.knowledge.PrecisionBand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NpcProjectionTest {
    @Test
    void observerRolesResolveToDistinctProjectionClasses() {
        assertEquals(NpcProjectionService.ObserverClass.STRANGER,
                NpcProjectionService.observerClass(false, false, false, false));
        assertEquals(NpcProjectionService.ObserverClass.HOUSEHOLD,
                NpcProjectionService.observerClass(false, true, false, false));
        assertEquals(NpcProjectionService.ObserverClass.SERVICE_PARTY,
                NpcProjectionService.observerClass(true, false, false, false));
        assertEquals(NpcProjectionService.ObserverClass.WAREHOUSE_STEWARD,
                NpcProjectionService.observerClass(false, false, true, false));
        assertEquals(NpcProjectionService.ObserverClass.ADMIN,
                NpcProjectionService.observerClass(false, false, false, true));
    }

    @Test
    void conflictingBeliefsBecomeContestedAndStaleInformationIsMarked() {
        UUID knower = UUID.randomUUID();
        FactKey key = new FactKey(UUID.randomUUID(), "need.food", "");
        BeliefRecord oldRumor = new BeliefRecord(UUID.randomUUID(), knower, key,
                "粮食充足", PrecisionBand.IMPRESSION, 0.4D, KnowledgeSourceKind.TESTIMONY,
                "路人传闻", List.of(UUID.randomUUID()), 10L, 10L, 20L,
                DisclosurePolicy.SHAREABLE, 0L);
        BeliefRecord document = new BeliefRecord(UUID.randomUUID(), knower, key,
                "正在缺粮", PrecisionBand.CATEGORY, 0.9D, KnowledgeSourceKind.DOCUMENT,
                "仓库清册", List.of(UUID.randomUUID()), 30L, 30L, 100L,
                DisclosurePolicy.PUBLIC, 0L);

        var projected = NpcProjectionService.projectBeliefs(List.of(oldRumor, document), 40L);

        assertEquals(1, projected.size());
        assertEquals(ConflictState.CONTESTED, projected.getFirst().conflict());
        assertEquals("正在缺粮", projected.getFirst().displayValue());
    }
}
