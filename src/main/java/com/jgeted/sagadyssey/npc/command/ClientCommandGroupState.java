package com.jgeted.sagadyssey.npc.command;

import com.jgeted.sagadyssey.npc.command.network.CommandGroupSyncPayload;

/** 客户端最近一次集团快照，供指挥界面和世界选框共同读取。 */
public final class ClientCommandGroupState {
    private static CommandGroupSyncPayload snapshot = new CommandGroupSyncPayload(
            false, PlayerCommandData.MAIN_SQUAD, java.util.List.of(), java.util.List.of(), null, null);

    private ClientCommandGroupState() {}

    public static void update(CommandGroupSyncPayload payload) { snapshot = payload; }
    public static CommandGroupSyncPayload snapshot() { return snapshot; }
}
