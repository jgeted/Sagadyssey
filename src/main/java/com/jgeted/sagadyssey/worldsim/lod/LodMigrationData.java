package com.jgeted.sagadyssey.worldsim.lod;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.*;

/** Bounded intent/result journal. No item stacks, copied characters or executable plans. */
public final class LodMigrationData extends NbtSavedData {
    public static final String NAME="sagadyssey_lod_migrations_v3";
    public static final Factory<LodMigrationData> FACTORY=new Factory<>(LodMigrationData::new,LodMigrationData::load);
    private final Map<UUID,CompoundTag> rows=new LinkedHashMap<>();
    public static LodMigrationData get(MinecraftServer s){var storage=s.overworld().getDataStorage();var d=storage.get(FACTORY.type(NAME));if(d!=null)return d;if(Files.exists(s.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME+".dat")))throw new IllegalStateException("unreadable LOD migration journal");d=new LodMigrationData();storage.set(FACTORY.type(NAME),d);return d;}
    public CompoundTag find(UUID id){var r=rows.get(id);return r==null?null:r.copy();}
    public void put(UUID id,CompoundTag row){
        Objects.requireNonNull(id);validate(row);var previous=rows.get(id);
        if(previous==null){
            if(rows.size()>=64)throw new IllegalStateException("migration journal full; explicit archival required");
            if(!row.getStringOr("State", "").equals("INTENT"))throw new IllegalStateException("migration must begin with durable intent");
        } else {
            if(!previous.getStringOr("Before", "").equals(row.getStringOr("Before", ""))||previous.getLongOr("Time", 0L)!=row.getLongOr("Time", 0L)||!previous.get("Targets").equals(row.get("Targets"))||!Objects.equals(previous.get("BeforeCounts"),row.get("BeforeCounts")))throw new IllegalStateException("migration original evidence is immutable");
            var from=previous.getStringOr("State", "");var to=row.getStringOr("State", "");
            if(!previous.equals(row)&&!(from.equals("INTENT")&&to.equals("APPLIED")||from.equals("APPLIED")&&to.equals("ROLLED_BACK")&&previous.getStringOr("After", "").equals(row.getStringOr("After", ""))&&Objects.equals(previous.get("AfterCounts"),row.get("AfterCounts"))))throw new IllegalStateException("invalid migration transition");
        }
        rows.put(id,row.copy());setDirty();
    }
    private static void validate(CompoundTag r){
        if(!Set.of("INTENT","APPLIED","ROLLED_BACK").contains(r.getStringOr("State", ""))||!r.getStringOr("Before", "").matches("[0-9a-f]{64}")||!MinecraftApi.containsTag(r, "Time",Tag.TAG_LONG)||r.getLongOr("Time", 0L)<0||!(r.get("Targets") instanceof ListTag list)||list.isEmpty()||list.size()>100||MinecraftApi.elementType(list)!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("invalid migration intent");
        if(!r.getStringOr("State", "").equals("INTENT")&&!r.getStringOr("After", "").matches("[0-9a-f]{64}"))throw new IllegalArgumentException("missing migration result revision");
        for(var key:List.of("BeforeCounts","AfterCounts"))if(r.contains(key)) {
            if(!MinecraftApi.containsTag(r, key,Tag.TAG_COMPOUND))throw new IllegalArgumentException("invalid migration count report");
            var counts=r.getCompoundOrEmpty(key);for(var field:List.of("Bodies","People","ItemUnits","Orders","ActivePlans"))
                if(!MinecraftApi.containsTag(counts, field,Tag.TAG_INT)||counts.getIntOr(field, 0)<0)throw new IllegalArgumentException("invalid migration count field");
            if(counts.getIntOr("Bodies", 0)!=list.size()||counts.getIntOr("People", 0)!=list.size()||counts.getIntOr("ActivePlans", 0)>list.size())throw new IllegalArgumentException("migration counts do not match original identities");
        }
        Set<UUID> ids=new HashSet<>(),people=new HashSet<>();for(var raw:list){var e=(CompoundTag)raw;if(!e.read("Body", UUIDUtil.CODEC).isPresent()||!e.read("Person", UUIDUtil.CODEC).isPresent()||!ids.add(e.read("Body", UUIDUtil.CODEC).orElseThrow())||!people.add(e.read("Person", UUIDUtil.CODEC).orElseThrow())||!e.getStringOr("Mode", "").equals("LEGACY"))throw new IllegalArgumentException("invalid migration original identity/mode");}
    }
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider lookup){t.putInt("Schema",1);var list=new ListTag();rows.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{var row=e.getValue().copy();row.store("Id", UUIDUtil.CODEC,e.getKey());list.add(row);});t.put("Migrations",list);return t;}
    public static LodMigrationData load(CompoundTag t,HolderLookup.Provider lookup){if(t.getIntOr("Schema", 0)!=1||!(t.get("Migrations") instanceof ListTag list)||list.size()>64||!list.isEmpty()&&MinecraftApi.elementType(list)!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("invalid migration schema");var d=new LodMigrationData();for(var raw:list){var r=(CompoundTag)raw;if(!r.read("Id", UUIDUtil.CODEC).isPresent()||d.rows.containsKey(r.read("Id", UUIDUtil.CODEC).orElseThrow()))throw new IllegalArgumentException("duplicate migration");validate(r);var copy=r.copy();copy.remove("Id");d.rows.put(r.read("Id", UUIDUtil.CODEC).orElseThrow(),copy);}return d;}
}
