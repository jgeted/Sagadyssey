package com.jgeted.sagadyssey.worldsim.lod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Strict schema: a damaged checkpoint must never silently become an empty world. */
public final class NpcLodData extends SavedData {
    public static final String NAME="sagadyssey_npc_lod_v3";
    public static final int SCHEMA=1, CAPACITY=8192;
    public static final Factory<NpcLodData> FACTORY=new Factory<>(NpcLodData::new,NpcLodData::load);
    private final Map<UUID,LodCheckpoint> rows=new LinkedHashMap<>();
    private final Map<UUID,UUID> persons=new HashMap<>();
    private final TreeMap<Long,LinkedHashSet<UUID>> due=new TreeMap<>();
    public static NpcLodData get(MinecraftServer s){
        var storage=s.overworld().getDataStorage();var data=storage.get(FACTORY,NAME);if(data!=null)return data;
        if(java.nio.file.Files.exists(s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve(NAME+".dat")))throw new IllegalStateException("unreadable LOD checkpoint file; preserve and restore a coherent backup");
        data=new NpcLodData();storage.set(NAME,data);return data;
    }
    public LodCheckpoint find(UUID body){return rows.get(body);}
    public UUID bodyForPerson(UUID person){return persons.get(person);}
    public List<LodCheckpoint> all(){return List.copyOf(rows.values());}
    public void put(LodCheckpoint row){
        var old=rows.get(row.body());
        if(persons.containsKey(row.person())&&!persons.get(row.person()).equals(row.body()))throw new IllegalStateException("person already has an original LOD body");
        if(old==null&&rows.size()>=CAPACITY)throw new IllegalStateException("LOD checkpoint capacity reached");
        if(old!=null&&(!old.person().equals(row.person())||row.capturedAt()<old.capturedAt()||row.summarizedAt()<old.summarizedAt()))throw new IllegalStateException("checkpoint identity/time changed");
        unschedule(old);rows.put(row.body(),row);persons.put(row.person(),row.body());schedule(row);setDirty();
    }
    public void remove(UUID body){var old=rows.remove(body);if(old!=null){persons.remove(old.person());unschedule(old);setDirty();}}
    public List<UUID> due(long now,int limit){
        var result=new ArrayList<UUID>();
        while(result.size()<limit&&!due.isEmpty()&&due.firstKey()<=now){var entry=due.firstEntry();var it=entry.getValue().iterator();result.add(it.next());it.remove();if(entry.getValue().isEmpty())due.pollFirstEntry();}
        return result;
    }
    public int backlog(long now){int n=0;for(var e:due.headMap(now,true).values())n+=e.size();return n;}
    private void schedule(LodCheckpoint r){if(r.unloaded())due.computeIfAbsent(r.summarizedAt()+200,k->new LinkedHashSet<>()).add(r.body());}
    private void unschedule(LodCheckpoint r){if(r==null||!r.unloaded())return;var set=due.get(r.summarizedAt()+200);if(set!=null){set.remove(r.body());if(set.isEmpty())due.remove(r.summarizedAt()+200);}}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup){tag.putInt("Schema",SCHEMA);var list=new ListTag();rows.values().stream().sorted(Comparator.comparing(LodCheckpoint::body)).forEach(r->list.add(r.save()));tag.put("Checkpoints",list);return tag;}
    public static NpcLodData load(CompoundTag t,HolderLookup.Provider lookup){
        if(!t.contains("Schema",Tag.TAG_INT)||t.getInt("Schema")!=SCHEMA||!t.contains("Checkpoints",Tag.TAG_LIST))throw new IllegalStateException("unreadable LOD schema");
        var data=new NpcLodData();var list=(ListTag)t.get("Checkpoints");if(list.size()>CAPACITY||!list.isEmpty()&&list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalStateException("LOD data over capacity or wrong element type");
        for(var raw:list){var row=LodCheckpoint.load((CompoundTag)raw);if(data.rows.putIfAbsent(row.body(),row)!=null||data.persons.putIfAbsent(row.person(),row.body())!=null)throw new IllegalStateException("duplicate checkpoint body/person");data.schedule(row);}return data;
    }
}
