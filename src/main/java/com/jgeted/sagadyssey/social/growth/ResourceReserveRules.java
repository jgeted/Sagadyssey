package com.jgeted.sagadyssey.social.growth;

import com.google.gson.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.*;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import java.util.*;

/** Datapack policy only; no new inventory, rights or persistent task ledger. */
@EventBusSubscriber(modid="sagadyssey")
public final class ResourceReserveRules extends SimpleJsonResourceReloadListener<JsonElement> {
    private static final ResourceReserveRules INSTANCE=new ResourceReserveRules();
    private volatile List<Rule> rules=List.of();
    public record Rule(Identifier id,TagKey<Block> sources,TagKey<Item> stock,
                       int base,int perPerson,int ceiling) {
        public Rule { if(base<1||perPerson<0||ceiling<base||ceiling>4096)throw new IllegalArgumentException("invalid reserve bounds"); }
        public int target(int living){if(living<0)throw new IllegalArgumentException("negative population");return (int)Math.min(ceiling,(long)base+(long)perPerson*living);}
    }
    public ResourceReserveRules(){super(com.jgeted.sagadyssey.core.MinecraftApi.JSON_ELEMENT, net.minecraft.resources.FileToIdConverter.json("resource_reserves"));}
    public static List<Rule> rules(){return INSTANCE.rules;}
    public static Rule parse(Identifier id,JsonObject json){
        return new Rule(id,TagKey.create(Registries.BLOCK,Identifier.parse(json.get("source_tag").getAsString())),
                TagKey.create(Registries.ITEM,Identifier.parse(json.get("stock_tag").getAsString())),
                json.get("base").getAsInt(),json.get("per_person").getAsInt(),json.get("ceiling").getAsInt());
    }
    @SubscribeEvent public static void register(AddServerReloadListenersEvent e){e.addListener(Identifier.fromNamespaceAndPath("sagadyssey", "resource_reserves"), INSTANCE);}
    @Override protected void apply(Map<Identifier,JsonElement> entries,ResourceManager manager,ProfilerFiller profiler){
        var next=new TreeMap<Identifier,Rule>();entries.forEach((id,json)->next.put(id,parse(id,json.getAsJsonObject())));
        rules=List.copyOf(next.values());
    }
}
