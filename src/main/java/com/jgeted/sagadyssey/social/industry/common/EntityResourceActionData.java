package com.jgeted.sagadyssey.social.industry.common;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Approved Schema1 native-operation facts and explicit entity titles. Never an inventory or spendable balance. */
public final class EntityResourceActionData extends SavedData {
    public static final String NAME="sagadyssey_entity_resource_actions_v1";
    public static final Factory<EntityResourceActionData> FACTORY=new Factory<>(EntityResourceActionData::new,EntityResourceActionData::load);
    private final Map<UUID,CompoundTag> sources=new LinkedHashMap<>(),actions=new LinkedHashMap<>();
    public static EntityResourceActionData get(MinecraftServer s){return s.overworld().getDataStorage().computeIfAbsent(FACTORY,NAME);}
    public static EntityResourceActionData existing(MinecraftServer s){return s.overworld().getDataStorage().get(FACTORY,NAME);}
    public List<CompoundTag> sources(){return sources.values().stream().map(CompoundTag::copy).toList();}
    public List<CompoundTag> actions(){return actions.values().stream().map(CompoundTag::copy).toList();}
    public CompoundTag source(UUID id){var t=sources.get(id);return t==null?null:t.copy();}
    public CompoundTag action(UUID id){var t=actions.get(id);return t==null?null:t.copy();}
    public void putSource(CompoundTag t){
        for(String key:List.of("Id","Target","Owner","Actor","Need"))if(!t.hasUUID(key))throw new IllegalArgumentException("source missing "+key);
        if(!Set.of("SHEAR","FISH_COD").contains(t.getString("Kind")) || !t.contains("Output",Tag.TAG_COMPOUND))throw new IllegalArgumentException("source kind/output");
        var old=sources.get(t.getUUID("Id"));if(old!=null && !old.equals(t))throw new IllegalStateException("registered entity title/need binding conflict");
        if(old==null && sources.size()>=4096)throw new IllegalStateException("explicit archival required");
        if(old==null){sources.put(t.getUUID("Id"),t.copy());setDirty();}
    }
    public void putAction(CompoundTag t){
        for(String key:List.of("Id","Source","Need","Person","Body","Plan"))if(!t.hasUUID(key))throw new IllegalArgumentException("action missing "+key);
        if(!sources.containsKey(t.getUUID("Source")))throw new IllegalArgumentException("unknown source");
        if(!Set.of("READY","ACTING","ACTED","DONE","REVIEW").contains(t.getString("State")))throw new IllegalArgumentException("action state");
        var old=actions.get(t.getUUID("Id"));
        if(old!=null)for(String key:List.of("Source","Need","Person","Body"))if(!old.getUUID(key).equals(t.getUUID(key)))throw new IllegalStateException("operation identity conflict");
        if(old!=null && Set.of("DONE","REVIEW").contains(old.getString("State")) && !old.equals(t))throw new IllegalStateException("terminal native facts cannot be rewritten");
        if(old==null && actions.size()>=4096)throw new IllegalStateException("native fact ledger full");
        if(!t.equals(old)){actions.put(t.getUUID("Id"),t.copy());setDirty();}
    }
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider p){
        t.putInt("Schema",1);var ss=new ListTag();sources.values().forEach(x->ss.add(x.copy()));t.put("Sources",ss);
        var aa=new ListTag();actions.values().forEach(x->aa.add(x.copy()));t.put("Actions",aa);return t;
    }
    public static EntityResourceActionData load(CompoundTag t,HolderLookup.Provider p){
        if(t.getInt("Schema")!=1 || !(t.get("Sources") instanceof ListTag ss) || !(t.get("Actions") instanceof ListTag aa)
                || ss.size()>4096 || aa.size()>4096)throw new IllegalArgumentException("invalid native schema");
        var d=new EntityResourceActionData();for(var x:ss){var row=(CompoundTag)x;if(d.sources.containsKey(row.getUUID("Id")))throw new IllegalArgumentException("duplicate native source");d.putSource(row);}
        for(var x:aa){var row=(CompoundTag)x;if(d.actions.containsKey(row.getUUID("Id")))throw new IllegalArgumentException("duplicate native operation");d.putAction(row);}d.setDirty(false);return d;
    }
}
