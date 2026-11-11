package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.gui.NpcCommandScreen;
import com.jgeted.sagadyssey.npc.gui.NpcRecruitScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client-only behavior kept out of the common payload class for dedicated-server safety. */
final class ClientNpcStatsPayloadHandler {
    private ClientNpcStatsPayloadHandler() {}

    static void handle(NpcStatsPayload data, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            // This response may open a screen, but must never replace an unrelated screen.
            if (!NpcInteractionPacket.consumeExpectedStatsResponse(data.npcId())
                    || mc.player == null || mc.level == null || mc.screen != null) return;
            var entity = mc.level.getEntity(data.npcId());
            if (!(entity instanceof NpcBase npc) || !npc.isAlive()
                    || mc.player.distanceToSqr(npc) > 64.0D) return;
            mc.setScreen(data.isOwned() ? new NpcCommandScreen(data) : new NpcRecruitScreen(data));
        });
    }
}
