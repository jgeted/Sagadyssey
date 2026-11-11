package com.jgeted.sagadyssey.social.religion.life;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid="sagadyssey")
public final class ChurchLifeEvents {
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){if(e.getServer().overworld().getGameTime()%20==0)ChurchLifeService.tick(e.getServer());}
    /** Native book UI must obey the same record policy as the command UI. */
    @SubscribeEvent public static void book(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem e){
        if(!(e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p)||e.getItemStack().get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT)==null)return;
        var fingerprint=com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint.of(e.getItemStack());
        var record=ChurchLifeData.get(p.level().getServer()).records().stream().filter(r->r.physical()!=null&&r.physical().equals(fingerprint)).findFirst().orElse(null);if(record==null)return;
        var person=com.jgeted.sagadyssey.social.religion.ReligionService.currentCharacter(p);
        if(person==null||!ChurchLifeService.canRead(p.level().getServer(),record,person)){
            e.setCanceled(true);e.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);if(p.connection!=null)p.sendSystemMessage(net.minecraft.network.chat.Component.literal("本册属于受限制文书；没有本人的合法查阅资格，不打开私密正文。"));return;
        }
        ChurchLifeService.learn(p.level().getServer(),person,record);
    }
}
