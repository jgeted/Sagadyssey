package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.npc.gui.NpcHandoverIntentScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ClientHandoverPromptHandler {
    private ClientHandoverPromptHandler() {}
    public static void handle(HandoverPromptPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) return;
            if (minecraft.level.getEntity(payload.npcId()) == null) return;
            minecraft.setScreen(new NpcHandoverIntentScreen(payload.npcId(), payload.hand(),
                    payload.npcName(), payload.itemName(), payload.availableCount()));
        });
    }
}
