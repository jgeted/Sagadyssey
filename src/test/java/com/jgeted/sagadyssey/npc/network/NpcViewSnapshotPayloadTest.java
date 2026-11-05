package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.knowledge.projection.NpcViewSnapshot;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NpcViewSnapshotPayloadTest {
    @Test
    void ordinaryProjectionSchemaHasNoAuthoritativeCombatOrInventoryFields() {
        Set<String> forbidden = Set.of("currentHp", "maxHp", "attackDamage", "speed", "armor",
                "moral", "rawTrades", "inventory", "personalityScore", "decisionScore");
        Set<String> components = Arrays.stream(NpcViewSnapshot.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(java.util.Collections.disjoint(forbidden, components));
    }

    @Test
    void payloadRoundTripContainsOnlyProjectedDescriptions() {
        NpcViewSnapshot original = new NpcViewSnapshot(7, UUID.randomUUID(), 12L,
                new NpcViewSnapshot.DisplayIdentity("玛拉", "自由民", "铁匠学徒",
                        "所属尚不明确", "女性 · 沉静"), "尚未建立正式关系",
                "<玛拉> 我正等着下一批铁料。",
                List.of(new NpcViewSnapshot.ObservedCondition("身体", "似乎负伤", 1)),
                List.of(), List.of(new NpcViewSnapshot.SystemInsight("战斗", "身体状态",
                        "动作受限", "只显示可观察迹象", 1,
                        com.jgeted.sagadyssey.knowledge.PrecisionBand.IMPRESSION,
                        com.jgeted.sagadyssey.knowledge.FreshnessBand.CURRENT, "当面观察")),
                List.of(new NpcViewSnapshot.AvailableInteraction(
                        "talk", "交谈", "交流", "询问近况")), List.of(), List.of(), 200L);
        var buffer = Unpooled.buffer();

        NpcViewSnapshotPayload.STREAM_CODEC.encode(buffer, new NpcViewSnapshotPayload(original));
        NpcViewSnapshot decoded = NpcViewSnapshotPayload.STREAM_CODEC.decode(buffer).view();

        assertEquals(original, decoded);
        buffer.release();
    }
}
