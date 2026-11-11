package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.npc.gui.NpcTradeScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client-only trade-screen refresh behavior. */
final class ClientNpcTradeResponseHandler {
    private ClientNpcTradeResponseHandler() {}

    static void handle(NpcTradeResponsePacket data, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof NpcTradeScreen screen) {
                screen.onExperienceUpdated(data.newExperience(), data.newLevel());
            }
        });
    }
}
