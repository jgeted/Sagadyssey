package com.jgeted.sagadyssey.npc.brain.life;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import java.util.Map;
import java.util.TreeMap;

/** Server datapacks define activities; replacing definitions never mutates ongoing checkpoints. */
@EventBusSubscriber(modid = "sagadyssey")
public final class VisitActivityRegistry extends SimpleJsonResourceReloadListener {
    private static final VisitActivityRegistry INSTANCE = new VisitActivityRegistry();
    private volatile Map<ResourceLocation, VisitActivity> definitions = Map.of();

    public VisitActivityRegistry() { super(new Gson(), "visit_activity"); }
    public static Map<ResourceLocation, VisitActivity> definitions() { return INSTANCE.definitions; }

    @SubscribeEvent
    public static void addReloadListener(AddReloadListenerEvent event) { event.addListener(INSTANCE); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager manager, ProfilerFiller profiler) {
        replace(entries);
    }

    void replace(Map<ResourceLocation, JsonElement> entries) {
        Map<ResourceLocation, VisitActivity> next = new TreeMap<>();
        entries.forEach((id, json) -> {
            if (!json.isJsonObject()) throw new IllegalArgumentException("activity must be an object: " + id);
            next.put(id, VisitActivity.parse(id, json.getAsJsonObject()));
        });
        definitions = Map.copyOf(next); // Publish only after every definition has passed validation.
    }

    Map<ResourceLocation, VisitActivity> snapshot() { return definitions; }
}
