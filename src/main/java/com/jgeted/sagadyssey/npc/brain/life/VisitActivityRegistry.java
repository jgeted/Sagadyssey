package com.jgeted.sagadyssey.npc.brain.life;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import java.util.Map;
import java.util.TreeMap;

/** Server datapacks define activities; replacing definitions never mutates ongoing checkpoints. */
@EventBusSubscriber(modid = "sagadyssey")
public final class VisitActivityRegistry extends SimpleJsonResourceReloadListener<JsonElement> {
    private static final VisitActivityRegistry INSTANCE = new VisitActivityRegistry();
    private volatile Map<Identifier, VisitActivity> definitions = Map.of();

    public VisitActivityRegistry() { super(com.jgeted.sagadyssey.core.MinecraftApi.JSON_ELEMENT, net.minecraft.resources.FileToIdConverter.json("visit_activity")); }
    public static Map<Identifier, VisitActivity> definitions() { return INSTANCE.definitions; }

    @SubscribeEvent
    public static void addReloadListener(AddServerReloadListenersEvent event) { event.addListener(Identifier.fromNamespaceAndPath("sagadyssey", "visit_activity"), INSTANCE); }

    @Override
    protected void apply(Map<Identifier, JsonElement> entries, ResourceManager manager, ProfilerFiller profiler) {
        replace(entries);
    }

    void replace(Map<Identifier, JsonElement> entries) {
        Map<Identifier, VisitActivity> next = new TreeMap<>();
        entries.forEach((id, json) -> {
            if (!json.isJsonObject()) throw new IllegalArgumentException("activity must be an object: " + id);
            next.put(id, VisitActivity.parse(id, json.getAsJsonObject()));
        });
        definitions = Map.copyOf(next); // Publish only after every definition has passed validation.
    }

    Map<Identifier, VisitActivity> snapshot() { return definitions; }
}
