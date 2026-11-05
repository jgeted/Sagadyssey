package com.jgeted.sagadyssey.npc.command.network;

import com.jgeted.sagadyssey.npc.command.ClientCommandGroupState;
import com.jgeted.sagadyssey.npc.command.gui.CommandWandScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client-only command overlay and screen updates. */
@OnlyIn(Dist.CLIENT)
final class ClientCommandGroupSyncHandler {
    private ClientCommandGroupSyncHandler() {}

    static void handle(CommandGroupSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientCommandGroupState.update(payload);
            Minecraft mc = Minecraft.getInstance();
            if (payload.openScreen() || mc.screen instanceof CommandWandScreen) {
                mc.setScreen(new CommandWandScreen(payload));
            }
        });
    }
}
