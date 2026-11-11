package com.jgeted.sagadyssey.social.industry.common;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.core.HolderLookup;
import com.jgeted.sagadyssey.social.needs.NeedRequest;
import com.jgeted.sagadyssey.social.needs.NeedRequestStatus;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import java.util.*;

/** Schema2 native-operation facts and explicit entity titles. Never an inventory or spendable balance. */
public final class EntityResourceActionData extends NbtSavedData {
    public static final String NAME="sagadyssey_entity_resource_actions_v1";
    public static final Factory<EntityResourceActionData> FACTORY=new Factory<>(EntityResourceActionData::new,EntityResourceActionData::load);
    private final Map<UUID,CompoundTag> sources=new LinkedHashMap<>(),actions=new LinkedHashMap<>();
    // Process-local creation proof is intentionally absent after load/restart.
    private final Map<UUID,CompoundTag> liveUnstarted=new HashMap<>();
    private boolean loading,cancelling;
    public static boolean settled(String state){return state.equals("DONE")||state.equals("CANCELLED");}
    public static boolean terminal(String state){return settled(state)||state.equals("REVIEW");}
    private static boolean effectFreeReady(CompoundTag a){
        return a.getStringOr("State", "").equals("READY")&&a.get("Drops") instanceof ListTag drops&&drops.isEmpty()
                &&List.of("TargetBefore","ToolBefore","ToolDamageBefore","ToolDamageAfter","DeliveryReceipt","PickupReceipt").stream().noneMatch(a::contains);
    }
    public void createAction(CompoundTag a){
        if(actions.containsKey(a.read("Id", UUIDUtil.CODEC).orElseThrow())||!MinecraftApi.containsTag(a, "StartedAt",MinecraftApi.ANY_NUMERIC_TAG)||a.getLongOr("StartedAt", 0L)<0||!effectFreeReady(a))throw new IllegalStateException("fresh effect-free READY required");
        putAction(a);liveUnstarted.put(a.read("Id", UUIDUtil.CODEC).orElseThrow(),a.copy());
    }
    public boolean cancelUnstarted(UUID id,NeedRequest endedNeed,long now,String reason){
        var a=action(id);if(a==null||endedNeed==null||!a.read("Need", UUIDUtil.CODEC).orElseThrow().equals(endedNeed.id())||(!endedNeed.status().terminal()&&endedNeed.status()!=NeedRequestStatus.DELIVERED)||now<a.getLongOr("StartedAt", 0L))return false;
        if(a.getStringOr("State", "").equals("CANCELLED"))return true;
        var proof=liveUnstarted.get(id);
        if(proof==null||!effectFreeReady(a)||reason==null||reason.isBlank()||reason.length()>1000||now<a.getLongOr("StartedAt", 0L))return false;
        for(String key:List.of("Source","Need","Person","Body"))if(!proof.read(key, UUIDUtil.CODEC).orElseThrow().equals(a.read(key, UUIDUtil.CODEC).orElseThrow()))return false;
        if(proof.getLongOr("StartedAt", 0L)!=a.getLongOr("StartedAt", 0L))return false;
        a.putString("State","CANCELLED");a.putString("Detail","NO_NATIVE_EFFECT: "+reason);a.putLong("UpdatedAt",now);
        cancelling=true;try{putAction(a);}finally{cancelling=false;}
        return true;
    }
    public static EntityResourceActionData get(MinecraftServer s){return s.overworld().getDataStorage().computeIfAbsent(FACTORY.type(NAME));}
    public static EntityResourceActionData existing(MinecraftServer s){return s.overworld().getDataStorage().get(FACTORY.type(NAME));}
    public List<CompoundTag> sources(){return sources.values().stream().map(CompoundTag::copy).toList();}
    public List<CompoundTag> actions(){return actions.values().stream().map(CompoundTag::copy).toList();}
    public CompoundTag source(UUID id){var t=sources.get(id);return t==null?null:t.copy();}
    public CompoundTag action(UUID id){var t=actions.get(id);return t==null?null:t.copy();}
    boolean hasSourceCapacity(){return sources.size()<4096;}
    boolean hasActionCapacity(){return actions.size()<4096;}
    public void putSource(CompoundTag t){
        for(String key:List.of("Id","Target","Owner","Actor","Need"))if(!t.read(key, UUIDUtil.CODEC).isPresent())throw new IllegalArgumentException("source missing "+key);
        var kind=t.getStringOr("Kind", "");
        if((!Set.of("SHEAR","FISH_COD").contains(kind) && !NaturalIndustrySources.natural(kind))
                || !MinecraftApi.containsTag(t, "Output",Tag.TAG_COMPOUND))throw new IllegalArgumentException("source kind/output");
        if(NaturalIndustrySources.natural(kind) && (t.getStringOr("Dimension", "").isBlank()
                || !t.read("Owner", UUIDUtil.CODEC).orElseThrow().equals(t.read("Actor", UUIDUtil.CODEC).orElseThrow())
                || kind.equals(NaturalIndustrySources.ANGLING) && (!MinecraftApi.containsTag(t, "Water",Tag.TAG_LONG)||!MinecraftApi.containsTag(t, "Shore",Tag.TAG_LONG))))
            throw new IllegalArgumentException("incomplete natural opportunity");
        var old=sources.get(t.read("Id", UUIDUtil.CODEC).orElseThrow());if(old!=null && !old.equals(t))throw new IllegalStateException("registered entity title/need binding conflict");
        if(old==null && sources.size()>=4096)throw new IllegalStateException("explicit archival required");
        if(old==null){sources.put(t.read("Id", UUIDUtil.CODEC).orElseThrow(),t.copy());setDirty();}
    }
    public void putAction(CompoundTag t){
        for(String key:List.of("Id","Source","Need","Person","Body","Plan"))if(!t.read(key, UUIDUtil.CODEC).isPresent())throw new IllegalArgumentException("action missing "+key);
        if(!sources.containsKey(t.read("Source", UUIDUtil.CODEC).orElseThrow()))throw new IllegalArgumentException("unknown source");
        if(!Set.of("READY","ACTING","ACTED","DONE","REVIEW","CANCELLED").contains(t.getStringOr("State", "")))throw new IllegalArgumentException("action state");
        var old=actions.get(t.read("Id", UUIDUtil.CODEC).orElseThrow());
        if(old!=null)for(String key:List.of("Source","Need","Person","Body"))if(!old.read(key, UUIDUtil.CODEC).orElseThrow().equals(t.read(key, UUIDUtil.CODEC).orElseThrow()))throw new IllegalStateException("operation identity conflict");
        if(old!=null && terminal(old.getStringOr("State", "")) && !old.equals(t))throw new IllegalStateException("terminal native facts cannot be rewritten");
        if(t.getStringOr("State", "").equals("CANCELLED")){
            var ready=t.copy();ready.putString("State","READY");
            if(!effectFreeReady(ready)||!t.getStringOr("Detail", "").startsWith("NO_NATIVE_EFFECT: ")||t.getLongOr("UpdatedAt", 0L)<t.getLongOr("StartedAt", 0L))throw new IllegalArgumentException("invalid no-effect cancellation facts");
            if(!loading&&!cancelling&&(old==null||!old.equals(t)))throw new IllegalStateException("cancellation requires live creation proof");
        }
        if(!effectFreeReady(t))liveUnstarted.remove(t.read("Id", UUIDUtil.CODEC).orElseThrow());
        if(old==null && actions.size()>=4096)throw new IllegalStateException("native fact ledger full");
        if(!t.equals(old)){actions.put(t.read("Id", UUIDUtil.CODEC).orElseThrow(),t.copy());setDirty();}
    }
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider p){
        t.putInt("Schema",sources.values().stream().anyMatch(x->NaturalIndustrySources.natural(x.getStringOr("Kind", "")))?3:2);
        var ss=new ListTag();sources.values().forEach(x->ss.add(x.copy()));t.put("Sources",ss);
        var aa=new ListTag();actions.values().forEach(x->aa.add(x.copy()));t.put("Actions",aa);return t;
    }
    public static EntityResourceActionData load(CompoundTag t,HolderLookup.Provider p){
        int schema=t.getIntOr("Schema", 0);
        if((schema<1||schema>3) || !(t.get("Sources") instanceof ListTag ss) || !(t.get("Actions") instanceof ListTag aa)
                || ss.size()>4096 || aa.size()>4096)throw new IllegalArgumentException("invalid native schema");
        var d=new EntityResourceActionData();d.loading=true;for(var x:ss){var row=(CompoundTag)x;if(d.sources.containsKey(row.read("Id", UUIDUtil.CODEC).orElseThrow()))throw new IllegalArgumentException("duplicate native source");
            if(schema<3&&NaturalIndustrySources.natural(row.getStringOr("Kind", "")))throw new IllegalArgumentException("natural source requires schema3");
            d.putSource(row);}
        for(var x:aa){var row=(CompoundTag)x;if(t.getIntOr("Schema", 0)==1&&row.getStringOr("State", "").equals("CANCELLED"))throw new IllegalArgumentException("new cancellation cannot be disguised as schema1");if(d.actions.containsKey(row.read("Id", UUIDUtil.CODEC).orElseThrow()))throw new IllegalArgumentException("duplicate native operation");d.putAction(row);}d.loading=false;d.setDirty(false);return d;
    }
}
