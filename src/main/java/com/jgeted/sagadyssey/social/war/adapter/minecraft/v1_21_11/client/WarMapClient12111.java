package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.client;

import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarMapNetwork12111.RequestPayload;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarMapNetwork12111.UpdatePayload;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarManagementNetwork12111.Enter;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

@EventBusSubscriber(modid="sagadyssey",value=Dist.CLIENT)
public final class WarMapClient12111 {
    @SubscribeEvent public static void register(RegisterClientPayloadHandlersEvent e){e.register(UpdatePayload.TYPE,(p,c)->c.enqueueWork(()->{
        var mc=Minecraft.getInstance();
        var update = p.value();
        if(update.openScreen()&&update.view().isPresent()) {
            WarManagementClient12111.openedCommandMap();
            mc.setScreen(new WarMapScreen12111(update,request->ClientPacketDistributor.sendToServer(new RequestPayload(request)),null,
                    view -> ClientPacketDistributor.sendToServer(new Enter(view.session(), view.revision()))));
        }
        else if(mc.screen instanceof WarMapScreen12111 screen && screen.session().equals(update.session()))screen.accept(update);
    }));}
}
