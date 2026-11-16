package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.client;

import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarManagementNetwork12111.Input;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarManagementNetwork12111.Output;
import com.jgeted.sagadyssey.social.war.api.WarManagementInteraction.Display;
import com.jgeted.sagadyssey.social.war.api.WarManagementInteraction.View;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

import java.util.UUID;

@EventBusSubscriber(modid = "sagadyssey", value = Dist.CLIENT)
public final class WarManagementClient12111 {
    private static UUID closed;
    private static View marking;

    private WarManagementClient12111() {}

    @SubscribeEvent public static void register(RegisterClientPayloadHandlersEvent e) {
        e.register(Output.TYPE, (packet, context) -> context.enqueueWork(() -> accept(packet.value())));
    }

    private static void accept(View view) {
        if (view.session().equals(closed)) return;
        var mc = Minecraft.getInstance();
        var current = mc.screen instanceof WarManagementScreen12111 panel && panel.session().equals(view.session()) ? panel : null;
        if (view.display() == Display.PANEL) {
            marking = null;
            if (current != null) current.accept(view);
            else mc.setScreen(new WarManagementScreen12111(view,
                    input -> ClientPacketDistributor.sendToServer(new Input(input))));
            return;
        }
        if (current != null) mc.setScreen(null);
        marking = view.display() == Display.WORLD ? view : null;
        if (marking == null && mc.player != null && !view.message().isBlank())
            mc.player.displayClientMessage(Component.literal(view.message()), true);
    }

    static void closed(UUID session) {
        closed = session;
        if (marking != null && marking.session().equals(session)) marking = null;
    }

    static void openedCommandMap() { marking = null; }

    @SubscribeEvent public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        marking = null;
        closed = null;
    }

    @SubscribeEvent public static void markHint(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || !mc.player.getMainHandItem().is(Items.FILLED_MAP)) return;
        String text;
        if (marking != null) text = marking.message();
        else if (mc.player.isShiftKeyDown())
            text = mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof NpcBase
                    ? "右键 NPC · 商议军务" : "朝空中右键 · 军令地图";
        else return;
        var g = event.getGuiGraphics();
        String hint = mc.font.plainSubstrByWidth(text, Math.max(100, g.guiWidth() - 16));
        g.drawCenteredString(mc.font, hint, g.guiWidth() / 2, g.guiHeight() - 59, 0xFFF0DCB4);
    }
}
