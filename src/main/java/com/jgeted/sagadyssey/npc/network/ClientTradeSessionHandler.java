package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.npc.gui.NpcNegotiationScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@OnlyIn(Dist.CLIENT)
final class ClientTradeSessionHandler {
    private ClientTradeSessionHandler() {}

    static void handle(TradeSessionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.level == null) return;
            var entity = minecraft.level.getEntity(payload.npcEntityId());
            if (entity == null || minecraft.player.distanceToSqr(entity) > 64.0D) return;
            if (minecraft.screen instanceof NpcNegotiationScreen screen
                    && screen.sessionId().equals(payload.sessionId())) {
                screen.update(payload);
            } else {
                minecraft.setScreen(new NpcNegotiationScreen(payload));
            }
        });
    }
}
