package com.jgeted.sagadyssey.npc.appearance;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Projects a server-provided outfit over the ordinary NPC appearance. */
@EventBusSubscriber(modid = "sagadyssey")
public final class NpcClothingVisualEvents {
    public static final String OUTFIT_PREVIEW = "SagadysseyClothingOutfitPreview";
    public static final String PALETTE_PREVIEW = "SagadysseyClothingPalettePreview";
    private static final Map<MinecraftServer, Function<NpcBase, String>> OVERLAYS = new ConcurrentHashMap<>();
    private NpcClothingVisualEvents() {}

    /** The read-only provider returns an empty string to retain the ordinary outfit. */
    public static void bindOutfitOverlay(MinecraftServer server, Function<NpcBase, String> overlay) {
        if (!server.isSameThread()) throw new IllegalStateException("outfit overlay binding requires server thread");
        if (OVERLAYS.putIfAbsent(server, Objects.requireNonNull(overlay)) != null)
            throw new IllegalStateException("outfit overlay already bound to this server");
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { OVERLAYS.remove(event.getServer()); }
    @SubscribeEvent public static void joined(EntityJoinLevelEvent event) {
        // Entity data is not an NBT cache. Rebuild the projection before a loaded
        // NPC is tracked, including passengers and chunks outside simulation distance.
        if(event.getEntity() instanceof NpcBase npc && npc.level() instanceof ServerLevel)refresh(npc);
    }
    @SubscribeEvent public static void tick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof NpcBase npc && npc.level() instanceof ServerLevel
                && (npc.tickCount <= 1 || (npc.tickCount + npc.getId()) % 20 == 0)) refresh(npc);
    }
    public static void refresh(NpcBase npc) {
        if (!(npc.level() instanceof ServerLevel level)) return;
        var overlay = OVERLAYS.get(level.getServer());
        String outfit = overlay == null ? "" : overlay.apply(npc);
        String preview = npc.getPersistentData().getStringOr(OUTFIT_PREVIEW, "");
        if (preview.equals("civilian")) outfit = "";
        else if (NpcMilitaryOutfit.find(preview).isPresent() || NpcMilitaryRole.find(preview).isPresent()) outfit = preview;
        String faction = npc.getFaction() == null ? "" : npc.getFaction().id();
        if (!outfit.isEmpty() && faction.equals("sagadyssey:player") && npc.getOriginalFaction() != null)
            faction = npc.getOriginalFaction();
        npc.setClothingVisual(outfit, faction, npc.getPersistentData().getStringOr(PALETTE_PREVIEW, ""));
    }
}
