package com.jgeted.sagadyssey.worldsim.lod;

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
                || net.minecraft.resources.ResourceLocation.tryParse(dimension) == null
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
        var t = new CompoundTag(); t.putUUID("Body",body); t.putUUID("Person",person);
        t.putString("Dimension",dimension); t.putLong("Position",position);
        if(plan!=null)t.putUUID("Plan",plan); t.putString("PlanRevision",planRevision);
        t.putInt("LastCommittedStep",lastCommittedStep); t.putString("InventoryDigest",inventoryDigest);
        t.putLong("CapturedAt",capturedAt); t.putLong("SummarizedAt",summarizedAt);
        t.putBoolean("Unloaded",unloaded); t.putString("Reconciliation",reconciliation);
        var assets=new ListTag(); assetRevisions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{var r=new CompoundTag();r.putUUID("Id",e.getKey());r.putLong("Revision",e.getValue());assets.add(r);});t.put("Assets",assets);
        var orders=new ListTag();orderRevisions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{var r=new CompoundTag();r.putUUID("Id",e.getKey());r.putString("Revision",e.getValue());orders.add(r);});t.put("Orders",orders);
        var held=new ListTag();reservations.stream().sorted().forEach(id->{var r=new CompoundTag();r.putUUID("Id",id);held.add(r);});t.put("Reservations",held);
        return t;
    }
    public static LodCheckpoint load(CompoundTag t) {
        for(String k:List.of("Body","Person"))if(!t.hasUUID(k))throw new IllegalArgumentException("missing checkpoint identity");
        for(String k:List.of("Dimension","PlanRevision","InventoryDigest","Reconciliation"))if(!t.contains(k,Tag.TAG_STRING))throw new IllegalArgumentException("missing checkpoint text");
        for(String k:List.of("Position","CapturedAt","SummarizedAt"))if(!t.contains(k,Tag.TAG_LONG))throw new IllegalArgumentException("missing checkpoint time/position");
        if(!t.contains("LastCommittedStep",Tag.TAG_INT)||!t.contains("Unloaded",Tag.TAG_BYTE))throw new IllegalArgumentException("missing checkpoint boundary");
        for(String k:List.of("Assets","Orders","Reservations")) {
            if(!t.contains(k,Tag.TAG_LIST))throw new IllegalArgumentException("missing checkpoint collection");
            var list=(ListTag)t.get(k);
            if(!list.isEmpty()&&list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("wrong checkpoint collection type");
        }
        if(t.contains("Plan")&&!t.hasUUID("Plan"))throw new IllegalArgumentException("invalid plan identity");
        Map<UUID,Long> assets=new HashMap<>();Map<UUID,String> orders=new HashMap<>();Set<UUID> held=new HashSet<>();
        var a=t.getList("Assets",Tag.TAG_COMPOUND);var o=t.getList("Orders",Tag.TAG_COMPOUND);var r=t.getList("Reservations",Tag.TAG_COMPOUND);
        if(a.size()>64||o.size()>64||r.size()>256)throw new IllegalArgumentException("checkpoint collection too large");
        for(var raw:a){var e=(CompoundTag)raw;if(!e.hasUUID("Id")||!e.contains("Revision",Tag.TAG_LONG)||assets.put(e.getUUID("Id"),e.getLong("Revision"))!=null)throw new IllegalArgumentException("bad asset revision");}
        for(var raw:o){var e=(CompoundTag)raw;if(!e.hasUUID("Id")||e.getString("Revision").length()!=64||orders.put(e.getUUID("Id"),e.getString("Revision"))!=null)throw new IllegalArgumentException("bad order revision");}
        for(var raw:r){var e=(CompoundTag)raw;if(!e.hasUUID("Id")||!held.add(e.getUUID("Id")))throw new IllegalArgumentException("bad reservation identity");}
        return new LodCheckpoint(t.getUUID("Body"),t.getUUID("Person"),t.getString("Dimension"),t.getLong("Position"),t.hasUUID("Plan")?t.getUUID("Plan"):null,t.getString("PlanRevision"),t.getInt("LastCommittedStep"),assets,orders,held,t.getString("InventoryDigest"),t.getLong("CapturedAt"),t.getLong("SummarizedAt"),t.getBoolean("Unloaded"),t.getString("Reconciliation"));
    }
}
