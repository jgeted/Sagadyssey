package com.jgeted.sagadyssey.social.industry.common;

import java.util.*;
import static com.jgeted.sagadyssey.social.industry.common.IndustryContract.*;

/** Curated, bounded lineage, not a runtime self-certification API. New effect paths are closed by default. */
public final class IndustryCatalog {
    public static final String BASELINE = "55a31d50208d790c1cd39a927a83c82a0fbd1baaebe2b91ca6ed594d1765b6b5";
    private static final Map<String,Capability> CAPABILITIES;
    static {
        var c = new LinkedHashMap<String,Capability>();
        add(c,"food.carrot",Industry.FOOD,Map.of("world:mature_carrot",1),true,false,
                Grade.MIXED,"final-growth-55a/two-round-production-consumption",true,false,
                "Controlled loaded carrot fields, lawful seed protection and actual meals; not worldgen cold start");
        add(c,"forestry.fell",Industry.FORESTRY,Map.of("world:authorized_log",1),true,false,
                Grade.EXECUTION,"final-growth-55a/14-real-logs",true,false,"Finite known timber; no sapling renewal proof");
        add(c,"forestry.regrow",Industry.FORESTRY,Map.of("minecraft:oak_sapling",1),false,false,
                Grade.NONE,"pending/tree-regrowth-integration",false,false,"Existing PLANT transaction may be reusable; forest intent, seed retention and renewal integration are not verified");
        add(c,"stone.mine",Industry.STONE,Map.of("world:authorized_stone",1),true,false,
                Grade.MIXED,"final-growth-55a/20-mined-183-embedded",true,false,"Finite authorized quarry; not renewable stone");
        add(c,"metal.ore-to-tool",Industry.METAL,Map.of("world:authorized_iron_ore",1),true,true,
                Grade.NONE,"pending/planned-native-furnace-handoff",false,false,"Single-owner native iron bridge approved; full ore-to-worker cycle evidence not yet integrated into this view");
        add(c,"wool.shear",Industry.WOOL,Map.of("world:authorized_fleeced_sheep",1),true,false,
                Grade.NONE,"pending/entity-rights-and-receipt",false,false,"Owned white-sheep bridge approved; controlled evidence awaits version/scope integration; pig production remains separate");
        add(c,"fish.capture",Industry.FISH,Map.of("world:authorized_fish",1),false,false,
                Grade.NONE,"pending/entity-rights-and-receipt",false,false,"Owned finite-cod bridge approved; controlled evidence awaits version/scope integration; no renewable fishery claim");
        add(c,"workshop.wood",Industry.WORKSHOP,Map.of("minecraft:oak_log",1),false,true,
                Grade.EXECUTION,"final-growth-55a/wooden-parts-and-use",true,false,"Existing explicit recipes only; quantities rechecked by ProductionService");
        add(c,"logistics.delivery",Industry.LOGISTICS,Map.of("source:disposable_unit",1),false,false,
                Grade.MIXED,"final-growth-55a/original-food-routes",true,false,"Known authorized loaded route; no arbitrary unloaded transport");
        CAPABILITIES = Collections.unmodifiableMap(c);
    }
    private IndustryCatalog() {}
    private static void add(Map<String,Capability> c,String id,Industry industry,Map<String,Integer> inputs,
                            boolean tool,boolean facility,Grade grade,String evidence,boolean connected,
                            boolean approval,String boundary) {
        c.put(id,new Capability(id,industry,inputs,tool,facility,
                new Evidence(evidence,BASELINE,grade,grade == Grade.NONE ? Set.of() : Set.of(Mode.CONTROLLED_NEAR),boundary),connected,approval));
    }
    public static Collection<Capability> all() { return CAPABILITIES.values(); }
    public static Optional<Capability> find(String id) { return Optional.ofNullable(CAPABILITIES.get(id)); }
    public static Capability require(String id) { return find(id).orElseThrow(() -> new IllegalArgumentException("unknown capability: "+id)); }
}
