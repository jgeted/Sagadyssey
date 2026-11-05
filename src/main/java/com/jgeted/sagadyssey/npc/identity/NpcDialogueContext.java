package com.jgeted.sagadyssey.npc.identity;

/** 需要按社会身份调整语气的 NPC 对话情境。 */
public enum NpcDialogueContext {
    GREETING("greeting"),
    CONVERSATION("conversation"),
    COMMAND("command"),
    TRADE("trade");

    private final String id;

    NpcDialogueContext(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
