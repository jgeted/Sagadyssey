package com.jgeted.sagadyssey.social.world;

import com.jgeted.sagadyssey.Sagadyssey;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import com.jgeted.sagadyssey.social.politics.PoliticalStateData;

/** 初始化并向进入世界的玩家展示当前世界局势。 */
public final class WorldSocialEvents {
    private WorldSocialEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        WorldScenarioType scenario = WorldScenarioData.get(event.getServer()).scenario();
        PoliticalStateData.get(event.getServer());
        Sagadyssey.LOGGER.info("世界社会场景已加载: {}", scenario.id());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        WorldScenarioType scenario = WorldScenarioData.get(player.server).scenario();
        player.sendSystemMessage(Component.literal("§6当前世界局势：§f").append(scenario.displayName()));
        player.sendSystemMessage(scenario.description().copy().withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
