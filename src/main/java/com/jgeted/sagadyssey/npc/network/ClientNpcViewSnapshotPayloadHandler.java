package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.gui.NpcContextScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@OnlyIn(Dist.CLIENT)
final class ClientNpcViewSnapshotPayloadHandler {
    private ClientNpcViewSnapshotPayloadHandler() {}

    static void handle(NpcViewSnapshotPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            int entityId = payload.view().bodyEntityId();
            if (minecraft.player == null || minecraft.level == null) return;
            var entity = minecraft.level.getEntity(entityId);
            if (!(entity instanceof NpcBase npc) || !npc.isAlive()
                    || minecraft.player.distanceToSqr(npc) > 64.0D) return;
            if (minecraft.screen instanceof NpcContextScreen screen
                    && screen.matchesEntity(entityId)) {
                screen.update(payload.view());
                return;
            }
            if (!NpcInteractionPacket.consumeExpectedStatsResponse(entityId)
                    || minecraft.screen != null) return;
            minecraft.setScreen(new NpcContextScreen(payload.view()));
        });
    }
}
