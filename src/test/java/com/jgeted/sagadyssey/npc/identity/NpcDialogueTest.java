package com.jgeted.sagadyssey.npc.identity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NpcDialogueTest {
    @Test
    void repeatedConversationCyclesThroughAvailableWorldTopics() {
        List<String> topics = List.of("正在运粮", "记得玩家的帮助", "谈随身货物");

        assertEquals("正在运粮", NpcDialogue.selectTopic(topics, 0));
        assertEquals("记得玩家的帮助", NpcDialogue.selectTopic(topics, 1));
        assertEquals("谈随身货物", NpcDialogue.selectTopic(topics, 2));
        assertEquals("正在运粮", NpcDialogue.selectTopic(topics, 3));
    }
}
