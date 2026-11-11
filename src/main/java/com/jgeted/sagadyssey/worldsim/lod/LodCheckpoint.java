package com.jgeted.sagadyssey.worldsim.lod;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.*;
import java.util.*;

/** No inventory or executable task copy: only original identities, revisions and receipt boundary. */
public record LodCheckpoint(UUID body, UUID person, String dimension, long position,
                            UUID plan, String planRevision, int lastCommittedStep,
                            Map<UUID, Long> assetRevisions, Map<UUID, String> orderRevisions,
                            Set<UUID> reservations, String inventoryDigest,
                            long capturedAt, long summarizedAt, boolean unloaded, String reconciliation) {
    public LodCheckpoint {
        if (body == null || person == null || dimension == null || dimension.isBlank()
                || net.minecraft.resources.Identifier.tryParse(dimension) == null
                || planRevision == null || !(plan == null ? planRevision.isEmpty() : digest(planRevision))
                || !digest(inventoryDigest)
                || lastCommittedStep < -1 || lastCommittedStep >= 64 || plan == null && lastCommittedStep != -1
                || capturedAt < 0 || summarizedAt < capturedAt
                || reconciliation == null || reconciliation.length() > 512)
            throw new IllegalArgumentException("invalid LOD checkpoint");
        assetRevisions = Map.copyOf(assetRevisions); orderRevisions = Map.copyOf(orderRevisions);
        reservations = Set.copyOf(reservations);
        if (assetRevisions.size() > 64 || orderRevisions.size() > 64 || reservations.size() > 256
                || assetRevisions.values().stream().anyMatch(v -> v < 0)
                || orderRevisions.values().stream().anyMatch(v -> !digest(v)))
            throw new IllegalArgumentException("checkpoint exceeds revision bounds");
    }
    private static boolean digest(String s) { return s != null && s.matches("[0-9a-f]{64}"); }
    public LodCheckpoint summary(long now) {
        return new LodCheckpoint(body, person, dimension, position, plan, planRevision, lastCommittedStep,
                assetRevisions, orderRevisions, reservations, inventoryDigest, capturedAt, now, unloaded, reconciliation);
    }
    public CompoundTag save() {
        var t = new CompoundTag(); t.store("Body", UUIDUtil.CODEC,body); t.store("Person", UUIDUtil.CODEC,person);
        t.putString("Dimension",dimension); t.putLong("Position",position);
        if(plan!=null)t.store("Plan", UUIDUtil.CODEC,plan); t.putString("PlanRevision",planRevision);
        t.putInt("LastCommittedStep",lastCommittedStep); t.putString("InventoryDigest",inventoryDigest);
        t.putLong("CapturedAt",capturedAt); t.putLong("SummarizedAt",summarizedAt);
        t.putBoolean("Unloaded",unloaded); t.putString("Reconciliation",reconciliation);
        var assets=new ListTag(); assetRevisions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{var r=new CompoundTag();r.store("Id", UUIDUtil.CODEC,e.getKey());r.putLong("Revision",e.getValue());assets.add(r);});t.put("Assets",assets);
        var orders=new ListTag();orderRevisions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{var r=new CompoundTag();r.store("Id", UUIDUtil.CODEC,e.getKey());r.putString("Revision",e.getValue());orders.add(r);});t.put("Orders",orders);
        var held=new ListTag();reservations.stream().sorted().forEach(id->{var r=new CompoundTag();r.store("Id", UUIDUtil.CODEC,id);held.add(r);});t.put("Reservations",held);
        return t;
    }
    public static LodCheckpoint load(CompoundTag t) {
        for(String k:List.of("Body","Person"))if(!t.read(k, UUIDUtil.CODEC).isPresent())throw new IllegalArgumentException("missing checkpoint identity");
        for(String k:List.of("Dimension","PlanRevision","InventoryDigest","Reconciliation"))if(!MinecraftApi.containsTag(t, k,Tag.TAG_STRING))throw new IllegalArgumentException("missing checkpoint text");
        for(String k:List.of("Position","CapturedAt","SummarizedAt"))if(!MinecraftApi.containsTag(t, k,Tag.TAG_LONG))throw new IllegalArgumentException("missing checkpoint time/position");
        if(!MinecraftApi.containsTag(t, "LastCommittedStep",Tag.TAG_INT)||!MinecraftApi.containsTag(t, "Unloaded",Tag.TAG_BYTE))throw new IllegalArgumentException("missing checkpoint boundary");
        for(String k:List.of("Assets","Orders","Reservations")) {
            if(!MinecraftApi.containsTag(t, k,Tag.TAG_LIST))throw new IllegalArgumentException("missing checkpoint collection");
            var list=(ListTag)t.get(k);
            if(!list.isEmpty()&&MinecraftApi.elementType(list)!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("wrong checkpoint collection type");
        }
        if(t.contains("Plan")&&!t.read("Plan", UUIDUtil.CODEC).isPresent())throw new IllegalArgumentException("invalid plan identity");
        Map<UUID,Long> assets=new HashMap<>();Map<UUID,String> orders=new HashMap<>();Set<UUID> held=new HashSet<>();
        var a=MinecraftApi.listOfType(t, "Assets",Tag.TAG_COMPOUND);var o=MinecraftApi.listOfType(t, "Orders",Tag.TAG_COMPOUND);var r=MinecraftApi.listOfType(t, "Reservations",Tag.TAG_COMPOUND);
        if(a.size()>64||o.size()>64||r.size()>256)throw new IllegalArgumentException("checkpoint collection too large");
        for(var raw:a){var e=(CompoundTag)raw;if(!e.read("Id", UUIDUtil.CODEC).isPresent()||!MinecraftApi.containsTag(e, "Revision",Tag.TAG_LONG)||assets.put(e.read("Id", UUIDUtil.CODEC).orElseThrow(),e.getLongOr("Revision", 0L))!=null)throw new IllegalArgumentException("bad asset revision");}
        for(var raw:o){var e=(CompoundTag)raw;if(!e.read("Id", UUIDUtil.CODEC).isPresent()||e.getStringOr("Revision", "").length()!=64||orders.put(e.read("Id", UUIDUtil.CODEC).orElseThrow(),e.getStringOr("Revision", ""))!=null)throw new IllegalArgumentException("bad order revision");}
        for(var raw:r){var e=(CompoundTag)raw;if(!e.read("Id", UUIDUtil.CODEC).isPresent()||!held.add(e.read("Id", UUIDUtil.CODEC).orElseThrow()))throw new IllegalArgumentException("bad reservation identity");}
        return new LodCheckpoint(t.read("Body", UUIDUtil.CODEC).orElseThrow(),t.read("Person", UUIDUtil.CODEC).orElseThrow(),t.getStringOr("Dimension", ""),t.getLongOr("Position", 0L),t.read("Plan", UUIDUtil.CODEC).isPresent()?t.read("Plan", UUIDUtil.CODEC).orElseThrow():null,t.getStringOr("PlanRevision", ""),t.getIntOr("LastCommittedStep", 0),assets,orders,held,t.getStringOr("InventoryDigest", ""),t.getLongOr("CapturedAt", 0L),t.getLongOr("SummarizedAt", 0L),t.getBooleanOr("Unloaded", false),t.getStringOr("Reconciliation", ""));
    }
}
