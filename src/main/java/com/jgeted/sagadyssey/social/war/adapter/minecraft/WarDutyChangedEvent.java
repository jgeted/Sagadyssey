package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.appearance.NpcClothingVisualEvents;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.worldsim.transfer.ItemLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;

import java.util.UUID;

/** Refresh signal for read-only views when an original military duty changes between NPC ticks. */
@EventBusSubscriber(modid = "sagadyssey")
public final class WarDutyChangedEvent extends Event {
    private final NpcBase body;

    private WarDutyChangedEvent(NpcBase body) { this.body = body; }

    public NpcBase body() { return body; }

    @SubscribeEvent public static void refreshClothing(WarDutyChangedEvent event) {
        NpcClothingVisualEvents.refresh(event.body());
    }

    static void publish(MinecraftServer server, UUID person) {
        var registry = CharacterRegistryData.get(server);
        var original = registry.bodyOf(person);
        if (original == null) return;
        ItemLocation.personal(person, original.entityId()).body(server, registry)
                .ifPresent(body -> NeoForge.EVENT_BUS.post(new WarDutyChangedEvent(body)));
    }
}
